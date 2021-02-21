package actors

import actors.OnlineUsersBehavior.RegisterAsOffline
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import constants.UserRoles
import services.JwtService

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2020/12/24
 * @since 0.4.1
 */
object UserTokenEntity {

  val INIT_TOKEN_ID = 0L
  final case class LatestTokenInfo(tokenId: Long, var expireTime: LocalDateTime) extends JacksonCborSerializable

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class GenerateToken(ip: String, userAgent: String, replyTo: ActorRef[String]) extends Command

  final case class GetLatestTokenInfo(replyTo: ActorRef[LatestTokenInfo]) extends Command

  final case class AdjustTokenExpireTime(tokenId: Long, adjustTime: LocalDateTime) extends Command

  final case object InvalidateToken extends Command

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class TokenGenerated(tokenId: Long, generateTime: LocalDateTime, ip: String, userAgent: String) extends Event

  final case object TokenInvalidated extends Event

  // entity body

  val TypeKey = EntityTypeKey[Command]("user-token")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        UserTokenEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(userId: Long, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, userId.toString())

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    val config = context.system.settings.config
    val tokenExpireDuration = config.getDuration("users.token.expire-duration")
    context.log.info("starting userTokenEntity, userId: {}, token expire duration: {}", entityId, tokenExpireDuration)
    val onlineUsersActor = OnlineUsersBehavior.initSingleton(context.system)
    val userId = entityId.toLong
    onlineUsersActor ! OnlineUsersBehavior.RegisterAsOnline(userId)
    val jwtService = new JwtService(config)

    EventSourcedBehavior[Command, Event, LatestTokenInfo](
      persistenceId = persistenceId,
      emptyState = LatestTokenInfo(INIT_TOKEN_ID, LocalDateTime.MIN),
      eventHandler = (state, event) => {
        event match {
          case TokenGenerated(tokenId, generateTime, _, _) =>
            LatestTokenInfo(tokenId, generateTime.plus(tokenExpireDuration))
          case TokenInvalidated =>
            state.copy(expireTime = LocalDateTime.MIN)
        }
      },
      commandHandler = (state, command) => {
        command match {
          case GenerateToken(ip, userAgent, replyTo) =>
            context.log.info("generate token request received, userId: {}, ip: {}, userAgent: {}, latestTokenId: {}", userId, ip, userAgent, state.tokenId)
            val newTokenId = state.tokenId + 1
            val token = jwtService.generateToken(UserRoles.END_USER, userId, newTokenId)
            val tokenGenerated = TokenGenerated(newTokenId, LocalDateTime.now(), ip, userAgent)
            Effect.persist(tokenGenerated).thenReply(replyTo)(_ => token)
          case GetLatestTokenInfo(replyTo) =>
            context.log.info("get user latest token info request received, userId: {}", userId)
            Effect.none.thenReply(replyTo)(_ => state)
          case AdjustTokenExpireTime(tokenId, adjustTime) =>
            context.log.info("adjust expire time, tokenId: {}, adjustTime: {}", tokenId, adjustTime)
            if (tokenId == INIT_TOKEN_ID) {
              new Exception("can't adjust token expire time on init status")
            }
            val adjustedExpireTime = adjustTime.plus(tokenExpireDuration)
            if (tokenId == state.tokenId && adjustedExpireTime.isAfter(state.expireTime)) {
               state.expireTime = adjustedExpireTime
            }
            Effect.none
          case InvalidateToken =>
            context.log.info("invalidate token, userId: {}", userId)
            if (state.expireTime.isAfter(LocalDateTime.now())) {
              Effect.persist(TokenInvalidated).thenStop()
            }
            else {
              Effect.none.thenStop()
            }
        }
      },
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
      .receiveSignal {
        case (_, PostStop) =>
          context.log.info("user actor stopped, userId: {}", entityId)
          val onlineUsersActor = OnlineUsersBehavior.initSingleton(context.system)
          onlineUsersActor ! RegisterAsOffline(userId)
      }
  }
}
