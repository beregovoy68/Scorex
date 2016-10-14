package scorex.transaction

import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.PropertyChecks
import scorex.account.{PrivateKeyAccount, PublicKeyAccount}
import scorex.transaction.assets.exchange.{AssetPair, Order, OrderType}
import scorex.utils.{ByteArray, NTP}

class OrderTransactionSpecification extends PropSpec with PropertyChecks with Matchers with TransactionGen {

  property("Order transaction serialization roundtrip") {
    forAll(orderGenerator) { order: Order =>
      val recovered = Order.parseBytes(order.bytes).get
      recovered.bytes shouldEqual order.bytes
    }
  }

  property("Order generator should generate valid orders") {
    forAll(orderGenerator) { order: Order =>
      order.isValid(NTP.correctedTime()) shouldBe true
    }
  }

  property("Order timestamp validation") {
    forAll(invalidOrderGenerator) { order: Order =>
      val isValid = order.isValid(NTP.correctedTime())
      val time = NTP.correctedTime()
      whenever(order.maxTimestamp < time || order.maxTimestamp > time + Order.MaxLiveTime) {
        isValid shouldBe false
      }
    }
  }

  property("Order amount validation") {
    forAll(invalidOrderGenerator) { order: Order =>
      whenever(order.amount <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe false
      }
    }
  }

  property("Order matcherFee validation") {
    forAll(invalidOrderGenerator) { order: Order =>
      whenever(order.matcherFee <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe false
      }
    }
  }

  property("Order price validation") {
    forAll(invalidOrderGenerator) { order: Order =>
      whenever(order.price <= 0) {
        order.isValid(NTP.correctedTime()) shouldBe false
      }
    }
  }

  property("Order signature validation") {
    forAll(orderGenerator, bytes32gen) { (order: Order, bytes: Array[Byte]) =>
      order.isValid(NTP.correctedTime()) shouldBe true
      order.copy(sender = new PublicKeyAccount(bytes)).isValid(NTP.correctedTime()) shouldBe false
      order.copy(matcher = new PublicKeyAccount(bytes)).isValid(NTP.correctedTime()) shouldBe false
      order.copy(spendAssetId = Array(0: Byte) ++ order.spendAssetId).isValid(NTP.correctedTime()) shouldBe false
      order.copy(receiveAssetId = Array(0: Byte) ++ order.receiveAssetId).isValid(NTP.correctedTime()) shouldBe false
      order.copy(price = order.price + 1).isValid(NTP.correctedTime()) shouldBe false
      order.copy(amount = order.amount + 1).isValid(NTP.correctedTime()) shouldBe false
      order.copy(maxTimestamp = order.maxTimestamp + 1).isValid(NTP.correctedTime()) shouldBe false
      order.copy(matcherFee = order.matcherFee + 1).isValid(NTP.correctedTime()) shouldBe false
      order.copy(signature = bytes ++ bytes).isValid(NTP.correctedTime()) shouldBe false
    }
  }

  property("Buy and Sell orders") {
    forAll(accountGen, accountGen, assetPairGen, positiveLongGen, positiveLongGen, maxTimeGen) {
      (sender: PrivateKeyAccount, matcher: PrivateKeyAccount, pair: AssetPair,
       price: Long, amount: Long, maxTime: Long) =>
        val buy = Order.buy(sender, matcher, pair, price, amount, maxTime, price)
        buy.orderType shouldBe OrderType.BUY

        val sell = Order.sell(sender, matcher, pair, price, amount, maxTime, price)
        sell.orderType shouldBe OrderType.SELL
    }
  }

  property("AssetPair test") {
    forAll(bytes32gen, bytes32gen) { (assetA: Array[Byte], assetB: Array[Byte]) =>
      whenever(!(assetA sameElements assetB)) {
        val pair = AssetPair(assetA, assetB)
        ByteArray.compare(pair.first, pair.second) should be < 0
      }
    }
  }


}
