package embeds3data
import akka.NotUsed
import akka.stream.scaladsl.{Flow, Framing, Keep, Sink, Source}
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.util.ByteString

import scala.concurrent.Future

// Indicates a compatibility error of a downloaded version.
case class CompatibilityError(cause: Throwable) extends Exception(cause)

object StoreParser {

  val LineDelimiter   = "\n"
  val ColumnDelimiter = ","

  /**
    * Parses the given binary stream as CSV tuples into a map.
    * Parsing a stream will return the eventual map and a kill switch that allows to stop the parsing.
    *
    * All errors that are caused by the binary stream (includes the kill switch) are considered infrastructure problems
    * and passed directly.
    * Errors that occur within the parsing are considered compatibility errors and cause a `CompatibilityError`.
    */
  def parse(source: Source[ByteString, _])(implicit mat: Materializer): (KillSwitch, Future[Map[String, String]]) = {

    // Wraps an upstream error to distinguish it from a compatibility error.
    case class InfrastructureError(cause: Throwable) extends Exception

    val input: Source[ByteString, KillSwitch] =
      source
      // Allow a stop via kill switch.
        .viaMat(KillSwitches.single)(Keep.right)
        // Mark all problems from the source as infrastructure errors (in contrast to compatibility errors).
        .recoverWithRetries(1, { case cause => Source.failed(InfrastructureError(cause)) })

    val mapStream: Source[Map[String, String], KillSwitch] =
      input
        .via(mapReader)
        .recoverWithRetries(
          1, {
            // Every non-infrastructure related problem is now considered a compatibility error.
            // Actual infrastructure errors are unwrapped again.
            case InfrastructureError(cause) => Source.failed(cause)
            case cause                      => Source.failed(new CompatibilityError(cause))
          }
        )

    mapStream
      .toMat(Sink.last)(Keep.both) // keeps the kill switch
      .run()
  }

  val mapReader: Flow[ByteString, Map[String, String], NotUsed] =
    Flow[ByteString]
    // parse lines
      .via(Framing.delimiter(ByteString(LineDelimiter), maximumFrameLength = 1024, allowTruncation = true))
      .map(_.utf8String)
      // parse pairs
      .map { line =>
        line.split(ColumnDelimiter) match {
          case Array(key, value) => (key, value)
          case _                 => throw new IllegalArgumentException(s"Invalid line: $line")
        }

      }
      // fold to a map
      .fold(Map.empty[String, String])(_ + _)
}
