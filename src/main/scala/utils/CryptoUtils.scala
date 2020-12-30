package utils

import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}

/**
 * common
 *
 * @author colin
 * @version 1.0, 2020/7/21
 * @since 0.4.1
 */
object CryptoUtils {

  object Rsa {
    def readPrivateKeyFromPemFile(pemFilePath: String): RSAPrivateKey = {
      PemUtils.readPrivateKeyFromFile(pemFilePath, "RSA").asInstanceOf[RSAPrivateKey]
    }

    def readPublicKeyFromPemFile(pemFilePath: String): RSAPublicKey = {
      PemUtils.readPublicKeyFromFile(pemFilePath, "RSA").asInstanceOf[RSAPublicKey]
    }
  }

}
