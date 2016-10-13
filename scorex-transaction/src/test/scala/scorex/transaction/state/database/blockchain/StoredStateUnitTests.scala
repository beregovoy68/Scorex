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


  property("Validate transfer with too big amount") {

    val recipient = new PrivateKeyAccount("recipient account".getBytes)

    forAll(positiveLongGen, positiveLongGen) { (balance: Long, fee: Long) =>
      whenever(balance > fee) {
        val assetAcc = AssetAcc(testAcc, None)

        //set some balance
        val genes = GenesisTransaction(testAcc, balance, 0)
        state.applyChanges(state.calcNewBalances(Seq(genes), Map()))
        state.assetBalance(assetAcc) shouldBe balance

        //valid transfer
        val tx = TransferTransaction.create(None, testAcc, recipient, balance - fee, System.currentTimeMillis(),
          None, fee, Array())
        state.isValid(tx) shouldBe true

        //transfer asset
        val invalidtx = TransferTransaction.create(None, testAcc, recipient, balance, System.currentTimeMillis(),
          None, fee, Array())
        state.isValid(invalidtx) shouldBe false

        state invokePrivate applyChanges(Map(testAssetAcc ->(AccState(0L), Seq(tx))))

      }
    }
  }

  property("Transfer asset") {
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

  property("Reissue asset") {
    forAll(issueReissueGenerator) { pair =>
      val issueTx: IssueTransaction = pair._1
      val reissueTx: ReissueTransaction = pair._2
      val assetAcc = AssetAcc(issueTx.sender, Some(issueTx.assetId))

      state.applyChanges(state.calcNewBalances(Seq(issueTx), Map()))

      state.isValid(reissueTx, Int.MaxValue) shouldBe issueTx.reissuable
    }
  }

  property("Issue asset") {
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

  property("Applying transactions") {
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

  property("Reopen state") {
    val balance = 1234L
    state invokePrivate applyChanges(Map(testAssetAcc ->(AccState(balance), Seq(FeesStateChange(balance)))))
    state.balance(testAcc) shouldBe balance
    db.close()

    val state2 = new StoredState(new MVStore.Builder().fileName(stateFile).compress().open())
    state2.balance(testAcc) shouldBe balance
    state2 invokePrivate applyChanges(Map(testAssetAcc ->(AccState(0L), Seq())))
  }

}
