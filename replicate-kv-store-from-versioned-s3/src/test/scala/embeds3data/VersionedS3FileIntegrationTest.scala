package embeds3data
import java.util.UUID

import akka.stream.scaladsl.Sink
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.amazonaws.services.s3.model.{BucketVersioningConfiguration, SetBucketVersioningConfigurationRequest}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3Client}
import org.scalatest.{Matchers, OptionValues, WordSpec}

/**
  * The interface to a versioned file in S3 strongly depends on the behavior of S3.
  * Therefore the integration with S3 (mocked by localstack) is tested.
  */
class VersionedS3FileIntegrationTest extends WordSpec with Matchers with AkkaTest with OptionValues {

  val amazonS3Client: AmazonS3 = AmazonS3Client
    .builder()
    .withPathStyleAccessEnabled(true)
    .withEndpointConfiguration(new EndpointConfiguration("http://127.0.0.1:4572", "eu-central-1"))
    .build()

  "VersionedS3File" when {

    val bucketKey = "bucket-key"

    "obtaining latest version" should {

      "return the latest version" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)

        val putObjectResult = amazonS3Client.putObject(bucketName, bucketKey, "some-content")

        versionedS3File.obtainLatestVersion().await().value shouldBe putObjectResult.getVersionId
      }

      "return none for a non-existing file" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)

        versionedS3File.obtainLatestVersion().await() shouldBe None
      }

      "return the new version after an update" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)

        val putObjectResult1 = amazonS3Client.putObject(bucketName, bucketKey, "some-content-1")

        assume(versionedS3File.obtainLatestVersion().await().value == putObjectResult1.getVersionId)

        val putObjectResult2 = amazonS3Client.putObject(bucketName, bucketKey, "some-content-2")

        versionedS3File.obtainLatestVersion().await().value shouldBe putObjectResult2.getVersionId
      }

      "return the old version after deletion of the current version" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)

        val putObjectResult1 = amazonS3Client.putObject(bucketName, bucketKey, "some-content-1")
        val putObjectResult2 = amazonS3Client.putObject(bucketName, bucketKey, "some-content-2")

        assume(versionedS3File.obtainLatestVersion().await().value == putObjectResult2.getVersionId)

        amazonS3Client.deleteVersion(bucketName, bucketKey, putObjectResult2.getVersionId)

        versionedS3File.obtainLatestVersion().await().value shouldBe putObjectResult1.getVersionId
      }

      "fail if versioning is disabled" in {

        val bucketName      = createEmptyBucket(withVersioning = false)
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)

        amazonS3Client.putObject(bucketName, bucketKey, "some-content")

        versionedS3File.obtainLatestVersion().failed.await() shouldBe a[Throwable]
      }
    }

    "downloading version" should {

      "download a current version" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)
        val content         = "some-content"
        val putObjectResult = amazonS3Client.putObject(bucketName, bucketKey, content)

        versionedS3File.downloadVersion(putObjectResult.getVersionId).reduce(_ ++ _).map(_.utf8String).runWith(Sink.last).await() shouldBe content
      }

      "download an older version" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)
        val content         = "some-content"
        val putObjectResult = amazonS3Client.putObject(bucketName, bucketKey, content)

        amazonS3Client.putObject(bucketName, bucketKey, s"other-$content")

        versionedS3File.downloadVersion(putObjectResult.getVersionId).reduce(_ ++ _).map(_.utf8String).runWith(Sink.last).await() shouldBe content
      }

      "fail if version does not exist" in {

        val bucketName      = createEmptyBucket()
        val versionedS3File = new VersionedS3File(bucketName, bucketKey)
        amazonS3Client.putObject(bucketName, bucketKey, "some-content")

        val content2        = "some-other-content"
        val putObjectResult = amazonS3Client.putObject(bucketName, bucketKey, content2)

        amazonS3Client.deleteVersion(bucketName, bucketKey, putObjectResult.getVersionId)

        versionedS3File.downloadVersion(putObjectResult.getVersionId).runWith(Sink.last).failed.await() shouldBe a[Throwable]
      }
    }
  }

  /**
    * Creates a random empty bucket with or without versioning and returns the bucket name.
    */
  def createEmptyBucket(withVersioning: Boolean = true): String = {

    val bucketName = s"bucket-${UUID.randomUUID()}"

    amazonS3Client.createBucket(bucketName)

    if (withVersioning)
      amazonS3Client.setBucketVersioningConfiguration(
        new SetBucketVersioningConfigurationRequest(bucketName, new BucketVersioningConfiguration(BucketVersioningConfiguration.ENABLED)))

    bucketName
  }
}
