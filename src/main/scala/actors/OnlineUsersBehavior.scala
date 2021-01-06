package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}

import scala.collection.mutable

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2020/12/29
 * @since 0.4.1
 */
object OnlineUsersBehavior {

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class RegisterAsOffline(userId: Long) extends Command

  final case class GetOnlineUserCount(replyTo: ActorRef[OnlineUserCount]) extends Command

  final case class ApplyForTokenGeneration(userId: Long, replyTo: ActorRef[TokenGenerationApplyResult]) extends Command

  final case class BroadcastMessageToOnlineUsers(message: String) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  final case class OnlineUserCount(count: Int) extends Reply

  sealed trait TokenGenerationApplyResult extends Reply

  final case object TokenGenerationAllowed extends TokenGenerationApplyResult

  final case object TokenGenerationRejected extends TokenGenerationApplyResult

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting OnlineUsers actor")
    val config = context.system.settings.config
    val maxOnlineLimit = config.getInt("users.max-online-limit")

    def updated(users: mutable.TreeSet[Long], userCount: Int): Behavior[Command] =
      Behaviors.receiveMessage[Command] {
        case RegisterAsOffline(userId) =>
          context.log.info("get RegisterAsOffline msg, userId: {}", userId)
          updated(users - userId, userCount - 1)
        case ApplyForTokenGeneration(userId, replyTo) =>
          if (users.contains(userId)) {
            replyTo ! TokenGenerationAllowed
            Behaviors.same
          }
          else if (userCount < maxOnlineLimit) {
            replyTo ! TokenGenerationAllowed
            updated(users.addOne(userId), userCount + 1)
          }
          else {
            replyTo ! TokenGenerationRejected
            Behaviors.same
          }
        case GetOnlineUserCount(replyTo) =>
          replyTo ! OnlineUserCount(userCount)
          Behaviors.same
        case BroadcastMessageToOnlineUsers(message) =>
          // TODO: 待实现websocket发消息
          Behaviors.same
      }

    updated(mutable.TreeSet(), 0)
  }

  def initSingleton(system: ActorSystem[_]): ActorRef[Command] = {
    val singletonManager = ClusterSingleton(system)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(OnlineUsersBehavior()).onFailure[Exception](SupervisorStrategy.restart), "onlineUsersActor")
    }
  }

}
