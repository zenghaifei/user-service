package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, SupervisorStrategy}
import akka.cluster.typed.{ClusterSingleton, SingletonActor}
import akka.pattern.StatusReply

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

  final case class GetOnlineUserCount(replyTo: ActorRef[StatusReply[Int]]) extends Command

  final case class ApplyForTokenGeneration(userId: Long, replyTo: ActorRef[StatusReply[Unit]]) extends Command

  final case class BroadcastMessageToOnlineUsers(message: String) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { context =>
    context.log.info("starting OnlineUsers actor")
    val config = context.system.settings.config
    val maxOnlineLimit = config.getInt("users.max-online-limit")

    def updated(users: mutable.TreeSet[Long], userCount: Int): Behavior[Command] =
      Behaviors.receiveMessage[Command] {
        case RegisterAsOffline(userId) =>
          context.log.info("get RegisterAsOffline msg, userId: {}", userId)
          updated(users.subtractOne(userId), userCount - 1)
        case ApplyForTokenGeneration(userId, replyTo) =>
          if (users.contains(userId)) {
            replyTo ! StatusReply.Success()
            Behaviors.same
          }
          else if (userCount < maxOnlineLimit) {
            replyTo ! StatusReply.Success()
            updated(users.addOne(userId), userCount + 1)
          }
          else {
            replyTo ! StatusReply.Error("not allowed")
            Behaviors.same
          }
        case GetOnlineUserCount(replyTo) =>
          replyTo ! StatusReply.Success(userCount)
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
