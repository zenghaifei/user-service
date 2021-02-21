package routes

import actors.OnlineUsersBehavior.GetOnlineUserCount
import actors.UserInfoEntity.{apply => _, _}
import actors.UserTokenEntity.{GenerateToken, InvalidateToken}
import actors.UsersManagerPersistentBehavior._
import actors._
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Route, StandardRoute}
import akka.pattern.StatusReply
import akka.util.Timeout
import constants.DefinedHeaders
import services.MessagesService
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString}
import utils.HashUtils.Sha256
import utils.{HashUtils, StringUtils}

import java.time.LocalDateTime
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * routes
 *
 * @author colin
 * @version 1.0, 2020/12/30
 * @since 0.4.1
 */

final case class RegisterRequest(username: Option[String], password: String, phoneNumber: Option[String], phoneCode: Option[String], email: Option[String], emailCode: Option[String],
                                 nickname: String, gender: Option[String], address: Option[String], icon: Option[String], introduction: Option[String])

final case class LoginRequest(userIdentifier: String, password: String)

final case class ModifyUserInfoRequest(nickname: Option[String], gender: Option[String], address: Option[String], icon: Option[String], introduction: Option[String])

trait JsonSupport extends SprayJsonSupport with DefaultJsonProtocol {
  implicit val f1 = jsonFormat11(RegisterRequest)
  implicit val f2 = jsonFormat2(LoginRequest)
  implicit val f3 = jsonFormat5(ModifyUserInfoRequest)
}

class UserRouter(messagesService: MessagesService)(implicit ec: ExecutionContext, system: ActorSystem[_]) extends SLF4JLogging with JsonSupport {
  implicit val timeout: Timeout = 3.seconds

  val config = this.system.settings.config
  val registrationEmailCodeOverdueDuration = this.config.getDuration("users.registration.email-code-overdue-duration")
  val clusterSharding = ClusterSharding(system)

  private def getOnlineUserCount = (get & path("user" / "public" / "online" / "count")) {
    val onlineUsersActor = OnlineUsersBehavior.initSingleton(system)
    val onlineUserCountF = onlineUsersActor.ask(replyTo => GetOnlineUserCount(replyTo)).map(_.getValue)
    onComplete(onlineUserCountF) {
      case Success(count) =>
        complete(JsObject("code" -> JsNumber(0),
          "msg" -> JsString("success"),
          "data" -> JsObject("count" -> JsNumber(count))))
    }
  }

