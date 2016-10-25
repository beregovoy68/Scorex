package scorex.transaction.assets.exchange

import org.scalatest.prop.PropertyChecks
import org.scalatest.{Matchers, PropSpec}
import play.api.libs.json._
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.encode.Base58
import scorex.transaction.TransactionGen
import OrderJson._

class OrderJsonSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  property("Read Order from json") {
    val pk = new PrivateKeyAccount("123".getBytes)
    val pubKeyStr = Base58.encode(pk.publicKey)

    val json = Json.parse(s"""
        {
          "sender": "${pubKeyStr}",
          "matcher": "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ",
          "spendAssetId": "29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b",
          "receiveAssetId": "GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44",
          "amount": 0,
          "matcherFee": 0,
          "price": 0,
          "maxTimestamp": 0,
          "signature": "signature"
        } """)

    json.validate[Order] match {
      case e: JsError =>
        fail("Error: " + JsError.toJson(e).toString())
      case s: JsSuccess[Order] =>
        val o = s.get
        o.sender shouldBe new PublicKeyAccount(pk.publicKey)
        o.matcher shouldBe new PublicKeyAccount(Base58.decode("DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ").get)
        o.spendAssetId shouldBe Base58.decode("29ot86P3HoUZXH1FCoyvff7aeZ3Kt7GqPwBWXncjRF2b").get
        o.receiveAssetId shouldBe Base58.decode("GEtBMkg419zhDiYRXKwn2uPcabyXKqUqj4w3Gcs1dq44").get
        o.price shouldBe 0
        o.amount shouldBe 0
        o.matcherFee shouldBe 0
        o.maxTimestamp shouldBe 0
        o.signature shouldBe Base58.decode("signature").get

    }

  }

  property("Read Order without sender and matcher PublicKey") {
    val json = Json.parse(
      """
        {
          "sender": " ",
          "spendAssetId": "string",
          "receiveAssetId": "string",
          "amount": 0,
          "matcherFee": 0,
          "price": 0,
          "maxTimestamp": 0,
          "signature": "signature"
        } """)

    json.validate[Order] match {
      case e: JsError =>
        val paths = e.errors.map(_._1)
        paths should contain allOf (JsPath \ "matcher", JsPath \ "sender")
      case s: JsSuccess[Order] =>
        fail("Should be JsError")
    }
  }

  val base58Str = "DZUxn4pC7QdYrRqacmaAJghatvnn1Kh1mkE2scZoLuGJ"
  val json: JsValue = Json.parse(s"""
    {
      "sender": "${base58Str}",
      "wrong_sender": "0abcd",
      "wrong_long": "12e",
      "publicKey": "${base58Str}",
      "wrong_publicKey": "0abcd"
    }
    """)

  property("Json Reads Base58") {
    val sender = (json \ "sender").as[Array[Byte]]
    sender shouldBe Base58.decode(base58Str).get

    (json \ "wrong_sender").validate[Array[Byte]] match {
      case e: JsError =>
        println("Errors: " + JsError.toJson(e).toString())
      case _ => fail("Should be JsError")
    }
  }

  property("Json Reads PublicKeyAccount") {
    val publicKey = (json \ "publicKey").as[PublicKeyAccount]
    publicKey.bytes shouldBe new PublicKeyAccount(Base58.decode(base58Str).get).bytes

    (json \ "wrong_publicKey").validate[PublicKeyAccount] match {
      case e: JsError =>
        println("Errors: " + JsError.toJson(e).toString())
        e.errors.head._2.head.message shouldBe "error.incorrect.publicKeyAccount"
      case _ => fail("Should be JsError")
    }
  }

  property("Parse signed Order") {
    forAll { x: (Order, PrivateKeyAccount) =>
      val (order, pk) = x
      val json = order.json
      json.validate[Order] match {
        case e: JsError =>
          fail("Error: " + JsError.toJson(e).toString())
        case s: JsSuccess[Order] =>
          val o = s.get
          o.signatureValid should be(true)
      }
    }
  }
}
