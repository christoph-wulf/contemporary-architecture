package embeds3data
import akka.Done
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior}
import akka.stream.scaladsl.{Keep, Source}
import akka.stream.{KillSwitch, KillSwitches, Materializer}
import akka.util.ByteString
import org.apache.logging.log4j.scala.Logging

import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.util.{Failure, Success, Try}

// h1. Make versioned data in S3 available in a service

// In diesem Beispiel kombinieren wir
//  * [S3 Bucket] mit [automatischer Versionierung] und automatischem [Lifecyle-Management] alter Versionen,
//  * [Alpakka S3] Konnektor auf Basis von [Akka Streams], um S3 Objekte zu laden und
//  * [Typed Actor] für State Management, um
// versionierte Daten in S3 robust in einem Service verfügbar zu machen.

// Problem:
//   Output eines Batch-Jobs (zeitlich oder bei Code/Daten-Änderungen) in Services verfügbar machen.
//   Output ist klein genug für Memory in diesem Beispiel.
//   Das muss aber nicht, vgl. _Write a read-only versioned key value store Spark output to S3_.
//   Muss auch kein Batch-Output sein, können auch Business Parameter sein.

// Lösungsidee:
//   S3 Bucket mit Versionierung und Lifecycle, der alte Versionen nach 7 Tagen löscht
//   Client kann Datei herunterladen, einlesen und bei Erfolg austauschen
//   Periodisch wird auf Aktualisierungen geprüft und bei Kompatibilität heruntergeladen und ausgetauscht

// Vorteile:
//   Niedrige Latenz
//   Hohe Verfügbarkeit auch bei Network Partitions
//   Hohe Resilienz gegenüber korrupten Daten
//   Hohe Nachverfolgbarkeit der Datenherkunft (welcher Datenstand wird benutzt)

// Implementierung:
//   CFN für S3 bucket mit versioning und lifecycle
//   S3 Metadaten nachschlagen, geänderte Datei herunterladen und einlesen
//   Typed Actor
//     Initialisiert über: In temporäre Datei herunterladen, validieren, ggf. vorherige Versionen ausprobieren
//     Periodisch aktualisiert: In temporäre Datei herunterladen (wenn geändert), validieren, bei Erfolg austauschen sonst warnen und in Fehlermodus
//     Periodisch aktualisieren: In temporäre Datei herunterladen (wenn zu fehlerhafter Datei geändert) ansonsten warnen, validieren, bei Erfolg austauschen

// Tests gegen Localstack

// Inkrement:
//   Kompatibilität über AVRO Schemata sicherstellen, a) Schema Registry (Metadaten, Schema-Id) b) AVRO Schema einbetten (Metadaten, Länge des Schemas) (ggf.)
// Inkrement: Austauschen über Benachrichtigungen (SNS über Webhooks, SQS pro Instanz, SQS pro Service mit Akka Cluster)
// - Preis 1 Minute    (pro Instanz, Datei und Tag)
// - Preis 10 Sekunden (pro Instanz, Datei und Tag)

// 1. Lokale Infrastruktur über Shell-Skript, S3 Download einer Version, Metadaten Lookup, Download wenn geändert + Unit Tests
// 2. Interface für die Datei (als CSV parsen und HashMap erzeugen)
// 3. Typed Actor, der herunterlädt und periodisch auf Änderungen prüft (Protokoll: Ready, Get, Stop, +Check)
// 4. Typed Actor um Fehlerfälle erweitern (a) Fehlerhafte Version b) Netzwerk u.a.), (Protokoll Ready -> State)
// 5. Typed Actor um robuste Initialisierung erweitern
// 6. CFN-Template statt Shell-Skript
// 7. Dokumentation
// 8. Inkrements

// Referenzen zu Versioning:
//   https://docs.aws.amazon.com/de_de/AmazonS3/latest/dev/ObjectVersioning.html
//   https://docs.aws.amazon.com/de_de/AmazonS3/latest/dev/lifecycle-configuration-examples.html#lifecycle-config-conceptual-ex6
//   https://docs.aws.amazon.com/AmazonS3/latest/dev/RestoringPreviousVersions.html

object EmbeddedStoreActor extends Logging {

  val CheckInterval: FiniteDuration  = 20.seconds
  val InitialBackOff: FiniteDuration = 500.millis
  val MaxLoadAttempts                = 3

  type Store = Map[String, String]

  // The protocol contains the messages that can be sent to the actor from the outside.
  sealed trait Protocol extends Message

  // Asks for the current data.
  case class Get(replyTo: ActorRef[Option[Store]]) extends Protocol

  // Asks for a graceful shutdown.
  case class Shutdown(replyTo: ActorRef[Done]) extends Protocol

  /**
    * Creates the actor to replicate the latest compatible version of `bucketKey` in S3 `bucket`.
    */
  def create(file: VersionedS3File)(implicit mat: Materializer): Behavior[Protocol] =
    Behaviors
      .setup[Message] { ctx =>
        ctx.self ! Check(attempt = 1) // Send the first trigger to check for the latest version
        initial(file)                 // Start in initial behavior
      }
      .narrow[Protocol] // Narrow the actor's interface to the protocol

  // The messages contain all signals the actor reacts on (the following are not part of the protocol).
  sealed trait Message

  // Triggers a new check if the file has been updated.
  case class Check(attempt: Int) extends Message

  // Triggers a load of the given version.
  case class Load(versionId: String, attempt: Int) extends Message

  // Handles the outcome of a download for a version.
  case class Loaded(versionId: String, attempt: Int, result: Try[Store]) extends Message

