package routes

import actors.UserTokenEntity
import actors.UserTokenEntity._
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import services.JwtService
import services.JwtService.JwtClaims

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
  val config = this.system.settings.config

  private def verifyToken = (get & path("user" / "auth" / "token" / "verify")) {
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
    verifyToken
  )
}
