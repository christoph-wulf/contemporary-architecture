package embeds3data
import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import org.scalatest.{BeforeAndAfterEach, Suite}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext}

/**
  * Provides an implicit actor system, execution context and materializer for each test.
  * Creating and shutting down an actor system for each test takes some time but then log messages from still running
  * processes cannot overlap with the next test.
  */
trait AkkaTest extends BeforeAndAfterEach { self: Suite =>

  protected implicit def actorSystem: ActorSystem = _actorSystem
  protected implicit def ec: ExecutionContext     = actorSystem.dispatcher
  protected implicit def mat: Materializer        = _mat

  private var _actorSystem: ActorSystem = _
  private var _mat: Materializer        = _

  override def beforeEach(): Unit = {
    _actorSystem = ActorSystem()
    _mat = ActorMaterializer()
    super.beforeEach()
  }

  override def afterEach(): Unit = {
    Await.result(_actorSystem.terminate(), Duration.Inf)
    super.afterEach()
  }
}
