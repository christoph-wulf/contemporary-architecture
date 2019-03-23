package embeds3data
import akka.Done
import akka.actor.{ActorSystem => UntypedSystem}
import akka.actor.Scheduler
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import org.apache.logging.log4j.scala.Logging
import akka.actor.typed.scaladsl.adapter.UntypedActorSystemOps
import akka.actor.typed.scaladsl.AskPattern.Askable

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.{Duration, DurationLong}
import scala.util.{Failure, Success}

object EmbeddedStoreActorTestApp extends Logging {

  def main(args: Array[String]): Unit = {

    implicit val untypedSystem: UntypedSystem = UntypedSystem()
    implicit val ec: ExecutionContext         = untypedSystem.dispatcher
    implicit val materializer: Materializer   = ActorMaterializer()
    implicit val scheduler: Scheduler         = untypedSystem.scheduler
    implicit val timeout: Timeout             = 5.seconds

    val s3File = new VersionedS3File("important-data-bucket", "important-data")

    val embeddedStoreActor = untypedSystem.spawn(EmbeddedStoreActor.create(s3File), "embedded-store-actor")

    scheduler.schedule(100.millis, 5.seconds) {
      (embeddedStoreActor ? EmbeddedStoreActor.Get).onComplete {
        case Success(Some(data)) => logger.info(s"${data.take(10)}...")
        case Success(None)       => logger.info("No data!")
        case Failure(cause)      => logger.error("Look up failed!", cause)
      }
    }

    sys.addShutdownHook {
      val shutdown =
        for {
          _ <- embeddedStoreActor ? EmbeddedStoreActor.Shutdown
          _ <- untypedSystem.terminate()
        } yield {
          Done
        }
      Await.result(shutdown, Duration.Inf)
    }
  }
}
