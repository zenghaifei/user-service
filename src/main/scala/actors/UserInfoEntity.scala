package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.pattern.StatusReply
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

  final case class UserInfo(userId: Long, username: String, phoneNumber: String, email: String, loginPassword: String,
                            var nickname: String, var gender: String, var address: String, var icon: String, var introduction: String)

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class Init(userInfo: UserInfo, replyTo: ActorRef[StatusReply[Unit]]) extends Command

  final case class GetUserInfo(replyTo: ActorRef[StatusReply[UserInfo]]) extends Command

  final case class ModifyUserInfo(nickname: String, gender: String, address: String, icon: String, introduction: String, replyTo: ActorRef[ModifyUserInfoResult]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  sealed trait ModifyUserInfoResult extends Reply

  final case object ModifyUserInfoSuccess extends ModifyUserInfoResult

  final case object ModifyUserInfoFailed extends ModifyUserInfoResult

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class Inited(userInfo: UserInfo) extends Event

  final case class UserInfoModified(nickname: String, gender: String, address: String, icon: String, introduction: String) extends Event

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
            Effect.none.thenReply(replyTo)(_ => StatusReply.Error("userId not match"))
          }
          else {
            val inited = Inited(userInfo)
            Effect.persist(inited).thenReply(replyTo)(_ => StatusReply.Success())
          }
        case GetUserInfo(replyTo) =>
          Effect.none.thenReply(replyTo)(_ => StatusReply.Error("no userInfo on [NotInitedState]"))
        case ModifyUserInfo(nickname, gender, address, icon, introduction, replyTo) =>
          Effect.none.thenReply(replyTo)(_ => ModifyUserInfoFailed)
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
          Effect.none.thenReply(replyTo)(_ => StatusReply.Success())
        case GetUserInfo(replyTo) =>
          Effect.none.thenReply(replyTo)(_ => StatusReply.Success(userInfo))
        case ModifyUserInfo(nickname, gender, address, icon, introduction, replyTo) =>
          Effect.persist(UserInfoModified(nickname, gender, address, icon, introduction)).thenReply(replyTo)(_ => ModifyUserInfoSuccess)
      }
    }

    override def applyEvent(event: Event): State = event match {
      case Inited(_) =>
        throw new IllegalArgumentException(s"Unexpected event [$event] on [InitedState]")
      case UserInfoModified(nickname, gender, address, icon, introduction) =>
        if (!nickname.isEmpty) {
          this.userInfo.nickname = nickname
        }
        if (!gender.isEmpty) {
          this.userInfo.gender = gender
        }
        if (!address.isEmpty) {
          this.userInfo.address = address
        }
        if (!icon.isEmpty) {
          this.userInfo.icon = icon
        }
        if (!introduction.isEmpty) {
          this.userInfo.introduction = introduction
        }
        this
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
