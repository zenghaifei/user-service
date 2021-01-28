package routes

import actors.UserTokenEntity
import actors.UserTokenEntity._
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{complete, _}
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import constants.{DefinedHeaders, UserRoles}
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

  private def auth = (get & path("user" / "auth") & extractRequest) { request =>
    val jwtClaimsUriOpt = try {
      val token: String = request.getHeader(DefinedHeaders.authorization).map(_.value()).get()
      val jwtClaimsOpt = this.jwtService.decodeToken(token.substring(7))
      val uri = request.getHeader(DefinedHeaders.xOriginalURI).map(_.value()).get()
      jwtClaimsOpt.map((_, uri))
    } catch {
      case ex: Throwable =>
        log.info("invalide headers, msg: {}", ex.getMessage)
        Option.empty[(JwtClaims, String)]
    }

    val resultF = jwtClaimsUriOpt.fold(Future(complete(HttpResponse(StatusCodes.Unauthorized)))) { case (JwtClaims(role, userId, tokenId), uri) =>
      val rolePart = uri.split("/")(1)
      val uriRole = rolePart match {
        case UserRoles.ADMIN => rolePart
        case _ => UserRoles.END_USER
      }
      if (uriRole != role) {
        Future(complete(HttpResponse(StatusCodes.Forbidden)))
      }
      else {
        val userTokenEntity = UserTokenEntity.selectEntity(userId, sharding)
        userTokenEntity.ask(ref => GetLatestTokenInfo(ref))
          .map { case LatestTokenInfo(latestTokenId, tokenExpireTime) =>
            val now = LocalDateTime.now()
            if (tokenId != latestTokenId || tokenExpireTime.isBefore(now)) {
              complete(HttpResponse(StatusCodes.Unauthorized))
            }
            else {
              userTokenEntity ! AdjustTokenExpireTime(tokenId, now)
              complete(HttpResponse(StatusCodes.OK, Seq(headers.RawHeader("X-Forwarded-User", userId.toString))))
            }
          }
      }
    }

    onComplete(resultF) {
      case Success(route) => route
      case Failure(ex) =>
        log.warn("future failed, msg: {}, stack: {}", ex.getMessage, ex.fillInStackTrace())
        complete(HttpResponse(StatusCodes.InternalServerError))
    }
  }

  val routes: Route = concat(
    auth
  )
}
