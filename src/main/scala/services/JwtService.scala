package services

import akka.event.slf4j.SLF4JLogging
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.auth0.jwt.{JWT, JWTVerifier}
import com.typesafe.config.Config
import services.JwtService.JwtClaims
import utils.{CryptoUtils, TimeUtils}

import java.io.{BufferedWriter, File, FileWriter}
import java.security.interfaces.{RSAPrivateKey, RSAPublicKey}
import java.time.LocalDateTime

/**
 * services
 *
 * @author colin
 * @version 1.0, 2020/12/21
 * @since 0.4.1
 */
object JwtService {

  case class JwtClaims(userId: Long, tokenId: Long)
  object JwtClaims {
    val userId = "userId"
    val tokenId = "tokenId"
  }

}

class JwtService(config: Config) extends SLF4JLogging {
  private val PRIVATE_KEY_FILE_PATH = "jwt_private.pem"
  private val PUBLIC_KEY_FILE_PATH = "jwt_public.pem"

  private lazy val rsaPrivateKey: RSAPrivateKey = {
    val privateKeyStr = this.config.getString("jwt.private-key").trim()
    val keyFile = new File(PRIVATE_KEY_FILE_PATH)
    val fileWriter = new FileWriter(keyFile)
    val bufferedWriter = new BufferedWriter(fileWriter)
    try {
      bufferedWriter.write(privateKeyStr)
    } finally {
      bufferedWriter.close()
      fileWriter.close()
    }
    try {
      CryptoUtils.Rsa.readPrivateKeyFromPemFile(PRIVATE_KEY_FILE_PATH)
    } finally {
      keyFile.delete()
    }
  }

  private lazy val rsaPublicKey: RSAPublicKey = {
    val publicKeyStr = config.getString("jwt.public-key").trim()
    val keyFile = new File(PUBLIC_KEY_FILE_PATH)
    val fileWriter = new FileWriter(keyFile)
    val bufferedWriter = new BufferedWriter(fileWriter)
    try {
      bufferedWriter.write(publicKeyStr)
    } finally {
      bufferedWriter.close()
      fileWriter.close()
    }
    CryptoUtils.Rsa.readPublicKeyFromPemFile(PUBLIC_KEY_FILE_PATH)
  }

  private lazy val issuer = this.config.getString("jwt.issuer")

  private lazy val verifier: JWTVerifier = {
    val jwtRsaVerifyAlgorithm = Algorithm.RSA256(this.rsaPublicKey, null)
    JWT.require(jwtRsaVerifyAlgorithm)
      .withIssuer(issuer)
      .build()
  }

  private lazy val rsaSignAlgorithm: Algorithm = Algorithm.RSA256(null, this.rsaPrivateKey)

  def generateToken(userId: java.lang.Long, tokenId: java.lang.Long): String = {
    val now = LocalDateTime.now()
    val expiresAt = now.plusHours(24 * 7)
    JWT.create()
      .withIssuer(this.issuer)
      .withIssuedAt(TimeUtils.toDate(now))
      .withExpiresAt(TimeUtils.toDate(expiresAt))
      .withClaim(JwtClaims.userId, userId)
      .withClaim(JwtClaims.tokenId, tokenId)
      .sign(this.rsaSignAlgorithm)
  }

  def decodeToken(token: String): Option[JwtClaims] = {
    val decodedJwtOpt = {
      try {
        Some(this.verifier.verify(token))
      } catch {
        case e: JWTVerificationException =>
          log.info(s"****, jwt verification error:${e}")
          None
      }
    }

    decodedJwtOpt
      .map { decodedJwt =>
        val userId = decodedJwt.getClaim(JwtClaims.userId).asLong()
        val tokenId = decodedJwt.getClaim(JwtClaims.tokenId).asLong()
        JwtClaims(userId = userId, tokenId = tokenId)
      }
  }
}
