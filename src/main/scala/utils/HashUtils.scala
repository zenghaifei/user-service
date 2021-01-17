package utils

import org.bouncycastle.util.encoders.Hex

import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.{DigestInputStream, MessageDigest}

/**
 *
 *
 * @author colin
 * @version 1.0, 2020/11/28
 * @since 0.0.1
 */
object HashUtils {

  abstract class HashAlgorithm(val value: String)

  case object Sha256 extends HashAlgorithm("SHA-256")

  case object Sha1 extends HashAlgorithm("SHA-1")

  case object Md5 extends HashAlgorithm("MD5")

  case object Md2 extends HashAlgorithm("MD2")

  case object Sha384 extends HashAlgorithm("SHA-384")

  case object Sha512 extends HashAlgorithm("SHA-512")

  def computeHashFromStream(inputStream: InputStream, hashAlgorithm: HashAlgorithm): String = {
    val buffer = new Array[Byte](8192)
    val digester = MessageDigest.getInstance(hashAlgorithm.value)
    val dis = new DigestInputStream(inputStream, digester)
    try {
      while (dis.read(buffer) != -1) {}
    } finally {
      dis.close()
    }
    digester.digest.map("%02x".format(_)).mkString
  }

  def computeHashFromString(str: String, hashAlgorithm: HashAlgorithm): String = {
    val digest = MessageDigest.getInstance(hashAlgorithm.value)
    val hash = digest.digest(str.getBytes(StandardCharsets.UTF_8))
    new String(Hex.encode(hash))
  }

}