  def behavior(file: VersionedS3File,
               data: Option[Store],
               versionId: Option[String],
               incompatibleVersionIds: Set[String],
               downloading: Option[KillSwitch])(implicit mat: Materializer): Behavior[Message] =
    Behaviors.receive {

      // Respond query with the current data and stay in same state.
      case (_, Get(replyTo)) =>
        replyTo ! data
        Behaviors.same

      // React to shutdown by shutting down an active download and stopping the whole actor.
      case (_, Shutdown(replyTo)) =>
        downloading.foreach(_.shutdown()) // stop download if there is one
        replyTo ! Done
        Behaviors.stopped

      // React to check trigger by asynchronously obtaining and comparing the object metadata.
      case (ctx, Check(attempt)) =>
        check(file, ctx, versionId, incompatibleVersionIds, attempt)
        Behaviors.same

      // React to load trigger by asynchronously loading that version.
      case (ctx, Load(version, attempt)) =>
        load(file, ctx, version, attempt)
        Behaviors.same

      // Reacts to a successful update by switching to the new version and scheduling the next check.
      case (ctx, Loaded(version, _, Success(newData))) =>
        ctx.scheduleOnce(CheckInterval, ctx.self, Check(attempt = 1))
        behavior(file, Some(newData), Some(version), incompatibleVersionIds, None)

      // Remember an incompatible version.
      case (ctx, Loaded(version, _, Failure(CompatibilityError(cause)))) =>
        logger.error(s"Version $version is incompatible. Checking for new version in $CheckInterval.", cause)
        ctx.scheduleOnce(CheckInterval, ctx.self, Check(attempt = 1))
        behavior(file, data, versionId, incompatibleVersionIds + version, None)

      // Retries in case of other (infrastructure) problems with back-off for some attempts.
      case (ctx, Loaded(version, attempt, Failure(cause))) =>
        if (attempt <= MaxLoadAttempts) {
          val backOff = backOffFor(attempt)
          logger.warn(s"Could not load version $version in attempt $attempt, retrying in $backOff.", cause)
          ctx.scheduleOnce(backOff, ctx.self, Load(version, attempt + 1))
        } else {
          logger.error(s"Could not load version $version in $MaxLoadAttempts attempts. Scheduling a new check after $CheckInterval.", cause)
          ctx.scheduleOnce(CheckInterval, ctx.self, Check(attempt = 1))
        }
        behavior(file, data, versionId, incompatibleVersionIds, None)
    }

  /**
    * The initial behavior has no data, knows no versions yet and is not downloading anything.
    */
  def initial(file: VersionedS3File)(implicit mat: Materializer): Behavior[Message] =
    behavior(file, None, None, Set.empty, None)

  /**
    * Checks given key in bucket for its latest version.
    * If the version is different from the current one, sends a corresponding load trigger to the actor.
    * If not or no version at all exists schedules the next check.
    */
  def check(file: VersionedS3File, ctx: ActorContext[Message], currentVersionId: Option[String], incompatibleVersionIds: Set[String], attempt: Int)(
      implicit mat: Materializer): Unit = {
    import ctx.executionContext

    def reschedule(): Unit =
      ctx.scheduleOnce(CheckInterval, ctx.self, Check(attempt = 1))

    file.obtainLatestVersion().onComplete {

      case Success(Some(versionId)) if currentVersionId.contains(versionId) =>
        logger.debug(s"Current version $versionId is still up to date, checking again in $CheckInterval.")
        reschedule()

      case Success(Some(versionId)) if incompatibleVersionIds.contains(versionId) =>
        logger.error(s"Latest version $versionId is still incompatible, checking again in $CheckInterval.")
        reschedule()

      case Success(Some(versionId)) =>
        logger.info(s"Initiating download for version $versionId.")
        ctx.self ! Load(versionId, attempt = 1)

      case Success(None) =>
        logger.debug(s"No file is available, scheduling new check in $CheckInterval.")
        reschedule()

      // Something failed in the infrastructure, retry with exponential back off (not exceeding the check interval).
      case Failure(cause) =>
        val backOff = backOffFor(attempt)
        logger.warn(s"Could not check object meta data in S3 in attempt $attempt, retrying in $backOff.", cause)
        ctx.scheduleOnce(backOff, ctx.self, Check(attempt + 1))
    }
  }

  /**
    * Downloads the given version of given bucket key asynchronously and tries to parse it.
    * Propagates the result to the actor.
    * Returns a kill switch to interrupt the download in case of a shutdown.
    */
  def load(file: VersionedS3File, ctx: ActorContext[Message], versionId: String, attempt: Int)(implicit mat: Materializer): KillSwitch = {
    import ctx.executionContext

    // Download the version file but allow stop via kill switch.
    // Consider all problems from this source infrastructure errors (in contrast to compatibility errors).
    val stoppableDownload: Source[ByteString, KillSwitch] =
      file
        .downloadVersion(versionId)
        .viaMat(KillSwitches.single)(Keep.right)

    // Parse the store allowing a stop by kill switch.
    val (killSwitch, eventualData) = StoreParser.parse(stoppableDownload)

    // Handle the result asynchronously ...
    eventualData.onComplete { result =>
      ctx.self ! Loaded(versionId, attempt, result)
    }

    // ... and return the kill switch to the actor.
    killSwitch
  }

  /**
    * Exponential back off (that does not exceed the check interval) after given attempt.
    */
  def backOffFor(attempt: Int): FiniteDuration = {
    val exponentialBackOff = scala.math.pow(2, attempt - 1).toLong * InitialBackOff
    exponentialBackOff min CheckInterval
  }
}
