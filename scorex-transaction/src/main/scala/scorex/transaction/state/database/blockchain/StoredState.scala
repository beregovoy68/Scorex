package scorex.transaction.state.database.blockchain

import org.h2.mvstore.{MVMap, MVStore}
import play.api.libs.json.{JsNumber, JsObject}
import scorex.account.Account
import scorex.block.Block
import scorex.crypto.encode.Base58
import scorex.crypto.hash.FastCryptographicHash
import scorex.settings.WavesHardForkParameters
import scorex.transaction._
import scorex.transaction.assets.{AssetIssuance, IssueTransaction, ReissueTransaction, TransferTransaction}
import scorex.transaction.state.database.state._
import scorex.utils.{LogMVMapBuilder, NTP, ScorexLogging}
import scala.concurrent.duration._

import scala.collection.JavaConversions._
import scala.util.{Failure, Success, Try}
import scala.util.Try
import scala.util.control.NonFatal


/** Store current balances only, and balances changes within effective balance depth.
  * Store transactions for selected accounts only.
  * If no filename provided, blockchain lives in RAM (intended for tests only).
  *
  * Use apply method of StoredState object to create new instance
  */
class StoredState(db: MVStore, settings: WavesHardForkParameters) extends LagonakiState with ScorexLogging {


  val HeightKey = "height"
  val DataKey = "dataset"
  val LastStates = "lastStates"
  val IncludedTx = "includedTx"
  //todo put all transactions in the same map and use links to it
  val AllTxs = "IssueTxs"
  val ReissueIndex = "reissuableFlag"

  if (db.getStoreVersion > 0) db.rollback()

  private def accountChanges(key: Address): MVMap[Int, Row] = db.openMap(key.toString, new LogMVMapBuilder[Int, Row])

  private val lastStates: MVMap[Address, Int] = db.openMap(LastStates, new LogMVMapBuilder[Address, Int])

  /**
    * Transaction Signature -> Block height Map
    */
  private val includedTx: MVMap[Array[Byte], Int] = db.openMap(IncludedTx, new LogMVMapBuilder[Array[Byte], Int])

  /**
    * Transaction ID -> serialized transaction
    */
  private val transactionsMap: MVMap[Array[Byte], Array[Byte]] =
    db.openMap(AllTxs, new LogMVMapBuilder[Array[Byte], Array[Byte]])

  /**
    * Transaction Signature -> reissuable flag
    */
  private val reissuableIndex: MVMap[Array[Byte], Boolean] =
    db.openMap(ReissueIndex, new LogMVMapBuilder[Array[Byte], Boolean])

  private val heightMap: MVMap[String, Int] = db.openMap(HeightKey, new LogMVMapBuilder[String, Int])

  if (Option(heightMap.get(HeightKey)).isEmpty) heightMap.put(HeightKey, 0)

  def stateHeight: Int = heightMap.get(HeightKey)

  private def setStateHeight(height: Int): Unit = heightMap.put(HeightKey, height)

  private val accountAssetsMap: MVMap[String, Set[String]] = db.openMap("", new LogMVMapBuilder[String, Set[String]])

  private def updateAccountAssets(address: Address, assetId: Option[AssetId]): Unit = {
    if (assetId.isDefined) {
      val asset = Base58.encode(assetId.get)
      val assets = Option(accountAssetsMap.get(address)).getOrElse(Set.empty[String])
      accountAssetsMap.put(address, assets + asset)
    }
  }

  private def getAccountAssets(address: Address): Set[String] =
    Option(accountAssetsMap.get(address)).getOrElse(Set.empty[String])

  private def isIssuerOfAsset(address: Address, assetId: AssetId): Boolean = {
    val issueTransaction = Option(transactionsMap.get(assetId)).flatMap(b => IssueTransaction.parseBytes(b).toOption)

    if (issueTransaction.isDefined) issueTransaction.get.sender.address == address else false
  }

  def getAccountBalance(account: Account): Map[AssetId, (Long, Boolean)] = {
    val address = account.address
    getAccountAssets(address).foldLeft(Map.empty[AssetId, (Long, Boolean)]) { (result, asset) =>
      val assetIdTry = Base58.decode(asset)
      val balance = balanceByKey(address + asset)

      if (assetIdTry.isSuccess) {
        val assetId = assetIdTry.get
        val issued = isIssuerOfAsset(address, assetId)

        result.updated(assetId, (balance, issued))
      } else result
    }
  }

