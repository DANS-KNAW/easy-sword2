package nl.knaw.dans.easy.sword2

package object properties {

  case class DepositDoesNotExist(depositId: DepositId) extends Exception(s"Deposit $depositId does not exist")
  case class NoStateForDeposit(depositId: DepositId) extends Exception(s"No state available for deposit $depositId")
  case class NoContentTypeForDeposit(depositId: DepositId) extends Exception(s"No content type available for deposit $depositId")
}
