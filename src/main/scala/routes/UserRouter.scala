package routes

import actors.{OnlineUsersBehavior, UsersManagerPersistentBehavior}
import actors.OnlineUsersBehavior.GetOnlineUserCount
import actors.UsersManagerPersistentBehavior.{EmailExist, PhoneNumberExist, RegisterResult, RegisterSuccess, UsernameExist}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString}

import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

/**
 * routes
 *
 * @author colin
 * @version 1.0, 2020/12/30
 * @since 0.4.1
 */

final case class UserRegisterRequest(username: String, password: String, phoneNumber: String, email: String,
                                     gender: String, address: String, icon: String, introduction: String)

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val f1 = jsonFormat8(UserRegisterRequest)
}

class UserRouter()(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with JsonSupport {
  implicit val timeout: Timeout = 3.seconds

  private def getOnlineUserCount =
    (get & path("user" / "online" / "count")) {
      val onlineUsersActor = OnlineUsersBehavior.initSingleton(system)
      val onlineUserCountF = onlineUsersActor.ask(replyTo => GetOnlineUserCount(replyTo)).map(_.count)
      onComplete(onlineUserCountF) {
        case Success(count) =>
          complete(JsObject("code" -> JsNumber(0),
            "msg" -> JsString("success"),
            "data" -> JsObject("count" -> JsNumber(count))))
      }
    }

  private def register = (post & path("user" / "register")) {
    entity(as[UserRegisterRequest]) {
      case UserRegisterRequest(username, password, phoneNumber, email, gender, address, icon, introduction) =>
        val usersManagerActor = UsersManagerPersistentBehavior.initSingleton(system)
        val userInfo = UsersManagerPersistentBehavior.UserInfo(
          username = username,
          password = password,
          phoneNumber = phoneNumber,
          email = email,
          gender = gender,
          address = address,
          icon = icon,
          introduction = introduction)
        val registerResultF: Future[RegisterResult] = usersManagerActor.ask(replyTo => UsersManagerPersistentBehavior.RegisterUser(userInfo, replyTo))
        onComplete(registerResultF) {
          case Failure(e) =>
            this.log.warn("registerResult future failed, msg: {}, stack: {}", e.getMessage, e.getStackTrace)
            complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)))
          case Success(registerResult) =>
            registerResult match {
              case RegisterSuccess =>
                complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
              case UsernameExist =>
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"username ${username} registered by other users")))
              case PhoneNumberExist =>
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"phoneNumber ${phoneNumber} registered by other users")))
              case EmailExist =>
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"email ${email} registered by other users")))
            }
        }
    }
  }

  val routes: Route = concat(
    getOnlineUserCount,
    register
  )
}
