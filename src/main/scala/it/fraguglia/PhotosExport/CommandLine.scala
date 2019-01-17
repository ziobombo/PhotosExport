package it.fraguglia.PhotosExport

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

import org.apache.commons.io.FileUtils
import org.apache.commons.io.FilenameUtils
import org.apache.spark.sql.Row
import org.apache.spark.sql.SparkSession
import org.apache.spark.storage.StorageLevel

case class Version(fileName: String, masterId: Int, adjustmentUuid: String, isInTrash: Boolean, showInLibrary: Boolean)
case class Master(modelId: Int, isMissing: Boolean, isInTrash: Boolean)
case class ModelResource(modelId: Int, resourceTag: String, dir: String, suffix: String)
case class LibraryPhoto(fileName: String, path: String, backupFileName: String, backupPath: String, size: Long, isMaster: Boolean)

@Command(
  name = "photos-export.sh",
  mixinStandardHelpOptions = true, // add --help and --version options
  description = Array("Exports photos (raw and modified) from Apple Photos libraries to a backup folder"))
class MyApp extends Runnable with Serializable {

  @Option(
    names = Array("-l", "--library-path"),
    description = Array("Path of the input Apple Photo library"),
    required = true)
  private var libraryOption: String = "/library"

  @Option(
    names = Array("-b", "--backup-path"),
    description = Array("Path of the backup"),
    required = true)
  private var backupOption: String = "/backup"

  @Option(
    names = Array("-p", "--parallelism"),
    description = Array("Parallelism"))
  private var parallelismOption: Int = 2

