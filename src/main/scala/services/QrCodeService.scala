package services

import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}

import java.awt.image.BufferedImage
import java.awt.{Color, Graphics2D}
import java.io.{ByteArrayInputStream, ByteArrayOutputStream}
import java.util
import javax.imageio.ImageIO

/**
 * services
 *
 * @author colin
 * @version 1.0, 2021/1/31
 * @since 0.4.1
 */
class QrCodeService {

  def createQRCode(contents: String, width: Integer, height: Integer, margin: Integer): ByteArrayInputStream = {
    val qrCodeWriter = new QRCodeWriter()
    val hintTypesMap = new util.EnumMap[EncodeHintType, Object](classOf[EncodeHintType])
    hintTypesMap.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.H)
    // set white border size
    hintTypesMap.put(EncodeHintType.MARGIN, margin)
    hintTypesMap.put(EncodeHintType.CHARACTER_SET, "UTF-8")
    val matrix = qrCodeWriter.encode(contents, BarcodeFormat.QR_CODE, width, height, hintTypesMap)

    val bufferedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    // Creates a Graphics2D, which can be used to draw into this BufferedImage.
    bufferedImage.createGraphics()
    val graphics: Graphics2D = bufferedImage.getGraphics.asInstanceOf[Graphics2D]
    // set qrcode background color
    graphics.setColor(Color.white)
    graphics.fillRect(0, 0, width, height)

    for {
      w <- 0 until width
      h <- 0 until height
    } {
      val sum = w + h
      if (sum % 3 == 0) {
        graphics.setColor(Color.gray)
      } else if (sum % 3 == 1) {
        graphics.setColor(Color.darkGray)
      } else {
        graphics.setColor(Color.black)
      }
      if (matrix.get(w, h)) {
        graphics.fillRect(w, h, 1, 1)
      }
    }

    val os = new ByteArrayOutputStream()
    try {
      ImageIO.write(bufferedImage, "jpeg", os)
    } finally {
      os.close()
    }
    new ByteArrayInputStream(os.toByteArray)
  }
}
