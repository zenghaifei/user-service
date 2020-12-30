package routes

import actors.OnlineUsersBehavior.GetOnlineUserCount
import actors.UserTokenEntity._
import actors.{OnlineUsersBehavior, UserTokenEntity}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import services.JwtService
import services.JwtService.JwtClaims
import spray.json.{JsNumber, JsObject, JsString}

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * routes
 *
 * @author colin
 * @version 1.0, 2020/11/10
 * @since 0.4.1
 */
class AuthRouter(jwtService: JwtService)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with SprayJsonSupport {
  implicit val timeout: Timeout = 3.seconds

  val sharding = ClusterSharding(system)
  val userActorShardRegion = UserTokenEntity.shardRegion(sharding)

  val config = this.system.settings.config

  val maxOnlineLimit = config.getInt("users.max-online-limit")

  private def generateToken = (post & path("auth" / "token" / "generate") & parameter("userId")) { userId: String =>
    val onlineUsersActor = OnlineUsersBehavior.initSingleton(system)
    val onlineUserCountF = onlineUsersActor.ask(replyTo => GetOnlineUserCount(replyTo)).map(_.count)
    val tokenF: Future[String] =
      onlineUserCountF.flatMap { count =>
        if (count < this.maxOnlineLimit) {
          val userTokenEntity = UserTokenEntity.selectEntity(userId.toLong, sharding)
          userTokenEntity.ask(ref => GenerateToken("ip", "agent", ref)).map(_.value)
        }
        else {
          Future.failed(new Exception("max online users reached, try some time later"))
        }
      }
    onComplete(tokenF) {
      case Success(token) =>
        complete(JsObject("code" -> JsNumber(0),
          "msg" -> JsString("success"),
          "data" -> JsObject(
            "token" -> JsString(token)
          )
        ))
      case Failure(e) =>
        log.warn("ask generate token failed, userId: {}, msg: {}, stack: {}", userId, e.getMessage, e.fillInStackTrace())
        complete(status = StatusCodes.InternalServerError, e.getMessage)
    }
  }

  private def invalidateToken = (post & path("auth" / "token" / "invalidate") & parameter("userId")) { userId: String =>
    val userEntity = UserTokenEntity.selectEntity(userId.toLong, sharding)
    userEntity ! InvalidateToken
    complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
  }

  private def verifyToken = (get & path("auth" / "token" / "verify")) {
    optionalHeaderValueByName("Authorization") { tokenOpt: Option[String] =>
      tokenOpt
        .flatMap { token =>
          val jwtToken = token.substring(7)
          this.jwtService.decodeToken(jwtToken)
        }
        .map { case JwtClaims(userId, tokenId) =>
          val userTokenEntity = UserTokenEntity.selectEntity(userId.toLong, sharding)
          val tokenInfoF: Future[LatestTokenInfo] = userTokenEntity.ask(ref => GetLatestTokenInfo(ref))
          onComplete(tokenInfoF) {
            case Success(LatestTokenInfo(latestTokenId, tokenExpireTime)) =>
              val now = LocalDateTime.now()
              if (tokenId != latestTokenId || tokenExpireTime.isBefore(now)) {
                complete(HttpResponse(StatusCodes.Unauthorized))
              }
              else {
                userTokenEntity ! AdjustTokenExpireTime(tokenId, now)
                complete(HttpResponse(StatusCodes.OK, Seq(headers.RawHeader("X-Forwarded-User", userId.toString))))
              }
            case Failure(ex) =>
              log.warn("ask get latest token info failed, userId: {}, msg: {}, stack: {}", userId, ex.getMessage, ex.fillInStackTrace())
              complete(HttpResponse(StatusCodes.InternalServerError))
          }
        }
        .getOrElse {
          complete(HttpResponse(StatusCodes.Unauthorized))
        }
    }
  }

  val routes: Route = concat(
    generateToken,
    invalidateToken,
    verifyToken
  )
}
