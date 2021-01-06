package routes

import actors.OnlineUsersBehavior
import actors.OnlineUsersBehavior.GetOnlineUserCount
import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.AskPattern.{Askable, schedulerFromActorSystem}
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import spray.json.{DefaultJsonProtocol, JsNumber, JsObject, JsString}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.Success

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

//  private def register = (post & path("user" / "register")) {
//    entity(as[UserRegisterRequest]) {
//      case UserRegisterRequest(username, password, phoneNumber, email, gender, address, icon, introduction) =>
//        if (userName.size > 70)
//    }
//  }

  val routes: Route = concat(
    getOnlineUserCount
  )
}