  private def sendRegistrationEmailCode =
    (post & path("user" / "public" / "register" / "email_code" / "send") & parameter("email")) { email =>
      if (StringUtils.isInvalidEmailFormat(email)) {
        complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"invalid email: ${email}")))
      }
      else {
        val emailCodeEntity = EmailCodeEntity.selectEntity(email, this.clusterSharding)
        val now = LocalDateTime.now()
        val overdueTime = now.plus(this.registrationEmailCodeOverdueDuration)
        val emailCodeF = emailCodeEntity.ask(replyTo => EmailCodeEntity.GenerateEmailCode(overdueTime, replyTo))
          .map(_.getValue)
        val sendEmailF = emailCodeF.flatMap { emailCode =>
          this.messagesService.sendInstantEmail(email, "注册邮箱验证码", s"您的注册邮箱验证码是<b>${emailCode}</b>", now, overdueTime)
        }
        onComplete(sendEmailF) {
          case Failure(ex) =>
            log.warn("send registration email code failed, email: {}, msg: {}, stack: {}", ex)
            complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(ex.getMessage)))
          case Success(_) =>
            complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
        }
      }
    }

  private def register: Route = (post & path("user" / "public" / "register")) {
    entity(as[RegisterRequest]) {
      case RegisterRequest(usernameOpt, password, phoneNumberOpt, phoneCodeOpt, emailOpt, emailCodeOpt, nickname, genderOpt, addressOpt, iconOpt, introductionOpt) =>
        if (emailOpt.isEmpty && phoneNumberOpt.isEmpty) {
          complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString("请补充邮箱或手机号码")))
        }
        else {
          val emailCodeVerifyResultF =
            emailOpt.fold(Future(true)) { email =>
              emailCodeOpt.fold(Future(false)) { emailCode =>
                val emailCodeEntity = EmailCodeEntity.selectEntity(email, this.clusterSharding)
                emailCodeEntity.ask(replyTo => EmailCodeEntity.GetEmailCode(replyTo))
                  .map(_.getValue)
                  .map { case EmailCodeEntity.CodeData(code, overdueTime) =>
                    if (overdueTime.isBefore(LocalDateTime.now()))
                      false
                    else if (code != emailCode)
                      false
                    else
                      true
                  }
              }
            }

          val registerResultF: Future[StandardRoute] = emailCodeVerifyResultF.flatMap {
            case false =>
              Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString("邮箱验证码错误"))))
            case true =>
              val usersManagerActor = UsersManagerPersistentBehavior.initSingleton(system)
              val userInfo = UsersManagerPersistentBehavior.UserInfo(
                username = usernameOpt.getOrElse(""),
                password = HashUtils.computeHashFromString(password, Sha256),
                phoneNumber = phoneNumberOpt.getOrElse(""),
                email = emailOpt.getOrElse(""),
                nickname = nickname,
                gender = genderOpt.getOrElse(""),
                address = addressOpt.getOrElse(""),
                icon = iconOpt.getOrElse(""),
                introduction = introductionOpt.getOrElse(""))
              usersManagerActor.ask(replyTo => UsersManagerPersistentBehavior.RegisterUser(userInfo, replyTo))
                .flatMap {
                  case RegisterSuccess =>
                    if (emailCodeOpt.isDefined) {
                      val emailCodeEntity = EmailCodeEntity.selectEntity(emailCodeOpt.get, this.clusterSharding)
                      emailCodeEntity.ask(replyTo => EmailCodeEntity.InvalidateEmailCode(replyTo))
                        .map { _ =>
                          complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
                        }
                    }
                    else {
                      Future(complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success"))))
                    }
                  case UsernameExist =>
                    Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"username ${usernameOpt.get} registered by other users"))))
                  case PhoneNumberExist =>
                    Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"phoneNumber ${phoneNumberOpt.get} registered by other users"))))
                  case EmailExist =>
                    Future(complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"email ${emailOpt.get} registered by other users"))))
                }
          }

          onComplete(registerResultF) {
            case Failure(e) =>
              this.log.warn("registerResult future failed, msg: {}, stack: {}", e.getMessage, e.getStackTrace)
              complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)))
            case Success(registerResult) => registerResult
          }
        }
    }
  }

  private def login = (post & path("user" / "public" / "login")) {
    optionalHeaderValueByName(DefinedHeaders.userAgent) { userAgentOpt: Option[String] =>
      extractClientIP { clientRemoteAddress =>
        entity(as[LoginRequest]) { case LoginRequest(userIdentifier, password) =>
          lazy val usersManagerActor = UsersManagerPersistentBehavior.initSingleton(system)
          val userIdResultF: Future[StatusReply[Long]] =
            if (StringUtils.isValidCnPhoneNumberFormat(userIdentifier)) {
              usersManagerActor.ask(ref => GetUserIdByPhoneNumber(userIdentifier, ref))
            }
            else if (StringUtils.isValidEmailFormat(userIdentifier)) {
              usersManagerActor.ask(ref => GetUserIdByEmail(userIdentifier, ref))
            }
            else if (StringUtils.isValidUsernameFormat(userIdentifier)) {
              usersManagerActor.ask(ref => GetUserIdByUsername(userIdentifier, ref))
            }
            else {
              Future(StatusReply.Error[Long]("user not exist"))
            }

          val loginVerifiedUserIdF = userIdResultF
            .map(_.getValue)
            .flatMap { userId: Long =>
              val userEntity = UserInfoEntity.selectEntity(userId, clusterSharding)
              userEntity.ask(ref => GetUserInfo(ref))
                .map(_.getValue)
            }
            .flatMap { userInfo: UserInfoEntity.UserInfo =>
              val hashedPassword = HashUtils.computeHashFromString(password, Sha256)
              if (hashedPassword == userInfo.loginPassword) {
                Future(userInfo.userId)
              }
              else {
                Future.failed(new Exception("wrong username or password"))
              }
            }

          val generateTokenF = loginVerifiedUserIdF
            .flatMap { userId =>
              val userTokenEntity = UserTokenEntity.selectEntity(userId, clusterSharding)
              val clientIP: String = clientRemoteAddress.toOption.map(_.getHostAddress).getOrElse("unknown")
              val userAgent: String = userAgentOpt.getOrElse("unknown")
              userTokenEntity.ask(ref => GenerateToken(clientIP, userAgent, ref))
            }

          onComplete(generateTokenF) {
            case Failure(ex) =>
              this.log.info("verify login failed, userIdentifier: {}, msg: {}, stack: {}", userIdentifier, ex.getMessage, ex.getStackTrace)
              complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString("username or password error")))
            case Success(token) =>
              complete(JsObject(
                "code" -> JsNumber(0),
                "msg" -> JsString("login success"),
                "data" -> JsObject(
                  "token" -> JsString(token)
                )
              ))
          }
        }
      }
    }
  }

  private def logout = (post & path("user" / "logout")) {
    optionalHeaderValueByName(DefinedHeaders.xForwardedUser) { userIdOpt =>
      userIdOpt
        .map(_.toLong)
        .map { userId =>
          val userEntity = UserTokenEntity.selectEntity(userId, clusterSharding)
          userEntity ! InvalidateToken
          complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
        }
        .getOrElse {
          complete(JsObject("code" -> JsNumber(1), "msg" -> JsString(s"user not exist")))
        }
    }
  }

  private def getUserInfo = (get & path("user" / "info")) {
    optionalHeaderValueByName(DefinedHeaders.xForwardedUser) { userIdOpt: Option[String] =>
      userIdOpt.map(_.toLong) match {
        case None =>
          complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(s"no header ${DefinedHeaders.xForwardedUser}")))
        case Some(userId) =>
          val userInfoEntity = UserInfoEntity.selectEntity(userId, clusterSharding)
          val userInfoStatusReplyF: Future[StatusReply[UserInfoEntity.UserInfo]] = userInfoEntity.ask(ref => GetUserInfo(ref))
          onComplete(userInfoStatusReplyF) {
            case Failure(e) =>
              this.log.warn("get user info result future failed, userId: {}, msg: {}, stack: {}", userId, e.getMessage, e.getStackTrace)
              complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString(e.getMessage)))
            case Success(userInfoStatusReply) =>
              userInfoStatusReply match {
                case StatusReply.Error(ex) =>
                  this.log.warn("get user info failed, user not exist, userId: {}", userId)
                  complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString(ex.getMessage)))
                case StatusReply.Success(UserInfoEntity.UserInfo(userId: Long,
                username: String,
                phoneNumber: String,
                email: String,
                loginPassword: String,
                nickname: String,
                gender: String,
                address: String,
                icon: String,
                introduction: String)) =>
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

  private def modifyUserInfo = (post & path("user" / "info" / "modify")) {
    optionalHeaderValueByName(DefinedHeaders.xForwardedUser) { userIdOpt: Option[String] =>
      userIdOpt.map(_.toLong) match {
        case None =>
          complete(status = StatusCodes.BadRequest, JsObject("code" -> JsNumber(1), "msg" -> JsString("failed")))
        case Some(userId) =>
          entity(as[ModifyUserInfoRequest]) { request =>
            val userInfoEntity = UserInfoEntity.selectEntity(userId, clusterSharding)
            val modifyResultF: Future[Unit] = userInfoEntity.ask[StatusReply[Unit]](ref => ModifyUserInfo(
              nickname = request.nickname.getOrElse(""),
              gender = request.gender.getOrElse(""),
              address = request.address.getOrElse(""),
              icon = request.icon.getOrElse(""),
              introduction = request.introduction.getOrElse(""),
              replyTo = ref
            ))
              .map(_.getValue)
            onComplete(modifyResultF) {
              case Failure(ex) =>
                log.warn("ask userInfoEntity failed, msg: {}, stack: {}", ex.getMessage, ex.getStackTrace)
                complete(status = StatusCodes.InternalServerError, JsObject("code" -> JsNumber(1), "msg" -> JsString("failed")))
              case Success(_) =>
                complete(JsObject("code" -> JsNumber(0), "msg" -> JsString("success")))
            }
          }
      }
    }
  }

  val routes: Route = concat(
    getOnlineUserCount,
    sendRegistrationEmailCode,
    register,
    login,
    logout,
    getUserInfo,
    modifyUserInfo
  )
}
