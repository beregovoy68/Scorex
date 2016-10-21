package scorex.transaction.state.database.blockchain

import java.io.File

import org.h2.mvstore.MVStore
import org.scalacheck.Gen
import org.scalatest._
import org.scalatest.prop.{GeneratorDrivenPropertyChecks, PropertyChecks}
import scorex.account.PrivateKeyAccount
import scorex.transaction._
import scorex.transaction.assets.{IssueTransaction, ReissueTransaction, TransferTransaction}
import scorex.transaction.state.database.state._

import scala.util.Random

import scorex.transaction.assets.exchange.{Order, OrderMatch}

class StoredStateUnitTests extends PropSpec with PropertyChecks with GeneratorDrivenPropertyChecks with Matchers
with PrivateMethodTester with OptionValues with TransactionGen {

  val folder = "/tmp/scorex/test/"
  new File(folder).mkdirs()
  val stateFile = folder + "state.dat"
  new File(stateFile).delete()

  val db = new MVStore.Builder().fileName(stateFile).compress().open()
  val state = new StoredState(db)
  val testAcc = new PrivateKeyAccount(scorex.utils.randomBytes(64))
  val testAssetAcc = AssetAcc(testAcc, None)
  val testAdd = testAcc.address

  val applyChanges = PrivateMethod[Unit]('applyChanges)
  val calcNewBalances = PrivateMethod[Unit]('calcNewBalances)

  property("Transaction seq Long overflow") {
    val TxN: Int = 12
    val InitialBalance: Long = Long.MaxValue / 8
    state.applyChanges(Map(testAssetAcc ->(AccState(InitialBalance), List(FeesStateChange(InitialBalance)))))
    state.balance(testAcc) shouldBe InitialBalance

    val transfers = (0 until TxN).map { i => genTransfer(InitialBalance - 1, 1) }
    transfers.foreach(tx => state.isValid(tx) shouldBe true)

    state.isValid(transfers) shouldBe false

    state.applyChanges(Map(testAssetAcc ->(AccState(0L), List())))
  }

  property("Amount + fee Long overflow") {
    val InitialBalance: Long = 100
    state.applyChanges(Map(testAssetAcc ->(AccState(InitialBalance), List(FeesStateChange(InitialBalance)))))
    state.balance(testAcc) shouldBe InitialBalance

    val transferTx = genTransfer(Long.MaxValue, Long.MaxValue)
    (-transferTx.fee - transferTx.amount) should be > 0L
    state.isValid(transferTx) shouldBe false

    val paymentTx = genTransfer(Long.MaxValue, Long.MaxValue)
    (-paymentTx.fee - paymentTx.amount) should be > 0L
    state.isValid(paymentTx) shouldBe false

    state.applyChanges(Map(testAssetAcc ->(AccState(0L), List())))

  }


  private def withRollbackTest(test: => Unit): Unit = {
    val startedState = state.stateHeight
    val h = state.hash
    test
    state.rollbackTo(startedState)
    h should be(state.hash)
  }

  property("Validate transfer with too big amount") {
    val recipient = new PrivateKeyAccount("recipient account".getBytes)

    forAll(positiveLongGen, positiveLongGen) { (balance: Long, fee: Long) =>
      whenever(balance > fee) {
        val assetAcc = AssetAcc(testAcc, None)

        //set some balance
        val genes = GenesisTransaction(testAcc, balance, 0)
        state.applyChanges(Map(testAssetAcc ->(AccState(genes.amount), List(genes))))
        state.assetBalance(assetAcc) shouldBe balance

        //valid transfer
        val tx = TransferTransaction.create(None, testAcc, recipient, balance - fee, System.currentTimeMillis(),
          None, fee, Array())
        state.isValid(tx) shouldBe true

        //transfer asset
        val invalidtx = TransferTransaction.create(None, testAcc, recipient, balance, System.currentTimeMillis(),
          None, fee, Array())
        state.isValid(invalidtx) shouldBe false

        state.applyChanges(Map(testAssetAcc ->(AccState(0L), List(tx))))
      }
    }
  }

  property("Transfer asset") {
    withRollbackTest {
      forAll(transferGenerator) { tx: TransferTransaction =>
        val senderAmountAcc = AssetAcc(tx.sender, tx.assetId)
        val senderFeeAcc = AssetAcc(tx.sender, tx.feeAsset)
        val recipientAmountAcc = AssetAcc(tx.recipient, tx.assetId)

        val senderAmountBalance = state.assetBalance(senderAmountAcc)
        val senderFeeBalance = state.assetBalance(senderFeeAcc)
        val recipientAmountBalance = state.assetBalance(recipientAmountAcc)

        state.applyChanges(state.calcNewBalances(Seq(tx), Map()))

        val newSenderAmountBalance = state.assetBalance(senderAmountAcc)
        val newSenderFeeBalance = state.assetBalance(senderFeeAcc)
        val newRecipientAmountBalance = state.assetBalance(recipientAmountAcc)

        newRecipientAmountBalance shouldBe (recipientAmountBalance + tx.amount)

        if (tx.sameAssetForFee) {
          newSenderAmountBalance shouldBe newSenderFeeBalance
          newSenderAmountBalance shouldBe (senderAmountBalance - tx.amount - tx.fee)
        } else {
          newSenderAmountBalance shouldBe senderAmountBalance - tx.amount
          newSenderFeeBalance shouldBe senderFeeBalance - tx.fee
        }
      }
    }
  }

  property("Old style reissue asset") {
    forAll(issueReissueGenerator) { pair =>
      val issueTx: IssueTransaction = pair._1
      val issueTx2: IssueTransaction = pair._2
      val assetAcc = AssetAcc(issueTx.sender, Some(issueTx.assetId))

      state.applyChanges(state.calcNewBalances(Seq(issueTx), Map()))

      state.isValid(issueTx2, Int.MaxValue) shouldBe false
    }
  }


  def getBalances(a: AssetAcc*): Seq[Long] = {
    a.map(state.assetBalance(_))
  }

  property("Order matching") {
    forAll { x: (OrderMatch, PrivateKeyAccount) =>
      val (om, matcher) = x

      val pair = om.buyOrder.assetPair
      val buyer = om.buyOrder.sender
      val seller = om.sellOrder.sender

      val buyerAcc1 = AssetAcc(buyer, Some(pair.first))
      val buyerAcc2 = AssetAcc(buyer, Some(pair.second))
      val sellerAcc1 = AssetAcc(seller, Some(pair.first))
      val sellerAcc2 = AssetAcc(seller, Some(pair.second))
      val buyerFeeAcc = AssetAcc(buyer, None)
      val sellerFeeAcc = AssetAcc(seller, None)
      val matcherFeeAcc =  AssetAcc(om.buyOrder.matcher, None)

      val Seq(buyerBal1, buyerBal2, sellerBal1, sellerBal2, buyerFeeBal, sellerFeeBal, matcherFeeBal) =
        getBalances(buyerAcc1, buyerAcc2, sellerAcc1, sellerAcc2, buyerFeeAcc, sellerFeeAcc, matcherFeeAcc)

      state.applyChanges(state.calcNewBalances(Seq(om), Map()))

      val Seq(newBuyerBal1, newBuyerBal2, newSellerBal1, newSellerBal2, newBuyerFeeBal, newSellerFeeBal, newMatcherFeeBal) =
        getBalances(buyerAcc1, buyerAcc2, sellerAcc1, sellerAcc2, buyerFeeAcc, sellerFeeAcc, matcherFeeAcc)

      newBuyerBal1 should be (buyerBal1 + om.amount)
      newBuyerBal2 should be (buyerBal2 - BigInt(om.amount)*Order.PriceConstant/om.price)
      newSellerBal1 should be (sellerBal1 - om.amount)
      newSellerBal2 should be (sellerBal2 + BigInt(om.amount)*Order.PriceConstant/om.price)
      newBuyerFeeBal should be (buyerFeeBal - om.buyMatcherFee)
      newSellerFeeBal should be (sellerFeeBal - om.sellMatcherFee)
      newMatcherFeeBal should be (matcherFeeBal + om.buyMatcherFee + om.sellMatcherFee - om.fee)
    }
  }

  property("Reissue asset") {
    withRollbackTest {
      forAll(issueReissueGenerator) { pair =>
        val issueTx: IssueTransaction = pair._1
        val reissueTx: ReissueTransaction = pair._3
        val assetAcc = AssetAcc(issueTx.sender, Some(issueTx.assetId))

        state.applyChanges(state.calcNewBalances(Seq(issueTx), Map()))

        state.isValid(reissueTx, Int.MaxValue) shouldBe issueTx.reissuable
      }
    }
  }

  property("Issue asset") {
    withRollbackTest {
      val startedState = state.stateHeight
      val h = state.hash
      forAll(issueGenerator) { issueTx: IssueTransaction =>
        val assetAcc = AssetAcc(issueTx.sender, Some(issueTx.assetId))
        val networkAcc = AssetAcc(issueTx.sender, None)

        //set some balance
        val genes = GenesisTransaction(issueTx.sender, issueTx.fee + Random.nextInt, issueTx.timestamp - 1)
        state.applyChanges(state.calcNewBalances(Seq(genes), Map()))
        state.assetBalance(assetAcc) shouldBe 0
        state.assetBalance(networkAcc) shouldBe genes.amount
        state.balance(issueTx.sender) shouldBe genes.amount

        //issue asset
        state.assetBalance(assetAcc) shouldBe 0
        val newBalances = state.calcNewBalances(Seq(issueTx), Map())
        state.applyChanges(newBalances)
        state.assetBalance(assetAcc) shouldBe issueTx.quantity
        state.assetBalance(networkAcc) shouldBe (genes.amount - issueTx.fee)
      }
    }
  }

  property("accountTransactions returns IssueTransactions") {
    forAll(issueGenerator) { issueTx: IssueTransaction =>
      val assetAcc = AssetAcc(issueTx.sender, Some(issueTx.assetId))
      val networkAcc = AssetAcc(issueTx.sender, None)
      //set some balance
      val genes = GenesisTransaction(issueTx.sender, issueTx.fee + Random.nextInt, issueTx.timestamp - 1)
      state.applyChanges(state.calcNewBalances(Seq(genes), Map()))
      //issue asset
      val newBalances = state.calcNewBalances(Seq(issueTx), Map())
      state.applyChanges(newBalances)
      state.accountTransactions(issueTx.sender).count(_.isInstanceOf[IssueTransaction]) shouldBe 1
    }
  }

  property("accountTransactions returns TransferTransactions if fee in base token") {
    forAll(transferGenerator) { t: TransferTransaction =>
      val tx = t.copy(feeAsset = None)
      val senderAmountAcc = AssetAcc(tx.sender, tx.assetId)
      val senderFeeAcc = AssetAcc(tx.sender, tx.feeAsset)
      val recipientAmountAcc = AssetAcc(tx.recipient, tx.assetId)
      state.applyChanges(state.calcNewBalances(Seq(tx), Map()))
      state.accountTransactions(tx.sender).count(_.isInstanceOf[TransferTransaction]) shouldBe 1
    }
  }

  property("Applying transactions") {
    withRollbackTest {
      val testAssetAcc = AssetAcc(testAcc, None)
      forAll(paymentGenerator, Gen.posNum[Long]) { (tx: PaymentTransaction,
                                                    balance: Long) =>
        state.balance(testAcc) shouldBe 0
        state.assetBalance(testAssetAcc) shouldBe 0
        state invokePrivate applyChanges(Map(testAssetAcc ->(AccState(balance), Seq(FeesStateChange(balance), tx, tx))))
        state.balance(testAcc) shouldBe balance
        state.assetBalance(testAssetAcc) shouldBe balance
        state.included(tx).value shouldBe state.stateHeight
        state invokePrivate applyChanges(Map(testAssetAcc ->(AccState(0L), Seq(tx))))
      }
    }
  }

  property("Reopen state") {
    val balance = 1234L
    state invokePrivate applyChanges(Map(testAssetAcc ->(AccState(balance), Seq(FeesStateChange(balance)))))
    state.balance(testAcc) shouldBe balance
    db.close()

    val state2 = new StoredState(new MVStore.Builder().fileName(stateFile).compress().open())
    state2.balance(testAcc) shouldBe balance
    state2 invokePrivate applyChanges(Map(testAssetAcc ->(AccState(0L), Seq())))
  }

  private var txTime: Long = 0

  private def getTimestamp: Long = synchronized {
    txTime = Math.max(System.currentTimeMillis(), txTime + 1)
    txTime
  }

  def genTransfer(amount: Long, fee: Long): TransferTransaction = {
    val recipient = new PrivateKeyAccount(scorex.utils.randomBytes())
    TransferTransaction.create(None, testAcc, recipient: Account, amount, getTimestamp, None, fee, Array())
  }

  def genPayment(amount: Long, fee: Long): PaymentTransaction = {
    val recipient = new PrivateKeyAccount(scorex.utils.randomBytes())
    val time = getTimestamp
    val sig = PaymentTransaction.generateSignature(testAcc, recipient, amount, fee, time)
    new PaymentTransaction(testAcc, recipient, amount, fee, time, sig)
  }

}
