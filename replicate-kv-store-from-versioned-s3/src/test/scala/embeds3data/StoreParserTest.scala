package embeds3data
import akka.stream.scaladsl.Source
import akka.util.ByteString
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration.DurationLong
import scala.util.control.NoStackTrace

class StoreParserTest extends WordSpec with Matchers with AkkaTest {

  "StoreParser" should {

    val validByteStrings = List("a,1\nb,2\n", "c,", "3\n", "d,4\n").map(ByteString(_))

    "parse a valid stream" in {

      val source = Source(validByteStrings)

      val (_, eventualStore) = StoreParser.parse(source)

      eventualStore.await() should contain theSameElementsAs Map("a" -> "1", "b" -> "2", "c" -> "3", "d" -> "4")
    }

    "raise a compatibility error for an incompatible stream" in {

      val invalidByteStrings = List("valid,1\n", "invalid,1,2,3,4").map(ByteString(_))

      val source = Source(invalidByteStrings)

      val (_, eventualStore) = StoreParser.parse(source)

      eventualStore.failed.await() shouldBe a[CompatibilityError]
    }

    "pass infrastructure errors" in {

      val infrastructureError = new Exception("Network problem!") with NoStackTrace

      val source = Source(validByteStrings) ++ Source.failed(infrastructureError)

      val (_, eventualStore) = StoreParser.parse(source)

      eventualStore.failed.await() shouldBe infrastructureError
    }

    "stop at kill switch abortion" in {

      val source = Source(validByteStrings).delay(1.second)

      val (killSwitch, eventualStore) = StoreParser.parse(source)

      val shutdownSignal = new Exception("Shut down!") with NoStackTrace

      killSwitch.abort(shutdownSignal)

      eventualStore.failed.await() shouldBe shutdownSignal
    }
  }
}
