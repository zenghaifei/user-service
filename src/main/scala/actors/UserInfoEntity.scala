package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/7
 * @since 0.4.1
 */
object UserInfoEntity {

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class Init(userInfo: UserInfo, replyTo: ActorRef[InitResult]) extends Command

  final case class GetUserInfo(replyTo: ActorRef[GetUserInfoResult]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  sealed trait InitResult extends Reply

  final case object InitSuccess extends InitResult

  final case class InitFailed(msg: String) extends InitResult

  sealed trait GetUserInfoResult extends Reply

  final case class UserInfo(userId: Long, username: String, phoneNumber: String, email: String, loginPassword: String,
                            gender: String, address: String, icon: String, introduction: String) extends GetUserInfoResult

  final case class GetUserInfoFailed(msg: String) extends GetUserInfoResult

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class Inited(userInfo: UserInfo) extends Event

  // state
  sealed trait State extends JacksonCborSerializable {
    def applyCommand(command: Command, userId: Long): Effect[Event, State]

    def applyEvent(event: Event): State
  }

  final case object NotInitedState extends State {

    override def applyCommand(command: Command, userId: Long): Effect[Event, State] = {
      command match {
        case Init(userInfo, replyTo) =>
          if (userInfo.userId != userId) {
            Effect.none.thenReply(replyTo)(_ => InitFailed("userId not match"))
          }
          else {
            val inited = Inited(userInfo)
            Effect.persist(inited).thenReply(replyTo)(_ => InitSuccess)
          }
        case GetUserInfo(replyTo) =>
          Effect.none.thenReply(replyTo)(_ => GetUserInfoFailed("no userInfo on [NotInitedState]"))
      }
    }

    override def applyEvent(event: Event): State = event match {
      case Inited(userInfo) =>
        InitedState(userInfo)
    }
  }

  final case class InitedState(userInfo: UserInfo) extends State {

    override def applyCommand(command: Command, userId: Long): Effect[Event, State] = {
      command match {
        case Init(_, replyTo) =>
          Effect.none.thenReply(replyTo)(_ => InitFailed("can't init on [InitedState]"))
        case GetUserInfo(replyTo) =>
          Effect.none.thenReply(replyTo)(_ => userInfo)
      }
    }

    override def applyEvent(event: Event): State = event match {
      case Inited(_) =>
        throw new IllegalArgumentException(s"Unexpected event [$event] on [InitedState]")
    }
  }

  val TypeKey = EntityTypeKey[Command]("user-info")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        UserInfoEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(userId: Long, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, userId.toString())

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting userInfoActor, userId: {}", entityId)
    val userId = entityId.toLong

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = NotInitedState,
      commandHandler = (state, command) => state.applyCommand(command, userId),
      eventHandler = (state, event) => state.applyEvent(event)
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }
}
