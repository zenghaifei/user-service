package routes

import actors.{OnlineUsersBehavior, UserInfoEntity, UsersManagerPersistentBehavior}
import actors.OnlineUsersBehavior.GetOnlineUserCount
import actors.UserInfoEntity.{GetUserInfo, GetUserInfoFailed, UserInfo}
import actors.UsersManagerPersistentBehavior.{EmailExist, PhoneNumberExist, RegisterResult, RegisterSuccess, UsernameExist}
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import constants.DefinedHeaders
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

final case class UserRegisterRequest(username: Option[String], password: String, phoneNumber: Option[String], email: Option[String],
                                     nickname: String, gender: Option[String], address: Option[String], icon: Option[String], introduction: Option[String])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val f1 = jsonFormat9(UserRegisterRequest)
}

class UserRouter()(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with JsonSupport {
  implicit val timeout: Timeout = 3.seconds

  val sharding = ClusterSharding(system)

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
      case UserRegisterRequest(usernameOpt, password, phoneNumberOpt, emailOpt, nickname, genderOpt, addressOpt, iconOpt, introductionOpt) =>
        val usersManagerActor = UsersManagerPersistentBehavior.initSingleton(system)
        val userInfo = UsersManagerPersistentBehavior.UserInfo(
          username = usernameOpt.getOrElse(""),
          password = password,
          phoneNumber = phoneNumberOpt.getOrElse(""),
          email = emailOpt.getOrElse(""),
          nickname = nickname,
          gender = genderOpt.getOrElse(""),
          address = addressOpt.getOrElse(""),
          icon = iconOpt.getOrElse(""),
          introduction = introductionOpt.getOrElse(""))
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
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"username ${usernameOpt.get} registered by other users")))
              case PhoneNumberExist =>
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"phoneNumber ${phoneNumberOpt.get} registered by other users")))
              case EmailExist =>
                complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"email ${emailOpt.get} registered by other users")))
            }
        }
    }
  }

  private def getUserInfo = (get & path("user" / "info")) {
    optionalHeaderValueByName(DefinedHeaders.xForwardedUser) { userIdOpt: Option[String] =>
      userIdOpt.map(_.toLong) match {
        case None =>
          complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"no header ${DefinedHeaders.xForwardedUser}")))
        case Some(userId) =>
          val userInfoEntity = UserInfoEntity.selectEntity(userId, sharding)
          val userInfoResultF = userInfoEntity.ask(ref => GetUserInfo(ref))
          onComplete(userInfoResultF) {
            case Failure(e) =>
              this.log.warn("get user info result future failed, userId: {}, msg: {}, stack: {}", userId, e.getMessage, e.getStackTrace)
              complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)))
            case Success(userInfoResult) =>
              userInfoResult match {
                case GetUserInfoFailed(msg) =>
                  this.log.warn("get user info failed, user not exist, userId: {}", userId)
                  complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(msg)))
                case UserInfo(userId: Long, username: String, phoneNumber: String, email: String, loginPassword: String, nickname: String, gender: String, address: String, icon: String, introduction: String) =>
                  complete(JsObject(
                    "code" -> JsNumber(0),
                    "msg" -> JsString("success"),
                    "data" -> JsObject(
                      "userId" -> JsNumber(userId),
                      "nickname" -> JsString(nickname),
                      "gender" -> JsString(gender),
                      "icon" -> JsString(icon),
                      "introduction" -> JsString(introduction)
                    )
                  ))
              }
          }
      }
    }
  }

  val routes: Route = concat(
    getOnlineUserCount,
    register,
    getUserInfo
  )
}
