package scorex.transaction

import scorex.block.{Block, BlockProcessingModule}

trait TransactionModule[TransactionBlockData] extends BlockProcessingModule[TransactionBlockData] {

  val blockStorage: BlockStorage

  val utxStorage: UnconfirmedTransactionsStorage

  def isValid(block: Block): Boolean

  def isValid(tx: Transaction, blockTime: Long): Boolean

  def transactions(block: Block): Seq[Transaction]

  /**
    * Returns all unconfirmed transactions
    */
  def unconfirmedTxs: Seq[Transaction]

  def putUnconfirmedIfNew(tx: Transaction): Boolean

  def packUnconfirmed(heightOpt: Option[Int] = None): TransactionBlockData

  def clearFromUnconfirmed(data: TransactionBlockData): Unit

  def onNewOffchainTransaction(transaction: Transaction): Unit

  lazy val balancesSupport: Boolean = blockStorage.state match {
    case _: State with BalanceSheet => true
    case _ => false
  }

  lazy val accountWatchingSupport: Boolean = blockStorage.state match {
    case _: State with AccountTransactionsHistory => true
    case _ => false
  }
}
