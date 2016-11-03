package scorex.lagonaki.integration

import scala.util.{Failure, Random}
import org.scalatest._
import scorex.account.{Account, PrivateKeyAccount, PublicKeyAccount}
import scorex.crypto.encode.Base58
import scorex.lagonaki.mocks.BlockMock
import scorex.lagonaki.{TestingCommons, TransactionTestingCommons}
import scorex.transaction.state.database.state.AccState
import scorex.transaction.state.wallet.{IssueRequest, TransferRequest}
import scorex.transaction.{AssetAcc, BalanceSheet, FeesStateChange, PaymentTransaction}
import scorex.utils.{ScorexLogging, _}

//TODO: Should be independed
class StoredStateSpecification extends FunSuite with TestLock with Matchers with ScorexLogging
with TransactionTestingCommons with PrivateMethodTester with OptionValues {

  import TestingCommons._

  val peers = applications.tail
  val app = peers.head
  val state = app.transactionModule.blockStorage.state
  val history = app.transactionModule.blockStorage.history
  val acc = accounts.head
  val recipient = application.wallet.privateKeyAccounts().last
  require(acc.address != recipient.address)

  test("invalidate transaction with forged signature in sequence") {
    val amount = state.balance(acc) / 1000
    val ts = System.currentTimeMillis()
    val transactions: Seq[PaymentTransaction] = (1 until 100).map { i =>
      PaymentTransaction(acc, recipient, amount, i, ts + i)
    }
    val txToForge = transactions.head
    val forgedSignature = forgeSignature(txToForge.signature)
    val forgedTransaction = PaymentTransaction(new PublicKeyAccount(txToForge.sender.publicKey), txToForge.recipient,
      txToForge.amount, txToForge.fee, txToForge.timestamp, forgedSignature)

    val transactionsToValidate = transactions :+ forgedTransaction
    val validTransactions = state.validate(transactionsToValidate, blockTime = transactionsToValidate.map(_.timestamp).max)

    validTransactions.count(tx => (tx.id sameElements txToForge.signature) ||
      (tx.id sameElements forgedTransaction.signature)) shouldBe 1
    validTransactions.size should be(transactionsToValidate.size - 1)
  }


  test("balance confirmations") {
    val rec = new PrivateKeyAccount(randomBytes())
    val senderBalance = state.balance(acc)
    state.balance(rec) shouldBe 0L
    senderBalance should be > 100L

    val txs = Seq(transactionModule.createPayment(acc, rec, 5, 1))
    val block = new BlockMock(txs)
    state.processBlock(block)
    state.balance(rec) shouldBe 5L
    state.balanceWithConfirmations(rec, 1) shouldBe 0L

    state.processBlock(new BlockMock(Seq()))
    state.balance(rec) shouldBe 5L
    state.balanceWithConfirmations(rec, 1) shouldBe 5L
    state.balanceWithConfirmations(rec, 2) shouldBe 0L

    val spendingBlock = new BlockMock(Seq(transactionModule.createPayment(rec, acc, 2, 1)))
    state.processBlock(spendingBlock)
    state.balance(rec) shouldBe 2L
    state.balanceWithConfirmations(rec, 1) shouldBe 2L

    state.processBlock(new BlockMock(Seq(transactionModule.createPayment(acc, rec, 5, 1))))
    state.balance(rec) shouldBe 7L
    state.balanceWithConfirmations(rec, 3) shouldBe 2L


    state.processBlock(new BlockMock(Seq(transactionModule.createPayment(acc, rec, 5, 1))))
    state.balance(rec) shouldBe 12L
    state.balanceWithConfirmations(rec, 1) shouldBe 7L
    state.balanceWithConfirmations(rec, 2) shouldBe 2L
    state.balanceWithConfirmations(rec, 4) shouldBe 2L
    state.balanceWithConfirmations(rec, 5) shouldBe 0L
  }

  test("private methods") {
    val testAdd = "aPFwzRp5TXCzi6DSuHmpmbQunopXRuxLk"
    val testAcc = new Account(testAdd)
    val applyMethod = PrivateMethod[Unit]('applyChanges)
    state.balance(testAcc) shouldBe 0
    val tx = transactionModule.createPayment(acc, testAcc, 1, 1)
    state invokePrivate applyMethod(Map(AssetAcc(testAcc, None) ->(AccState(2L), Seq(FeesStateChange(1L), tx))))
    state.balance(testAcc) shouldBe 2
    state.included(tx).value shouldBe state.stateHeight
    state invokePrivate applyMethod(Map(AssetAcc(testAcc, None) ->(AccState(0L), Seq(tx))))
  }

  test("validate single transaction") {
    val senderBalance = state.balance(acc)
    senderBalance should be > 0L
    val nonValid = transactionModule.createPayment(acc, recipient, senderBalance, 1)
    state.isValid(nonValid, nonValid.timestamp) shouldBe false

    val valid = transactionModule.createPayment(acc, recipient, senderBalance - 1, 1)
    state.isValid(valid, valid.timestamp) shouldBe true
  }

  test("double spending") {
    val senderBalance = state.balance(acc)
    val doubleSpending = (1 to 2).map(i => transactionModule.createPayment(acc, recipient, senderBalance / 2, 1))
    doubleSpending.foreach(t => state.isValid(t, t.timestamp) shouldBe true)
    state.isValid(doubleSpending, blockTime = doubleSpending.map(_.timestamp).max) shouldBe false
    state.validate(doubleSpending, blockTime = doubleSpending.map(_.timestamp).max).size shouldBe 1
    state.processBlock(new BlockMock(doubleSpending)) should be('failure)
  }

  test("many transactions") {
    val senderBalance = state.balance(acc)

    val receipements = Seq(
      new PrivateKeyAccount(Array(34.toByte, 1.toByte)),
      new PrivateKeyAccount(Array(1.toByte, 23.toByte))
    )

    val issueAssetTx = transactionModule.issueAsset(IssueRequest(acc.address, "AAAAB", "BBBBB", 1000000, 2, reissuable = false, 100000000), application.wallet).get
    state.processBlock(new BlockMock(Seq(issueAssetTx))) should be('success)
    val assetId = Some(Base58.encode(issueAssetTx.assetId))

    val txs = receipements.flatMap(r => Seq.fill(10)(transactionModule.transferAsset(TransferRequest(assetId, None, 10, 1, acc.address, "123", r.address), application.wallet).get))

    val shuffledTxs = Random.shuffle(txs)

    state.processBlock(new BlockMock(shuffledTxs)) should be('success)

    receipements.foreach(r => state.assetBalance(AssetAcc(r, Some(issueAssetTx.assetId))) should be (100))

    state.assetBalance(AssetAcc(acc, Some(issueAssetTx.assetId))) should be (999800)
  }

  test("validate plenty of transactions") {
    val trans = Seq.fill(transactionModule.utxStorage.sizeLimit)(genValidTransaction())
    profile(state.validate(trans, blockTime = trans.map(_.timestamp).max)) should be < 1000L
    state.validate(trans, blockTime = trans.map(_.timestamp).max).size should be <= trans.size
  }

  test("included") {
    val incl = includedTransactions(history.lastBlock, history)
    incl.nonEmpty shouldBe true
    incl.forall(t => state.included(t).isDefined) shouldBe true

    val newTx = genValidTransaction()
    state.included(newTx).isDefined shouldBe false
  }

  test("last transaction of account one block behind") {
    val amount = state.balance(acc) / 1000
    val tx1 = transactionModule.createPayment(acc, recipient, amount, 1)
    state.isValid(tx1, tx1.timestamp) shouldBe true
    val tx2 = transactionModule.createPayment(acc, recipient, amount, 2)
    state.isValid(tx2, tx2.timestamp) shouldBe true

    val block = new BlockMock(Seq(tx1, tx2))
    state.processBlock(block)

    val result = state.lastAccountLagonakiTransaction(acc)
    result.isDefined shouldBe true
    result.get shouldBe tx2
  }

  test("last transaction of account few blocks behind") {
    val amount = state.balance(acc) / 1000
    val tx1 = transactionModule.createPayment(acc, recipient, amount, 1)
    val tx2 = transactionModule.createPayment(acc, recipient, amount, 2)
    val block1 = new BlockMock(Seq(tx2, tx1))
    state.processBlock(block1)

    val tx3 = transactionModule.createPayment(recipient, acc, amount / 2, 3)
    val tx4 = transactionModule.createPayment(recipient, acc, amount / 2, 4)
    val block2 = new BlockMock(Seq(tx3, tx4))
    state.processBlock(block2)

    val result1 = state.lastAccountLagonakiTransaction(acc)
    result1.isDefined shouldBe true
    result1.get shouldBe tx2

    val result2 = state.lastAccountLagonakiTransaction(recipient)
    result2.isDefined shouldBe true
    result2.get shouldBe tx4
  }

}
