package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}

import scala.collection.mutable
import scala.concurrent.duration.DurationInt

/**
 * 维护所有用户的用户名、手机号、邮箱号、微信号等与userId的映射
 *
 * @author colin
 * @version 1.0, 2021/1/5
 * @since 0.4.1
 */
object UsersManagerPersistentBehavior {

  // command
  sealed trait Command extends JacksonCborSerializable

  sealed trait GetUserId extends Command

  final case class GetUserIdByUsername(username: String, replyTo: ActorRef[GetUserIdResult]) extends GetUserId

  final case class GetUserIdByPhoneNumber(phoneNumber: String, replyTo: ActorRef[GetUserIdResult]) extends GetUserId

  final case class GetUserIdByEmail(email: String, replyTo: ActorRef[GetUserIdResult]) extends GetUserId

  final case class UserInfo(var userId: Long = 0L, username: String, password: String, phoneNumber: String, email: String,
                            gender: String, address: String, icon: String, introduction: String)

  final case class RegisterUser(userInfo: UserInfo, replyTo: ActorRef[RegisterResult]) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  sealed trait GetUserIdResult extends Reply

  final case class GetUserIdSuccess(userId: Long) extends GetUserIdResult

  final case object UserNotFound extends GetUserIdResult


  sealed trait RegisterResult extends Reply

  final case object RegisterSuccess extends RegisterResult

  final case object UsernameExist extends RegisterResult

  final case object PhoneNumberExist extends RegisterResult

  final case object EmailExist extends RegisterResult

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class UserRegistered(userInfo: UserInfo) extends Event

  // state
  final case class State(usernameIdMapping: mutable.TreeMap[String, Long] = mutable.TreeMap(),
                         phoneNumberIdMapping: mutable.TreeMap[String, Long] = mutable.TreeMap(),
                         emailIdMapping: mutable.TreeMap[String, Long] = mutable.TreeMap(),
                         var lastUserId: Long = 0) extends JacksonCborSerializable {

    def handleCommand(command: Command): Effect[Event, State] = {
      command match {
        case msg: GetUserId =>
          val (userIdOpt, replyTo) = msg match {
            case GetUserIdByUsername(username, replyTo) =>
              (this.usernameIdMapping.get(username), replyTo)
            case GetUserIdByPhoneNumber(phoneNumber, replyTo) =>
              (this.phoneNumberIdMapping.get(phoneNumber), replyTo)
            case GetUserIdByEmail(email, replyTo) =>
              (this.emailIdMapping.get(email), replyTo)
          }
          userIdOpt match {
            case Some(userId) =>
              replyTo ! GetUserIdSuccess(userId)
            case None =>
              replyTo ! UserNotFound
          }
          Effect.none
        case RegisterUser(userInfo, replyTo) =>
          val usernameExist = this.usernameIdMapping.get(userInfo.username).map(_ => true).getOrElse(false)
          if (usernameExist) {
            replyTo ! UsernameExist
            return Effect.none
          }
          val phoneNumberExist = this.phoneNumberIdMapping.get(userInfo.phoneNumber).map(_ => true).getOrElse(false)
          if (phoneNumberExist) {
            replyTo ! PhoneNumberExist
            return Effect.none
          }
          val emailExist = this.emailIdMapping.get(userInfo.email).map(_ => true).getOrElse(false)
          if (emailExist) {
            replyTo ! EmailExist
            return Effect.none
          }
          userInfo.userId = this.lastUserId + 1
          val userRegistered = UserRegistered(userInfo)
          Effect.persist(userRegistered).thenReply(replyTo)(_ => RegisterSuccess)
      }
    }

    def handleEvent(event: Event): State = {
      event match {
        case UserRegistered(userInfo) =>
          val username = userInfo.username
          if (!username.isEmpty) {
            this.usernameIdMapping.addOne(username -> userInfo.userId)
          }
          val phoneNumber = userInfo.phoneNumber
          if (!phoneNumber.isEmpty) {
            this.phoneNumberIdMapping.addOne(phoneNumber -> userInfo.userId)
          }
          val email = userInfo.email
          if (!email.isEmpty) {
            this.emailIdMapping.addOne(email -> userInfo.userId)
          }
          this.lastUserId = userInfo.userId
          this
      }
    }
  }

  def persistenceId() = PersistenceId.ofUniqueId("users-manager")

  def tag = "users-manager"

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting users-manager persistent actor")
    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId(),
      emptyState = State(),
      commandHandler = (state, command) => state.handleCommand(command),
      eventHandler = (state, event) => state.handleEvent(event)
    )
      .withTagger(_ => Set(tag))
      .withRetention(RetentionCriteria.snapshotEvery(500, 2))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

}
