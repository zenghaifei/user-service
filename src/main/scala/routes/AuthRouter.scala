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

  private def auth = (get & path("user" / "auth")) {
    optionalHeaderValueByName(DefinedHeaders.authorization) { tokenOpt: Option[String] =>
      optionalHeaderValueByName(DefinedHeaders.xOriginalURI) { uriOpt: Option[String] =>
        val uriJwtClaimsOpt = for {
          token <- tokenOpt
          jwtClaims <- this.jwtService.decodeToken(token.substring(7))
          uri <- uriOpt
        } yield (uri, jwtClaims)
        val routeF = uriJwtClaimsOpt match {
          case None => Future(complete(HttpResponse(StatusCodes.Unauthorized)))
          case Some((uri, JwtClaims(role, userId, tokenId))) =>
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
        onComplete(routeF) {
          case Success(route) => route
          case Failure(ex) =>
            log.warn("future failed, msg: {}, stack: {}", ex.getMessage, ex.fillInStackTrace())
            complete(HttpResponse(StatusCodes.InternalServerError))
        }
      }
    }
  }

  val routes: Route = concat(
    auth
  )
}
