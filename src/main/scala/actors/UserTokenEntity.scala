package actors

import actors.OnlineUsersBehavior.RegisterAsOffline
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import constants.UserRoles
import services.JwtService

import java.time.{Duration, LocalDateTime}
import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2020/12/24
 * @since 0.4.1
 */
object UserTokenEntity {

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class GenerateToken(ip: String, userAgent: String, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class GetLatestTokenInfo(replyTo: ActorRef[LatestTokenInfo]) extends Command

  final case class AdjustTokenExpireTime(tokenId: Long, adjustTime: LocalDateTime) extends Command

  final case object InvalidateToken extends Command

  // reply
  sealed trait Reply extends JacksonCborSerializable

  final case class LatestTokenInfo(tokenId: Long, expireTime: LocalDateTime) extends Reply

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class TokenGenerated(tokenId: Long, generateTime: LocalDateTime, ip: String, userAgent: String) extends Event

  final case class TokenInvalidated(invalidateTime: LocalDateTime) extends Event

  // state
  sealed trait State extends JacksonCborSerializable {

    def getLatestTokenId: Long

    def getLatestTokenExpireTime: LocalDateTime

    def handleAdjustExpireTime(tokenId: Long, adjustTime: LocalDateTime, tokenExpireDuration: Duration): Unit

    def handleTokenInvalidatedEvent(tokenInvalidated: TokenInvalidated): State

    def applyCommand(command: Command, userId: Long, jwtService: JwtService,
                     tokenExpireDuration: Duration, context: ActorContext[Command]): Effect[Event, State] = {
      command match {
        case GenerateToken(ip, userAgent, replyTo) =>
          context.log.info("generate token request received, userId: {}, ip: {}, userAgent: {}, latestTokenId: {}", userId, ip, userAgent, getLatestTokenId)
          val newTokenId = getLatestTokenId + 1
          val token = jwtService.generateToken(UserRoles.END_USER, userId, newTokenId)
          val tokenGenerated = TokenGenerated(newTokenId, LocalDateTime.now(), ip, userAgent)
          Effect.persist(tokenGenerated).thenReply(replyTo)(_ => StatusReply.Success(token))
        case GetLatestTokenInfo(replyTo) =>
          context.log.info("get user latest token info request received, userId: {}", userId)
          Effect.none.thenReply(replyTo)(_ => LatestTokenInfo(getLatestTokenId, getLatestTokenExpireTime))
        case AdjustTokenExpireTime(tokenId, adjustTime) =>
          context.log.info("adjust expire time, tokenId: {}, adjustTime: {}", tokenId, adjustTime)
          Effect.none.thenRun(_ => handleAdjustExpireTime(tokenId, adjustTime, tokenExpireDuration))
        case InvalidateToken =>
          context.log.info("invalidate token, userId: {}", userId)
          this match {
            case BlankState =>
              Effect.none.thenStop()
            case _ =>
              Effect.persist(TokenInvalidated(LocalDateTime.now())).thenStop()
          }
      }
    }

    def applyEvent(event: Event, tokenExpireDuration: Duration): State = event match {
      case TokenGenerated(tokenId, generateTime, ip, userAgent) =>
        GeneratedState(tokenId, generateTime.plus(tokenExpireDuration))
      case event: TokenInvalidated => handleTokenInvalidatedEvent(event)
      case _ =>
        throw new IllegalStateException(s"unexpected event [$event] in state [BlankState]")
    }
  }

  final case object BlankState extends State {

    override def getLatestTokenId: Long = 0L

    override def getLatestTokenExpireTime: LocalDateTime = LocalDateTime.MIN

    override def handleAdjustExpireTime(tokenId: Long, adjustTime: LocalDateTime, tokenExpireDuration: Duration): Unit = {
      throw new IllegalArgumentException("Unexpected command [AdjustExpireTime] in state [BlankState]")
    }

    override def handleTokenInvalidatedEvent(tokenInvalidated: TokenInvalidated): State = {
      throw new IllegalArgumentException("Unexpected command [InvalidateToken] in state [BlankState]")
    }
  }

  final case class GeneratedState(latestTokenId: Long, var latestTokenExpireTime: LocalDateTime) extends State {
    override def getLatestTokenId: Long = latestTokenId

    override def getLatestTokenExpireTime: LocalDateTime = latestTokenExpireTime

    override def handleAdjustExpireTime(tokenId: Long, adjustTime: LocalDateTime, tokenExpireDuration: Duration): Unit = {
      val adjustedExpireTime = adjustTime.plus(tokenExpireDuration)
      if (tokenId == latestTokenId && adjustedExpireTime.isAfter(latestTokenExpireTime)) {
        this.latestTokenExpireTime = adjustedExpireTime
      }
    }

    override def handleTokenInvalidatedEvent(tokenInvalidated: TokenInvalidated): State = {
      this.latestTokenExpireTime = tokenInvalidated.invalidateTime
      this
    }
  }

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
    context.log.info("starting userTokenActor, userId: {}, token expire duration: {}", entityId, tokenExpireDuration)
    val userId = entityId.toLong
    val jwtService = new JwtService(config)

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = BlankState,
      commandHandler = (state, command) => state.applyCommand(command, userId, jwtService, tokenExpireDuration, context),
      eventHandler = (state, event) => state.applyEvent(event, tokenExpireDuration)
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
