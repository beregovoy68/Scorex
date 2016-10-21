package scorex.transaction.assets

import com.google.common.primitives.{Bytes, Longs}
import play.api.libs.json.{JsObject, Json}
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.EllipticCurveImpl
import scorex.crypto.encode.Base58
import scorex.serialization.Deser
import scorex.transaction.TypedTransaction.TransactionType
import scorex.transaction._

import scala.util.Try

case class ReissueTransaction(sender: PublicKeyAccount,
                              assetId: Array[Byte],
                              quantity: Long,
                              reissuable: Boolean,
                              fee: Long,
                              timestamp: Long,
                              signature: Array[Byte]) extends AssetIssuance {

  override val transactionType: TransactionType.Value = TransactionType.ReissueTransaction

  lazy val toSign: Array[Byte] = Bytes.concat(sender.publicKey, assetId, Longs.toByteArray(quantity),
    if (reissuable) Array(1: Byte) else Array(0: Byte), Longs.toByteArray(fee), Longs.toByteArray(timestamp))

  override lazy val json: JsObject = Json.obj(
    "type" -> transactionType.id,
    "id" -> Base58.encode(id),
    "sender" -> sender.address,
    "senderPublicKey" -> Base58.encode(sender.publicKey),
    "assetId" -> Base58.encode(assetId),
    "quantity" -> quantity,
    "reissuable" -> reissuable,
    "fee" -> fee,
    "timestamp" -> timestamp,
    "signature" -> Base58.encode(signature)
  )

  override val assetFee: (Option[AssetId], Long) = (None, fee)
  override lazy val balanceChanges: Seq[BalanceChange] =
    Seq(BalanceChange(AssetAcc(sender, Some(assetId)), quantity),
      BalanceChange(AssetAcc(sender, assetFee._1), -assetFee._2))

  override lazy val bytes: Array[Byte] = Bytes.concat(Array(transactionType.id.toByte), signature, toSign)

  def validate: ValidationResult.Value =
    if (!Account.isValid(sender)) {
      ValidationResult.InvalidAddress
    } else if (quantity <= 0) {
      ValidationResult.NegativeAmount
    } else if (fee <= 0) {
      ValidationResult.InsufficientFee
    } else if (!signatureValid) {
      ValidationResult.InvalidSignature
    } else ValidationResult.ValidateOke

}


object ReissueTransaction extends Deser[ReissueTransaction] {

  override def parseBytes(bytes: Array[Byte]): Try[ReissueTransaction] = Try {
    require(bytes.head == TransactionType.ReissueTransaction.id)
    parseTail(bytes.tail).get
  }

  def parseTail(bytes: Array[Byte]): Try[ReissueTransaction] = Try {
    import EllipticCurveImpl._
    val signature = bytes.slice(0, SignatureLength)
    val sender = new PublicKeyAccount(bytes.slice(SignatureLength, SignatureLength + KeyLength))
    val assetId = bytes.slice(SignatureLength + KeyLength, SignatureLength + KeyLength + AssetIdLength)
    val quantityStart = SignatureLength + KeyLength + AssetIdLength

    val quantity = Longs.fromByteArray(bytes.slice(quantityStart, quantityStart + 8))
    val reissuable = bytes.slice(quantityStart + 8, quantityStart + 9).head == (1: Byte)
    val fee = Longs.fromByteArray(bytes.slice(quantityStart + 9, quantityStart + 17))
    val timestamp = Longs.fromByteArray(bytes.slice(quantityStart + 17, quantityStart + 25))
    ReissueTransaction(sender, assetId, quantity, reissuable, fee, timestamp, signature)
  }

  def create(sender: PrivateKeyAccount,
             assetId: Array[Byte],
             quantity: Long,
             reissuable: Boolean,
             fee: Long,
             timestamp: Long): ReissueTransaction = {
    val unsigned = ReissueTransaction(sender, assetId, quantity, reissuable, fee, timestamp, null)
    val sig = EllipticCurveImpl.sign(sender, unsigned.toSign)
    unsigned.copy(signature = sig)
  }
}