package services

import akka.event.slf4j.SLF4JLogging
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.{JWT, JWTVerifier}
import com.typesafe.config.Config
import services.JwtService.JwtClaims
import utils.TimeUtils

import java.time.LocalDateTime

/**
 * services
 *
 * @author colin
 * @version 1.0, 2020/12/21
 * @since 0.4.1
 */
object JwtService {

  case class JwtClaims(role: String, userId: Long, tokenId: Long)
  object JwtClaims {
    val role = "role"
    val userId = "userId"
    val tokenId = "tokenId"
  }

}

class JwtService(config: Config) extends SLF4JLogging {

  private val secret: String = this.config.getString("jwt.secret").trim()

  private val issuer: String = this.config.getString("jwt.issuer")

  private val algorithm: Algorithm = Algorithm.HMAC256(secret)

  def generateToken(role: java.lang.String, userId: java.lang.Long, tokenId: java.lang.Long): String = {
    val now = LocalDateTime.now()
    val expiresAt = now.plusHours(24 * 7)
    JWT.create()
      .withIssuer(this.issuer)
      .withIssuedAt(TimeUtils.toDate(now))
      .withExpiresAt(TimeUtils.toDate(expiresAt))
      .withClaim(JwtClaims.role, role)
      .withClaim(JwtClaims.userId, userId)
      .withClaim(JwtClaims.tokenId, tokenId)
      .sign(this.algorithm)
  }

  private val verifier: JWTVerifier = JWT.require(this.algorithm)
    .withIssuer(this.issuer)
    .build()

  def decodeToken(token: String): Option[JwtClaims] = {
    val decodedJwtOpt = {
      try {
        Some(this.verifier.verify(token))
      } catch {
        case e: Throwable =>
          log.info(s"****, jwt verification error:${e}")
          None
      }
    }

    decodedJwtOpt
      .map { decodedJwt =>
        val role = decodedJwt.getClaim(JwtClaims.role).asString()
        val userId = decodedJwt.getClaim(JwtClaims.userId).asLong()
        val tokenId = decodedJwt.getClaim(JwtClaims.tokenId).asLong()
        JwtClaims(role = role, userId = userId, tokenId = tokenId)
      }
  }
}