  private[blockchain] def applyChanges(changes: Map[AssetAcc, (AccState, Reason)]): Unit = synchronized {
    setStateHeight(stateHeight + 1)
    val h = stateHeight
    changes.foreach { ch =>
      val change = Row(ch._2._1, ch._2._2, Option(lastStates.get(ch._1.key)).getOrElse(0))
      accountChanges(ch._1.key).put(h, change)
      lastStates.put(ch._1.key, h)
      ch._2._2.foreach {
        case tx: AssetIssuance =>
          transactionsMap.put(tx.id, tx.bytes)
          reissuableIndex.put(tx.assetId, tx.reissuable)
          includedTx.put(tx.id, h)
        case tx =>
          includedTx.put(tx.id, h)
      }
      updateAccountAssets(ch._1.account.address, ch._1.assetId)
    }
  }

  def rollbackTo(rollbackTo: Int): State = synchronized {
    def deleteNewer(key: Address): Unit = {
      val currentHeight = lastStates.get(key)
      if (currentHeight > rollbackTo) {
        val dataMap = accountChanges(key)
        val changes = dataMap.remove(currentHeight)
        changes.reason.foreach(t => {
          includedTx.remove(t.id)
          transactionsMap.remove(t.id)
          t match {
            case t: ReissueTransaction =>
              // if you rollback reissue, then before that issue value always was true
              reissuableIndex.put(t.assetId, true)
            case t: IssueTransaction =>
              reissuableIndex.remove(t.assetId)
            case _ =>
          }
        })
        val prevHeight = changes.lastRowHeight
        lastStates.put(key, prevHeight)
        deleteNewer(key)
      }
    }

    lastStates.keySet().foreach { key =>
      deleteNewer(key)
    }
    setStateHeight(rollbackTo)
    this
  }

  override def processBlock(block: Block): Try[State] = Try {
    val trans = block.transactions
    val fees: Map[AssetAcc, (AccState, Reason)] = block.consensusModule.feesDistribution(block)
      .map(m => m._1 -> (AccState(assetBalance(m._1) + m._2), List(FeesStateChange(m._2))))

    log.info(s"${block.timestampField.value} < ${settings.allowTemporaryNegativeUntil} = ${block.timestampField.value < settings.allowTemporaryNegativeUntil}")
    val newBalances: Map[AssetAcc, (AccState, Reason)] = calcNewBalances(trans, fees, block.timestampField.value < settings.allowTemporaryNegativeUntil)
    newBalances.foreach(nb => require(nb._2._1.balance >= 0))

    applyChanges(newBalances)
    log.trace(s"New state height is $stateHeight, hash: $hash, totalBalance: $totalBalance")

    this
  }


  private[blockchain] def calcNewBalances(trans: Seq[Transaction], fees: Map[AssetAcc, (AccState, Reason)], allowTemporaryNegative: Boolean):
  Map[AssetAcc, (AccState, Reason)] = {
    val newBalances: Map[AssetAcc, (AccState, Reason)] = trans.foldLeft(fees) { case (changes, tx) =>
      tx.balanceChanges().foldLeft(changes) { case (iChanges, bc) =>
        //update balances sheet
        val currentChange = iChanges.getOrElse(bc.assetAcc, (AccState(assetBalance(bc.assetAcc)), List.empty))
        val newBalance = if (currentChange._1.balance == Long.MinValue) Long.MinValue
        else Try(Math.addExact(currentChange._1.balance, bc.delta)).getOrElse(Long.MinValue)

        if (newBalance < 0 && !allowTemporaryNegative) {
          throw new Error(s"Transaction leads to negative balance ($newBalance): ${tx.json}")
        }

        iChanges.updated(bc.assetAcc, (AccState(newBalance), tx +: currentChange._2))
      }
    }
    newBalances
  }

  override def balance(account: Account, atHeight: Option[Int] = None): Long =
    assetBalance(AssetAcc(account, None), atHeight)

  def assetBalance(account: AssetAcc, atHeight: Option[Int] = None): Long = {
    balanceByKey(account.key, atHeight)
  }


  override def balanceWithConfirmations(account: Account, confirmations: Int, heightOpt: Option[Int]): Long =
    balance(account, Some(Math.max(1, heightOpt.getOrElse(stateHeight) - confirmations)))

