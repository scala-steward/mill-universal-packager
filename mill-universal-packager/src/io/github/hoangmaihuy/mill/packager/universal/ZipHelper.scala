package io.github.hoangmaihuy.mill.packager.universal

import java.io.File
import java.net.URI
import java.nio.file.{FileSystem, FileSystems, Files}
import java.util.zip.Deflater
import scala.jdk.CollectionConverters._

import org.apache.commons.compress.archivers.zip._
import org.apache.commons.io.IOUtils

/** Module with functions associated with processing zip files.
  *
  * @see
  *   http://stackoverflow.com/questions/17888365/file-permissions-are-not-being-preserved-while-after-zip
  * @see
  *   http://stackoverflow.com/questions/3450250/is-it-possible-to-create-a-script-to-save-and-restore-permissions
  * @see
  *   http://stackoverflow.com/questions/1050560/maintain-file-permissions-when-extracting-from-a-zip-file-using-jdk-5-api
  * @see
  *   http://docs.oracle.com/javase/7/docs/technotes/guides/io/fsp/zipfilesystemprovider.html
  */
object ZipHelper {
  case class FileMapping(file: File, name: String, unixMode: Option[Int] = None)

  /** Creates a zip file attempting to give files the appropriate unix permissions using Java 6 APIs.
    * @param sources
    *   The files to include in the zip file.
    * @param outputZip
    *   The location of the output file.
    */
  /** def zipNative(sources: Iterable[(File, String)], outputZip: File): Unit = IO.withTemporaryDirectory { dir => val
    * name = outputZip.getName val zipDir = dir / (if (name endsWith ".zip") name dropRight 4 else name) val files = for
    * { (file, name) <- sources } yield file -> (zipDir / name) IO.copy(files) for { (src, target) <- files if
    * src.canExecute } target.setExecutable(true, true)
    *
    * sourceDateEpoch(zipDir)
    *
    * val dirFileNames = Option(zipDir.listFiles) getOrElse Array.empty[java.io.File] map (_.getName)
    * sys.process.Process(Seq("zip", "-o", "-r", name) ++ dirFileNames, zipDir).! match { case 0 => () case n =>
    * sys.error("Failed to run native zip application!") }
    *
    * IO.copyFile(zipDir / name, outputZip) }
    */

  /** Creates a zip file with the apache commons compressor library.
    *
    * Note: This is known to have some odd issues on macOS whereby executable permissions are not actually discovered,
    * even though the Info-Zip headers exist and work on many variants of linux. Yay Apple.
    *
    * @param sources
    *   The files to include in the zip file.
    * @param outputZip
    *   The location of the output file.
    */
  def zip(sources: Iterable[(os.Path, os.SubPath)], outputZip: os.Path): Unit = {
    import io.github.hoangmaihuy.mill.packager.permissions.OctalString
    val mappings =
      for {
        (path, name) <- sources.toSeq
        file = path.toIO
        // TODO - Figure out if this is good enough....
        perm =
          if (file.isDirectory || file.canExecute) oct"0755"
          else oct"0644"
      } yield FileMapping(file, name.toString(), Some(perm))
    archive(mappings, outputZip)
  }

  /** Creates a zip file attempting to give files the appropriate unix permissions using Java 7 APIs.
    *
    * @param sources
    *   The files to include in the zip file.
    * @param outputZip
    *   The location of the output file.
    */
  /** def zipNIO(sources: Iterable[(File, String)], outputZip: File): Unit = { require(!outputZip.isDirectory,
    * "Specified output file " + outputZip + " is a directory.") val mappings = sources.toSeq.map { case (file, name) =>
    * FileMapping(file, name) }
    *
    * // make sure everything is available val outputDir = outputZip.getParentFile IO createDirectory outputDir
    *
    * // zipping the sources into the output zip withZipFilesystem(outputZip) { system => mappings foreach { case
    * FileMapping(dir, name, _) if dir.isDirectory => Files createDirectories (system getPath name) case
    * FileMapping(file, name, _) => val dest = system getPath name // create parent directories if available
    * Option(dest.getParent) foreach (Files createDirectories _) Files copy (file.toPath, dest,
    * StandardCopyOption.COPY_ATTRIBUTES) } } }
    */

  private def archive(sources: Seq[FileMapping], outputFile: os.Path): Unit =
    if (os.isDir(outputFile))
      sys.error("Specified output file " + outputFile + " is a directory.")
    else {
      val outputDir = outputFile / os.up
      os.makeDir.all(outputDir)
      withZipOutput(outputFile.toIO) { output =>
        for (FileMapping(file, name, mode) <- sources) {
          val entry = new ZipArchiveEntry(file, normalizePath(name))
          sys.env.get("SOURCE_DATE_EPOCH") foreach { epoch =>
            entry.setTime(epoch.toLong * 1000)
          }
          // Now check to see if we have permissions for this sucker.
          mode foreach (entry.setUnixMode)
          output `putArchiveEntry` entry

          try
            if (file.isFile) {
              val fis = new java.io.FileInputStream(file)
              try
                // TODO - Write file into output?
                IOUtils.copy(fis, output)
              finally fis.close()
            }
          finally output.closeArchiveEntry()
        }
      }
    }

  /** using apache commons compress
    */
  private def withZipOutput(file: File)(f: ZipArchiveOutputStream => Unit): Unit = {
    val zipOut = new ZipArchiveOutputStream(file)
    zipOut `setLevel` Deflater.BEST_COMPRESSION
    try f(zipOut)
    catch {
      case t: Throwable =>
        IOUtils.closeQuietly(zipOut)
        throw t
    } finally zipOut.close()
  }

  /** Replaces windows backslash file separator with a forward slash, this ensures the zip file entry is correct for any
    * system it is extracted on.
    * @param path
    *   The path of the file in the zip file
    */
  private def normalizePath(path: String) = {
    val sep = java.io.File.separatorChar
    if (sep == '/')
      path
    else
      path.replace(sep, '/')
  }

  /** Opens a zip filesystem and creates the file if necessary.
    *
    * Note: This will override an existing zipFile if existent!
    *
    * @param zipFile
    * @param f:
    *   FileSystem => Unit, logic working in the filesystem
    * @see
    *   http://stackoverflow.com/questions/9873845/java-7-zip-file-system-provider-doesnt-seem-to-accept-spaces-in-uri
    */
  def withZipFilesystem(zipFile: File, overwrite: Boolean = true)(f: FileSystem => Unit): Unit = {
    if (overwrite) Files `deleteIfExists` zipFile.toPath
    val env = Map("create" -> "true").asJava
    val uri = new URI("jar", zipFile.toPath.toUri().toString(), null)

    val system = FileSystems.newFileSystem(uri, env)
    try f(system)
    finally system.close()
  }

}
