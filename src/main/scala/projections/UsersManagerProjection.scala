package projections

import actors.UserInfoEntity.{InitFailed, InitSuccess}
import actors.UsersManagerPersistentBehavior.{UserInfo, UserRegistered}
import actors._
import akka.Done
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, ShardedDaemonProcess}
import akka.event.slf4j.SLF4JLogging
import akka.persistence.cassandra.query.scaladsl.CassandraReadJournal
import akka.projection.{HandlerRecoveryStrategy, ProjectionBehavior, ProjectionId}
import akka.projection.cassandra.scaladsl.CassandraProjection
import akka.projection.eventsourced.EventEnvelope
import akka.projection.eventsourced.scaladsl.EventSourcedProvider
import akka.projection.scaladsl.Handler
import akka.util.Timeout

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * projections
 *
 * @author colin
 * @version 1.0, 2021/1/13
 * @since 0.4.1
 */
object UsersManagerProjection {

  def init(system: ActorSystem[_]) = ShardedDaemonProcess(system).init[ProjectionBehavior.Command](
    name = "users-manager",
    numberOfInstances = 1,
    behaviorFactory = _ => ProjectionBehavior(build(system)),
    stopMessage = ProjectionBehavior.Stop
  )

  private def build(system: ActorSystem[_]) =
    CassandraProjection
      .atLeastOnce(
        projectionId = ProjectionId("users-manager", "p1"),
        EventSourcedProvider.eventsByTag[UsersManagerPersistentBehavior.Event](system, CassandraReadJournal.Identifier, UsersManagerPersistentBehavior.tag),
        handler = () => new UsersManagerHander(system)
      )
      .withSaveOffset(afterEnvelopes = 50, afterDuration = 1.seconds)
      .withRecoveryStrategy(HandlerRecoveryStrategy.retryAndFail(10, delay = 1.seconds))
      .withRestartBackoff(minBackoff = 200.millis, maxBackoff = 20.seconds, randomFactor = 0.1)
}

class UsersManagerHander(system: ActorSystem[_]) extends Handler[EventEnvelope[UsersManagerPersistentBehavior.Event]] with SLF4JLogging {
  implicit val timeout: Timeout = 3.seconds
  implicit val ec = system.executionContext

  val sharding = ClusterSharding(system)

  override def process(envelope: EventEnvelope[UsersManagerPersistentBehavior.Event]): Future[Done] = {
    envelope.event match {
      case UserRegistered(UserInfo(userId, username, password, phoneNumber, email, gender, address, icon, introduction)) =>
        log.debug("userRegistered , userId: {}", userId)
        val userInfoEntity = UserInfoEntity.selectEntity(userId, sharding)
        val userInfo1 = UserInfoEntity.UserInfo(userId, username, phoneNumber, email, password, gender, address, icon, introduction)
        userInfoEntity.ask(ref => UserInfoEntity.Init(userInfo1, ref))
          .map {
            case InitFailed(msg) =>
              log.warn("initfailed, userId: {}, msg: {}", userId, msg)
              Done
            case InitSuccess =>
              log.debug("initSuccess")
              Done
          }
    }
  }
}