  private def balanceByKey(key: String, atHeight: Option[Int] = None): Long = {
    Option(lastStates.get(key)) match {
      case Some(h) if h > 0 =>
        val requiredHeight = atHeight.getOrElse(stateHeight)
        require(requiredHeight >= 0, s"Height should not be negative, $requiredHeight given")

        def loop(hh: Int, min: Long = Long.MaxValue): Long = {
          val row = accountChanges(key).get(hh)
          require(Option(row).isDefined, s"accountChanges($key).get($hh) is null. lastStates.get(address)=$h")
          if (hh <= requiredHeight) Math.min(row.state.balance, min)
          else if (row.lastRowHeight == 0) 0L
          else loop(row.lastRowHeight, Math.min(row.state.balance, min))
        }

        loop(h)
      case _ =>
        0L
    }
  }

  def totalBalance: Long = lastStates.keySet().toList.map(address => balanceByKey(address)).sum

  override def accountTransactions(account: Account, limit: Int = 50): Seq[Transaction] = {
    Option(lastStates.get(account.address)) match {
      case Some(accHeight) =>
        val m = accountChanges(account.address)

        def loop(h: Int, acc: Seq[Transaction]): Seq[Transaction] = Option(m.get(h)) match {
          case Some(heightChangesBytes) if acc.length < limit =>
            val heightChanges = heightChangesBytes
            val heightTransactions = heightChanges.reason.toArray.filter(_.isInstanceOf[Transaction])
              .map(_.asInstanceOf[Transaction])
            loop(heightChanges.lastRowHeight, heightTransactions ++ acc)
          case _ => acc
        }

        loop(accHeight, Seq.empty).distinct
      case None => Seq.empty
    }
  }.takeRight(limit)

  def lastAccountLagonakiTransaction(account: Account): Option[LagonakiTransaction] = {
    def loop(h: Int, address: Address): Option[LagonakiTransaction] = {
      val changes = accountChanges(address)
      Option(changes.get(h)) match {
        case Some(row) =>
          val accountTransactions = row.reason.filter(_.isInstanceOf[LagonakiTransaction])
            .map(_.asInstanceOf[LagonakiTransaction])
            .filter(_.creator.isDefined).filter(_.creator.get.address == address)
          if (accountTransactions.nonEmpty) Some(accountTransactions.maxBy(_.timestamp))
          else loop(row.lastRowHeight, address)
        case _ => None
      }
    }

    Option(lastStates.get(account.address)) match {
      case Some(height) => loop(height, account.address)
      case None => None
    }
  }

  override def included(id: Array[Byte], heightOpt: Option[Int]): Option[Int] =
    Option(includedTx.get(id)).filter(_ < heightOpt.getOrElse(Int.MaxValue))

  /**
    * Returns sequence of valid transactions
    */
  override final def validate(trans: Seq[Transaction], heightOpt: Option[Int] = None): Seq[Transaction] = {
    val height = heightOpt.getOrElse(stateHeight)

    val txs = trans.filter(t => isValid(t, height))
    val invalidPaymentTransactionsByTimestamp = invalidatePaymentTransactionsByTimestamp(txs)
    val validTransactions = excludeTransactions(txs, invalidPaymentTransactionsByTimestamp)
    val filteredFromFuture = filterTransactionsFromFuture(validTransactions)

    def filterValidTransactionsByState(trans: Seq[Transaction]): Seq[Transaction] = {
      val (state, validTxs) = trans.foldLeft((Map.empty[AssetAcc, (AccState, Reason)], Seq.empty[Transaction])) {
        case ((currentState, validTxs), tx) =>
          try {
            val newState = tx.balanceChanges().foldLeft(currentState) { case (iChanges, bc) =>
              //update balances sheet
              def safeSum(first: Long, second: Long): Long = {
                try {
                  Math.addExact(first, second)
                } catch {
                  case e: ArithmeticException =>
                    throw new Error(s"Transaction leads to overflow balance: $first + $second = ${first + second}")
                }
              }

              val currentChange = iChanges.getOrElse(bc.assetAcc, (AccState(assetBalance(bc.assetAcc)), List.empty))
              val newBalance = safeSum(currentChange._1.balance, bc.delta)
              if (newBalance >= 0) {
                iChanges.updated(bc.assetAcc, (AccState(newBalance), tx +: currentChange._2))
              } else {
                throw new Error(s"Transaction leads to negative state: ${currentChange._1.balance} + ${bc.delta} = ${currentChange._1.balance + bc.delta}")
              }
            }
            (newState, validTxs :+ tx)
          } catch {
            case NonFatal(e) =>
              (currentState, validTxs)
          }
      }
      validTxs
    }

    filterValidTransactionsByState(filteredFromFuture)
  }

