package services

import akka.actor.typed.ActorSystem
import akka.event.slf4j.SLF4JLogging
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest}
import akka.http.scaladsl.unmarshalling.Unmarshal
import com.typesafe.config.Config
import spray.json.{JsObject, JsString}

import java.time.LocalDateTime
import scala.concurrent.Future

/**
 * services
 *
 * @author colin
 * @version 1.0, 2021/1/27
 * @since 0.4.1
 */
class MessagesService(config: Config)(implicit system: ActorSystem[_]) extends SLF4JLogging {
  private implicit val ec = this.system.executionContext
  private val messagesServiceHost = config.getString("services.messages-service")
  private lazy val sendInstantEmailUrl = s"http://${this.messagesServiceHost}/messages/email/instant/send"

  def sendInstantEmail(receiver: String, subject: String, content: String, sendTime: LocalDateTime, overdueTime: LocalDateTime): Future[Unit] = {
    val requestParamStr = JsObject(
      "receiver" -> JsString(receiver),
      "subject" -> JsString(subject),
      "content" -> JsString(content),
      "sendTime" -> JsString(sendTime.toString()),
      "overdueTime" -> JsString(overdueTime.toString())
    ).toString()
    val request = HttpRequest(
      method = HttpMethods.POST,
      uri = this.sendInstantEmailUrl,
      entity = HttpEntity(
        ContentTypes.`application/json`,
        requestParamStr
      )
    )
    Http().singleRequest(request)
      .flatMap { response =>
        if (response.status.isFailure()) {
          Unmarshal(response).to[String]
            .map { responseStr =>
              val errorMsg = s"request failed, uri: ${request.uri}, request params: $requestParamStr, response: $responseStr"
              log.warn(errorMsg)
              throw new Exception(errorMsg)
            }
        }
        else {
          Future()
        }
      }
  }
}
