package embeds3data
import java.nio.charset.StandardCharsets

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.alpakka.s3.scaladsl.S3
import akka.stream.alpakka.s3.{MultipartUploadResult, ObjectMetadata}
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.ByteString

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Random
import scala.collection.JavaConverters._

object S3UploadValidFile {

  val Bucket    = "important-data-bucket"
  val BucketKey = "important-data"

  def main(args: Array[String]): Unit = {

    implicit val actorSystem: ActorSystem   = ActorSystem()
    implicit val materializer: Materializer = ActorMaterializer()
    import actorSystem.dispatcher

    val run =
      for {
        multipartUploadResult <- upload(Bucket, BucketKey, randomFileContent())
        _ = println(s"Upload result is $multipartUploadResult")
        _ <- actorSystem.terminate()
      } yield {
        Unit
      }

    Await.result(run, Duration.Inf)
  }

  def upload(bucket: String, bucketKey: String, content: Source[ByteString, NotUsed])(implicit mat: Materializer): Future[MultipartUploadResult] = {

    val s3Sink: Sink[ByteString, Source[MultipartUploadResult, NotUsed]] =
      S3.multipartUpload(bucket, bucketKey)

    val result: Source[MultipartUploadResult, NotUsed] =
      content.runWith(s3Sink)

    result.runWith(Sink.last)
  }

  // Switch one of them to produce a corrupt CSV file
  val LineDelimiter: String   = StoreParser.LineDelimiter
  val ColumnDelimiter: String = StoreParser.ColumnDelimiter

  def randomFileContent(): Source[ByteString, NotUsed] =
    Source
      .fromIterator { () =>
        val random = new Random()
        def key    = random.alphanumeric.take(8).mkString
        def value  = random.alphanumeric.take(32).mkString

        Iterator.continually(s"$key$ColumnDelimiter$value")
      }
      .intersperse(LineDelimiter)
      .map(str => ByteString(str.getBytes(StandardCharsets.UTF_8)))
      .take(100)
}