  private def excludeTransactions(transactions: Seq[Transaction], exclude: Iterable[Transaction]) =
    transactions.filter(t1 => !exclude.exists(t2 => t2.id sameElements t1.id))

  private def invalidatePaymentTransactionsByTimestamp(transactions: Seq[Transaction]): Seq[Transaction] = {
    val paymentTransactions = transactions.filter(_.isInstanceOf[PaymentTransaction])
      .map(_.asInstanceOf[PaymentTransaction])

    val initialSelection: Map[String, (List[Transaction], Long)] = Map(paymentTransactions.map { payment =>
      val address = payment.sender.address
      val stateTimestamp = lastAccountLagonakiTransaction(payment.sender) match {
        case Some(lastTransaction) => lastTransaction.timestamp
        case _ => 0
      }
      address -> (List[Transaction](), stateTimestamp)
    }: _*)

    val orderedTransaction = paymentTransactions.sortBy(_.timestamp)
    val selection: Map[String, (List[Transaction], Long)] = orderedTransaction.foldLeft(initialSelection) { (s, t) =>
      val address = t.sender.address
      val tuple = s(address)
      if (t.timestamp > tuple._2) {
        s.updated(address, (tuple._1, t.timestamp))
      } else {
        s.updated(address, (tuple._1 :+ t, tuple._2))
      }
    }

    selection.foldLeft(List[Transaction]()) { (l, s) => l ++ s._2._1 }
  }

  private def filterTransactionsFromFuture(transactions: Seq[Transaction]): Seq[Transaction] = {
    val currentTime = NTP.correctedTime()
    transactions.filter {
      tx => (tx.timestamp - currentTime).millis <= SimpleTransactionModule.MaxTimeForUnconfirmed
    }
  }

  private[blockchain] def isValid(transaction: Transaction, height: Int): Boolean = transaction match {
    case tx: PaymentTransaction =>
      tx.validate == ValidationResult.ValidateOke && isTimestampCorrect(tx)
    case tx: TransferTransaction =>
      tx.validate == ValidationResult.ValidateOke && included(tx.id, None).isEmpty
    case tx: IssueTransaction =>
      tx.validate == ValidationResult.ValidateOke && included(tx.id, None).isEmpty
    case tx: ReissueTransaction =>
      val reissueValid: Boolean = {
        val sameSender = Option(transactionsMap.get(tx.assetId)).exists(b =>
          IssueTransaction.parseBytes(b) match {
            case Success(issue) =>
              issue.sender.address == tx.sender.address
            case Failure(f) =>
              log.debug(s"Can't deserialise issue tx", f)
              false
          })
        val reissuable = Option(reissuableIndex.get(tx.assetId)).getOrElse(false)
        sameSender && reissuable
      }
      reissueValid && tx.validate == ValidationResult.ValidateOke && included(tx.id, None).isEmpty
    case gtx: GenesisTransaction =>
      height == 0
    case otx: Any =>
      log.error(s"Wrong kind of tx: $otx")
      false
  }

  private def isTimestampCorrect(tx: PaymentTransaction): Boolean = {
    lastAccountLagonakiTransaction(tx.sender) match {
      case Some(lastTransaction) => lastTransaction.timestamp < tx.timestamp
      case None => true
    }
  }

  //for debugging purposes only
  def toJson(heightOpt: Option[Int] = None): JsObject = {
    val ls = lastStates.keySet().map(add => add -> balanceByKey(add, heightOpt))
      .filter(b => b._2 != 0).toList.sortBy(_._1)
    JsObject(ls.map(a => a._1 -> JsNumber(a._2)).toMap)
  }

  //for debugging purposes only
  override def toString: String = toJson().toString()

  def hash: Int = {
    (BigInt(FastCryptographicHash(toString.getBytes)) % Int.MaxValue).toInt
  }

}
