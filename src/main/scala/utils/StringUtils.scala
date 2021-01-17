package utils

/**
 * utils
 *
 * @author colin
 * @version 1.0, 2021/1/17
 * @since 0.4.1
 */
object StringUtils {

  private val phoneRegex = "^(?:(?:\\+|00)86)?1\\d{10}$".r
  def isValidCnPhoneNumberFormat(str: String): Boolean = {
    phoneRegex.matches(str)
  }

  private val emailRegex = "(?:[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*|\"(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21\\x23-\\x5b\\x5d-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])*\")@(?:(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?|\\[(?:(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9]))\\.){3}(?:(2(5[0-5]|[0-4][0-9])|1[0-9][0-9]|[1-9]?[0-9])|[a-z0-9-]*[a-z0-9]:(?:[\\x01-\\x08\\x0b\\x0c\\x0e-\\x1f\\x21-\\x5a\\x53-\\x7f]|\\\\[\\x01-\\x09\\x0b\\x0c\\x0e-\\x7f])+)\\])".r
  def isValidEmailFormat(str: String): Boolean = {
    emailRegex.matches(str)
  }

  private val usernameRegex = "^\\w[\\w|\\.|\\-]{0,50}\\w$".r
  def isValidUsernameFormat(str: String): Boolean = {
    this.usernameRegex.matches(str)
  }

}
