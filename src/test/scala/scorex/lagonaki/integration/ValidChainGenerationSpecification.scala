package scorex.lagonaki.integration

import akka.pattern.ask
import org.scalatest.{FunSuite, Matchers}
import scorex.account.PublicKeyAccount
import scorex.lagonaki.server.LagonakiApplication
import scorex.lagonaki.{TestingCommons, TransactionTestingCommons}
import scorex.network.peer.PeerManager.GetBlacklistedPeers
import scorex.transaction.BalanceSheet
import scorex.utils.{ScorexLogging, untilTimeout}

import scala.concurrent.Await
import scala.concurrent.duration._

class ValidChainGenerationSpecification extends FunSuite with TestLock with Matchers with ScorexLogging
with TransactionTestingCommons {

  import TestingCommons._

  implicit override lazy val transactionModule = application.transactionModule
  implicit override lazy val consensusModule = application.consensusModule

  val peers = applications.tail
  val app = peers.head
  val state = app.transactionModule.blockStorage.state
  val history = app.transactionModule.blockStorage.history

  def waitGenerationOfBlocks(howMany: Int): Unit = {
    val height = maxHeight()
    untilTimeout(5.minutes, 1.seconds) {
      val heights = peers.map(_.blockStorage.history.height())
      log.info(s"Current heights are: $heights. Waiting for ${height + howMany}")
      heights.foreach(_ should be >= height + howMany)
    }
  }

  def maxHeight(): Int = peers.map(_.blockStorage.history.height()).max

  def cleanTransactionPool(): Unit = untilTimeout(1.second) {
    transactionModule.utxStorage.all().foreach(tx => transactionModule.utxStorage.remove(tx))
    transactionModule.utxStorage.all().size shouldBe 0
  }

  private def blacklistedPeersFor(app: LagonakiApplication) =
    Await.result((app.peerManager ? GetBlacklistedPeers).mapTo[Set[String]], timeout.duration)

  private def checkBlacklists() = applications.foreach { app => assert(blacklistedPeersFor(app).isEmpty)}

  test("generate 13 blocks and synchronize") {
    val genBal = peers.flatMap(a => a.wallet.privateKeyAccounts()).map(acc => app.consensusModule.generatingBalance(acc)).sum
    genBal should be >= (peers.head.transactionModule.InitialBalance / 4)
    genValidTransaction()

    waitGenerationOfBlocks(13)

    val last = peers.head.blockStorage.history.lastBlock
    untilTimeout(5.minutes, 10.seconds, { checkBlacklists() }) {
      peers.head.blockStorage.history.contains(last) shouldBe true
    }
  }


  ignore("Generate block with plenty of transactions") {
    applications.tail.foreach { app =>
      app.wallet.privateKeyAccounts().foreach { acc =>
        if (state.asInstanceOf[BalanceSheet].balance(acc) > 0) {
          genValidTransaction(recepientOpt = accounts.headOption, senderOpt = Some(acc))
        }
      }
    }
    waitGenerationOfBlocks(1)

    val block = untilTimeout(3.minute) {
      stopGenerationForAllPeers()
      transactionModule.clearIncorrectTransactions()
      val toGen = transactionModule.utxStorage.sizeLimit - transactionModule.utxStorage.all().size
      (0 until toGen) foreach (i => genValidTransaction())
      val blocks = application.consensusModule.generateNextBlocks(accounts)(transactionModule)
      blocks.nonEmpty shouldBe true
      blocks.head
    }

    block.isValid shouldBe true
    block.transactions.nonEmpty shouldBe true

    startGenerationForAllPeers()
  }

  ignore("Don't include same transactions twice") {
    //Wait until all peers contain transactions
    val (incl, h) = untilTimeout(1.minutes, 1.seconds) {
      val last = history.lastBlock
      val h = history.heightOf(last).get
      val incl = includedTransactions(last, history)
      require(incl.nonEmpty)
      peers.foreach { p =>
        incl foreach { tx =>
          p.blockStorage.state.included(tx).isDefined shouldBe true
          p.blockStorage.state.included(tx).get should be <= h
        }
      }
      (incl, h)
    }

    stopGenerationForAllPeers()
    cleanTransactionPool()

    incl.foreach(tx => transactionModule.utxStorage.putIfNew(tx))
    transactionModule.utxStorage.all().size shouldBe incl.size
    val tx = genValidTransaction(randomAmnt = false)
    transactionModule.utxStorage.all().size shouldBe incl.size + 1

    startGeneration(applications)

    waitGenerationOfBlocks(2)

    peers.foreach { p =>
      incl foreach { tx =>
        p.blockStorage.state.included(tx).isDefined shouldBe true
        p.blockStorage.state.included(tx).get should be <= h
      }
    }
  }

  ignore("Double spending") {
    val recepient = new PublicKeyAccount(Array.empty)
    val (trans, valid) = untilTimeout(5.seconds) {
      cleanTransactionPool()
      stopGenerationForAllPeers()
      accounts.map(a => state.asInstanceOf[BalanceSheet].balance(a)).exists(_ > 2) shouldBe true
      val trans = accounts.flatMap { a =>
        val senderBalance = state.asInstanceOf[BalanceSheet].balance(a)
        (1 to 2) map (i => transactionModule.createPayment(a, recepient, senderBalance / 2, 1))
      }
      state.validate(trans, blockTime = trans.map(_.timestamp).max).nonEmpty shouldBe true
      val valid = transactionModule.packUnconfirmed()
      valid.nonEmpty shouldBe true
      (trans, valid)
    }
    state.validate(trans, blockTime = trans.map(_.timestamp).max).nonEmpty shouldBe true
    if (valid.size >= trans.size) {
      val balance = state.asInstanceOf[BalanceSheet].balance(trans.head.sender)
      log.error(s"Double spending: ${trans.map(_.json)} | ${valid.map(_.json)} | $balance")
    }
    valid.size should be < trans.size

    waitGenerationOfBlocks(2)

    accounts.foreach(a => state.asInstanceOf[BalanceSheet].balance(a) should be >= 0L)
    trans.exists(tx => state.included(tx).isDefined) shouldBe true // Some of transactions should be included in state
    trans.forall(tx => state.included(tx).isDefined) shouldBe false // But some should not
    startGenerationForAllPeers()
  }

  ignore("Rollback state") {
    def rollback(i: Int = 5) {

      val last = history.lastBlock
      val st1 = state.hash
      val height = history.heightOf(last).get
      val recepient = application.wallet.generateNewAccount()

      //Wait for nonEmpty block
      untilTimeout(1.minute, 1.second) {
        genValidTransaction(recepientOpt = recepient)
        peers.foreach(_.blockStorage.history.height() should be > height)
        history.height() should be > height
        history.lastBlock.transactions.nonEmpty shouldBe true
        peers.foreach(_.transactionModule.blockStorage.history.contains(last))
      }
      state.hash should not be st1
      waitGenerationOfBlocks(0)

      if (peers.forall(p => p.history.contains(last))) {
        stopGenerationForAllPeers()
        peers.foreach { p =>
          p.transactionModule.blockStorage.removeAfter(last.uniqueId)
        }
        peers.foreach { p =>
          p.transactionModule.blockStorage.removeAfter(last.uniqueId)
          p.history.lastBlock.encodedId shouldBe last.encodedId
        }
        state.hash shouldBe st1
        startGenerationForAllPeers()
      } else {
        require(i > 0, "History should contain last block at least sometimes")
        log.warn("History do not contains last block")
        rollback(i - 1)
      }
    }
    rollback()
  }

  private def startGenerationForAllPeers() = {
    log.info("Stop generation for all peers")
    startGeneration(peers)
  }

  private def stopGenerationForAllPeers() = {
    log.info("Stop generation for all peers")
    stopGeneration(peers)
  }
}
