package actors

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior, SupervisorStrategy}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity, EntityRef, EntityTypeKey}
import akka.pattern.StatusReply
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior, RetentionCriteria}
import utils.StringUtils

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt

/**
 * actors
 *
 * @author colin
 * @version 1.0, 2021/1/25
 * @since 0.4.1
 */
object EmailCodeEntity {

  final case class CodeData(code: String, overdueTime: LocalDateTime)

  // command
  sealed trait Command extends JacksonCborSerializable

  final case class GenerateEmailCode(overdueTime: LocalDateTime, replyTo: ActorRef[StatusReply[String]]) extends Command

  final case class GetEmailCode(replyTo: ActorRef[StatusReply[CodeData]]) extends Command

  final case class InvalidateEmailCode(replyTo: ActorRef[StatusReply[Unit]]) extends Command

  // event
  sealed trait Event extends JacksonJsonSerializable

  final case class EmailCodeGenerated(code: String, overdueTime: LocalDateTime) extends Event

  final case object EmailCodeInvalidated extends Event

  // state
  val invalidatedCodeData = CodeData("", LocalDateTime.MIN)

  final case class State(email: String, var codeData: CodeData) extends JacksonCborSerializable {

    def applyCommand(command: Command): Effect[Event, State] = {
      command match {
        case GenerateEmailCode(overdueTime, replyTo) =>
          val code = StringUtils.generateEmailCode().toLowerCase
          val codeGenerated = EmailCodeGenerated(code, overdueTime)
          Effect.persist(codeGenerated).thenReply(replyTo)(_ => StatusReply.Success(code))
        case GetEmailCode(replyTo) =>
          Effect.none.thenReply(replyTo)(_ => StatusReply.Success(this.codeData))
        case InvalidateEmailCode(replyTo) =>
          Effect.persist(EmailCodeInvalidated).thenReply(replyTo)(_ => StatusReply.Success())
      }
    }

    def applyEvent(event: Event): State = {
      event match {
        case EmailCodeGenerated(code, overdueTime) =>
          this.codeData = CodeData(code, overdueTime)
          this
        case EmailCodeInvalidated =>
          this.codeData = invalidatedCodeData
          this
      }
    }
  }

  val TypeKey = EntityTypeKey[Command]("email-code")

  def shardRegion(sharding: ClusterSharding): ActorRef[ShardingEnvelope[Command]] =
    sharding.init {
      Entity(TypeKey) { entityContext =>
        EmailCodeEntity(entityContext.entityId, PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId))
      }
    }

  def selectEntity(email: String, sharding: ClusterSharding): EntityRef[Command] = sharding.entityRefFor(TypeKey, email)

  def apply(entityId: String, persistenceId: PersistenceId): Behavior[Command] = Behaviors.setup { context =>
    val email = entityId
    context.log.info("starting email code entity, email: {}", email)

    EventSourcedBehavior[Command, Event, State](
      persistenceId = persistenceId,
      emptyState = State(email, invalidatedCodeData),
      commandHandler = (state, command) => state.applyCommand(command),
      eventHandler = (state, event) => state.applyEvent(event)
    )
      .withRetention(RetentionCriteria.snapshotEvery(20, 1))
      .onPersistFailure(SupervisorStrategy.restartWithBackoff(200.millis, 5.seconds, 0.1))
  }

}
