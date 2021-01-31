package routes

import akka.http.scaladsl.model.{ContentType, HttpEntity, MediaTypes}
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.StreamConverters
import services.QrCodeService


/**
 * routes
 *
 * @author colin
 * @version 1.0, 2021/1/31
 * @since 0.4.1
 */
class QrCodeRouter(qrCodeService: QrCodeService) {

  private def generateQrCode =
    (post & path("user" / "public" / "qrcode" / "generate") & parameter("data")) { data: String =>
      val source = StreamConverters.fromInputStream(() => qrCodeService.createQRCode(data, 255, 255, 1))
      complete(HttpEntity(ContentType(mediaType = MediaTypes.`image/jpeg`), source))
    }

  val routes = concat(
    generateQrCode
  )
}