  def run(): Unit = {

    @transient lazy val log = org.apache.log4j.LogManager.getLogger("appLogger")

    var library = libraryOption
    var backup = backupOption
    var parallelism = parallelismOption

    val spark = SparkSession
      .builder
      .appName("PhotosExport")
      .master("local[" + parallelism + "]")
      .getOrCreate()

    import spark.implicits._
    val version = spark
      .read
      .format("jdbc")
      .options(
        Map(
          "url" -> s"jdbc:sqlite:$library/database/photos.db",
          "dbtable" -> "(select fileName, masterId, adjustmentUuid, isInTrash, showInLibrary from rkVersion) as v",
          "driver" -> "org.sqlite.JDBC"))
      .load
      .repartition(parallelism)
      .map { row =>
        row match {
          case Row(fileName: String, masterId: Int, adjustmentUuid: String, isInTrash: Int, showInLibrary: Int) => Version(fileName, masterId, adjustmentUuid, isInTrash match {
            case 1 => true
            case _ => false
          }, showInLibrary match {
            case 1 => true
            case _ => false
          })
        }
      }
      .filter { version => !version.isInTrash && version.showInLibrary }
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val master = spark
      .read
      .format("jdbc")
      .options(
        Map(
          "url" -> s"jdbc:sqlite:$library/database/photos.db",
          "dbtable" -> "(select modelId, isMissing, isInTrash from rkMaster) as m"))
      .load
      .repartition(parallelism)
      .map { row =>
        row match {
          case Row(modelId: Int, isMissing: Int, isInTrash: Int) => Master(modelId, isMissing match {
            case 1 => true
            case _ => false
          }, isInTrash match {
            case 1 => true
            case _ => false
          })
        }
      }
      .filter { master => !master.isInTrash && !master.isMissing }
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val modelResource = spark
      .read
      .format("jdbc")
      .options(
        Map(
          "url" -> s"jdbc:sqlite:$library/database/photos.db",
          "dbtable" -> "(select modelId, resourceTag from rkModelResource) as mr"))
      .load
      .repartition(parallelism)
      .map { row =>
        row match {
          case Row(modelId: Int, resourceTag: String) => ModelResource(modelId, resourceTag, getResourceLocation(modelId)._2, getResourceLocation(modelId)._1)
        }
      }
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val allMasterFiles = spark.createDataset[String](getRecursiveListOfFiles(new File(library, "Masters")).toList.map(f => f.toString))
      .repartition(parallelism)
      .filter(new File(_).isFile())
      .map(x => (new File(x).getName(), x))
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val allEditedFiles = spark.createDataset[String](getRecursiveListOfFiles(Paths.get(library, "resources", "media", "version").toFile()).toList.map(f => f.toString))
      .repartition(parallelism)
      .filter(new File(_).isFile()).map(x => {
        val suffix = new File(x).getName().split('.')(0).split('_')(1)
        val dir = new File(x).getParentFile().getParentFile().getName()
        (suffix, dir, x)
      })
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    val allBackupFiles = spark.createDataset[String](getRecursiveListOfFiles(Paths.get(backup).toFile()).toList.map(f => f.toString))
      .repartition(parallelism)
      .filter(new File(_).isFile())
      .map(x => (new File(x).getName(), x, Files.size(Paths.get(x))))
      .persist(StorageLevel.MEMORY_AND_DISK_SER)

    //Master Photos
    val masterPhotos = master
      .joinWith(version.filter { v => v.adjustmentUuid == "UNADJUSTEDNONRAW" || v.adjustmentUuid == "UNADJUSTEDRAW" }, master("modelId") === version("masterId"), "inner")
    val masterLibraryPhotos = masterPhotos
      .joinWith(allMasterFiles, masterPhotos("_2.fileName") === allMasterFiles("_1"), "inner")
      .map {
        case (((master: Master, version: Version), (fileName: String, path: String))) => {
          val relativePath = path.replace(library, "");
          val backupFileName = relativePath.replace('/', '_');
          val backupPath = Paths.get(backup, backupFileName).toString()
          LibraryPhoto(fileName, path, backupFileName, backupPath, Files.size(Paths.get(path)), true)
        }
      }

    //Edited Photos
    val editedPhotos = master
      .joinWith(version.filter { v => v.adjustmentUuid != "UNADJUSTEDNONRAW" && v.adjustmentUuid != "UNADJUSTEDRAW" }, master("modelId") === version("masterId"), "inner")
    val editedPhotosWithResources = editedPhotos
      .joinWith(modelResource, modelResource("resourceTag") === editedPhotos("_2.adjustmentUuid"), "inner")
    val editedLibraryPhotos = editedPhotosWithResources
      .joinWith(allEditedFiles, (editedPhotosWithResources("_2.suffix") === allEditedFiles("_1")).and(editedPhotosWithResources("_2.dir") === allEditedFiles("_2")), "inner")
      .map {
        case ((((master: Master, version: Version), modelResource: ModelResource), (suffix, dir, path))) => {
          val relativePath = path.replace(library, "");
          val backupFileName = relativePath.replace('/', '_');
          val backupPath = Paths.get(backup, backupFileName).toString()
          LibraryPhoto(version.fileName, path, backupFileName, backupPath, Files.size(Paths.get(path)), false)
        }
      }

    val libraryPhotos = masterLibraryPhotos.union(editedLibraryPhotos)

    //copy images
    import org.apache.spark.sql.functions._
    libraryPhotos
      .joinWith(allBackupFiles, (libraryPhotos("backupFileName") === allBackupFiles("_1")).and(libraryPhotos("size") === allBackupFiles("_3")), "left")
      .filter(col("_2").isNull)
      .foreach(x => {
        log.info("Copying " + (x._1.isMaster match { case true => "master"; case false => "edited" }) + " file " + x._1.path + " to " + x._1.backupPath)
        FileUtils.copyFile(new File(x._1.path), new File(x._1.backupPath))
      })

    //delete non-existing photos
    allBackupFiles
      .joinWith(libraryPhotos, (libraryPhotos("backupFileName") === allBackupFiles("_1")).and(libraryPhotos("size") === allBackupFiles("_3")), "left")
      .filter(col("_2").isNull)
      .foreach(x => {
        log.info("Deleting file " + x._1._2)
        FileUtils.deleteQuietly(new File(x._1._2))
      })

    spark.close()

  }

  def getResourceLocation(modelId: Int): (String, String) = {
    val hex = Integer.toHexString(modelId);
    val suffix = hex;
    var dir = hex;
    if (dir.length() < 4) {
      val zerosNeeded = 4 - dir.length();

      val zeros = new String(new Array[Char](zerosNeeded)).replace("\0", "0");
      dir = zeros + dir;
    }
    dir = dir.substring(0, 2);
    return (suffix, dir);
  }

  def getRecursiveListOfFiles(dir: File): Array[File] = {
    val these = dir.listFiles
    these ++ these.filter(_.isDirectory).flatMap(getRecursiveListOfFiles)
  }
}