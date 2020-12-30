package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}

import scala.collection.immutable.TreeSet

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

  final case class RegisterAsOnline(userId: Long) extends Command

  final case class RegisterAsOffline(userId: Long) extends Command

  final case class GetOnlineUserCount(replyTo: ActorRef[OnlineUserCount]) extends Command

  final case class BroadcastMessageToOnlineUsers(message: String) extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  final case class OnlineUserCount(count: Int) extends Reply


  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting OnlineUsers actor")

    def updated(users: Set[Long], userCount: Int): Behavior[Command] =
      Behaviors.receiveMessage[Command] {
        case RegisterAsOnline(userId) =>
          context.log.info("get RegisterAsOnline msg, userId: {}", userId)
          updated(users + userId, userCount + 1)
        case RegisterAsOffline(userId) =>
          context.log.info("get RegisterAsOffline msg, userId: {}", userId)
          updated(users - userId, userCount - 1)
        case GetOnlineUserCount(replyTo) =>
          replyTo ! OnlineUserCount(userCount)
          Behaviors.same
        case BroadcastMessageToOnlineUsers(message) =>
          // TODO: 待实现websocket发消息
          Behaviors.same
      }

    updated(TreeSet(), 0)
  }

  def initSingleton(system: ActorSystem[_]): ActorRef[Command] = {
    val singletonManager = ClusterSingleton(system)
    singletonManager.init {
      SingletonActor(Behaviors.supervise(OnlineUsersBehavior()).onFailure[Exception](SupervisorStrategy.restart), "onlineUsersActor")
    }
  }

}
