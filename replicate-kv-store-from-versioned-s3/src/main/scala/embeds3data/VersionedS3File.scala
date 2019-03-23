package embeds3data

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
  * Provides metadata/version look up and download of a specific version for the given versioned S3 bucket and key.
  */
class VersionedS3File(bucket: String, bucketKey: String) extends Logging {

  /**
    * Obtains the current object metadata from S3 and extracts the version id.
    */
  def obtainLatestVersion()(implicit mat: Materializer, ec: ExecutionContext): Future[Option[String]] =
    S3.getObjectMetadata(bucket, bucketKey)
      .runWith(Sink.last)
      .transform {
        case Success(Some(metadata)) =>
          // We need to check for version id if the bucket has no versioning enabled.
          metadata.versionId match {
            case Some(versionId) =>
              Success(Some(versionId))
            case _ =>
              Failure(new IllegalArgumentException(s"Versioning must be enabled on S3 bucket $bucket!"))
          }
        case Success(None)  => Success(None) // there is no latest version
        case Failure(cause) => Failure(cause) // obtaining failed
      }

  /**
    * Opens a stream to download the given version.
    */
  def downloadVersion(versionId: String)(implicit mat: Materializer): Source[ByteString, NotUsed] =
    S3.download(bucket, bucketKey, versionId = Some(versionId))
      .flatMapConcat {
        case Some((source, _)) =>
          source
        case None =>
          Source.failed(new NoSuchElementException(s"$bucketKey in version $versionId in $bucket does not exist!"))
      }
}
