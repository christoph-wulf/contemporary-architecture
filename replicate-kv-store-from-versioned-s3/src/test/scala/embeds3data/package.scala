import scala.concurrent.{Await, Future}
import scala.concurrent.duration.{Duration, DurationLong}

package object embeds3data {

  implicit class AwaitableFuture[T](future: Future[T]) {

    def await(maxDuration: Duration = 5.seconds): T = Await.result(future, maxDuration)
  }
}
