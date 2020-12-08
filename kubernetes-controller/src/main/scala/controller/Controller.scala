package controller

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.google.gson.reflect.TypeToken
import io.kubernetes.client.Configuration
import io.kubernetes.client.apis.CoreV1Api
import io.kubernetes.client.models.V1Pod
import io.kubernetes.client.util.{Config, Watch}
import sun.misc.Signal

import scala.jdk.CollectionConverters._

object Controller {

  def main(args: Array[String]): Unit = {
    println("Controlling...")

    for ((k, v) <- sys.env)
      println(s"$k: $v")

    // invoke the shutdown hook on SIGINT and SIGTERM
    for (sig <- List("INT", "TERM"))
      Signal.handle(new Signal(sig), _ => System.exit(0))

    val client = Config.defaultClient()
    client.getHttpClient.setReadTimeout(0, TimeUnit.SECONDS)
    Configuration.setDefaultApiClient(client)

    val api = new CoreV1Api()

    val watch: Watch[V1Pod] =
      Watch.createWatch[V1Pod](
        client,
        api.listPodForAllNamespacesCall(null, null, true, null, 5, null, null, null, true, null, null),
        new TypeToken[Watch.Response[V1Pod]] {}.getType)

    val running = new AtomicBoolean(true)
    val stopped = new CountDownLatch(1)
    sys.addShutdownHook {
      println("Shutting down...")
      running.set(false)
      watch.close()
      stopped.await(5, TimeUnit.SECONDS)
      println("Shut down.")
    }

    try {
      watch.iterator().asScala.foreach { item =>
        val pod = item.`object`

        println
        println(s"${item.`type`}: ${pod.getMetadata.getName} (${pod.getKind} in ${pod.getMetadata.getNamespace})")
        for {
          status <- Option(pod.getStatus)
          condition <- Option(status.getConditions).toSeq.flatMap(_.asScala).sortBy(_.getLastTransitionTime).takeRight(1)
        } {
          println(condition.getType)
        }
        println

        Option(pod.getMetadata.getOwnerReferences).toSeq.flatMap(_.asScala).filter(_.isController).foreach(ctrl =>
          println(s" - controlled by ${ctrl.getName} (${ctrl.getKind})"))

        def trace(name: String, values: Option[Map[String, String]]): Unit = {
          values.foreach { kv =>
            println(s" - $name")
            for ((k, v) <- kv)
              println(s"   - $k: $v")
          }
        }

        trace("Annotations", Option(pod.getMetadata.getAnnotations).map(_.asScala.toMap))
        trace("Labels", Option(pod.getMetadata.getLabels).map(_.asScala.toMap))
      }
    } catch {
      case e if !running.get() =>
        println(s"Watch has been interrupted ${Option(e.getCause).fold("")("by " + _)} during shutdown.")
    } finally {
      stopped.countDown()
      println("Controlling stopped.")
    }
  }
}
