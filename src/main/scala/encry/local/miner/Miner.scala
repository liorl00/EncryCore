package encry.local.miner

import java.text.SimpleDateFormat
import java.util.Date
import akka.actor.{Actor, Props}
import encry.EncryApp._
import encry.consensus._
import encry.local.miner.EncryMiningWorker.NextChallenge
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.modifiers.mempool.{EncryTransaction, Transaction, TransactionFactory}
import encry.modifiers.state.box.Box.Amount
import encry.network.DeliveryManager.FullBlockChainSynced
import encry.network.EncryNodeViewSynchronizer.ReceivableMessages.SemanticallySuccessfulModifier
import encry.settings.Constants
import encry.stats.LoggingActor.LogMessage
import encry.stats.StatsSender.{CandidateProducingTime, MiningEnd, MiningTime, SleepTime}
import encry.utils.NetworkTime.Time
import encry.view.EncryNodeViewHolder.CurrentView
import encry.view.EncryNodeViewHolder.ReceivableMessages.{GetDataFromCurrentView, LocallyGeneratedModifier}
import encry.view.history.{EncryHistory, Height}
import encry.view.mempool.EncryMempool
import encry.view.state.{StateMode, UtxoState}
import encry.view.wallet.EncryWallet
import io.circe.syntax._
import io.circe.{Encoder, Json}
import io.iohk.iodb.ByteArrayWrapper
import org.encryfoundation.common.crypto.PrivateKey25519
import org.encryfoundation.common.utils.TaggedTypes.{ADDigest, SerializedAdProof}
import scala.collection._

class Miner extends Actor {

  import Miner._

  val dateFormat: SimpleDateFormat = new SimpleDateFormat("HH:mm:ss")
  var startTime: Long = System.currentTimeMillis()
  var sleepTime: Long = System.currentTimeMillis()
  var candidateOpt: Option[CandidateBlock] = None
  var syncingDone: Boolean = false
  val numberOfWorkers: Int = settings.node.numberOfMiningWorkers

  override def preStart(): Unit = context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier[_]])

  override def postStop(): Unit = killAllWorkers()

  def killAllWorkers(): Unit = context.children.foreach(context.stop)

  def needNewCandidate(b: EncryBlock): Boolean =
    !candidateOpt.flatMap(_.parentOpt).map(_.id).exists(_.sameElements(b.header.id))

  override def receive: Receive = if (settings.node.mining) miningEnabled else miningDisabled

  def mining: Receive = {
    case StartMining if context.children.nonEmpty =>
      killAllWorkers()
      self ! StartMining
    case StartMining =>
      for (i <- 0 until numberOfWorkers) yield context.actorOf(
        Props(classOf[EncryMiningWorker], i, numberOfWorkers).withDispatcher("mining-dispatcher").withMailbox("mining-mailbox"))
      candidateOpt match {
        case Some(candidateBlock) =>
          if (settings.logging.enableLogging)
            context.system.actorSelection("/user/loggingActor") !
              LogMessage("Info", s"Starting mining at ${dateFormat.format(new Date(System.currentTimeMillis()))}", System.currentTimeMillis())
          context.children.foreach(_ ! NextChallenge(candidateBlock))
        case None => produceCandidate()
      }
    case DisableMining if context.children.nonEmpty =>
      if (settings.logging.enableLogging)
        context.system.actorSelection("/user/loggingActor") ! LogMessage("Info", "Received DisableMining msg", System.currentTimeMillis())
      killAllWorkers()
      candidateOpt = None
      context.become(miningDisabled)
    case MinedBlock(block, workerIdx) if candidateOpt.exists(_.stateRoot sameElements block.header.stateRoot) =>
      if (settings.logging.enableLogging)
        context.system.actorSelection("/user/loggingActor") !
          LogMessage("Info", s"Going to propagate new block $block from worker $workerIdx", System.currentTimeMillis())
      killAllWorkers()
      nodeViewHolder ! LocallyGeneratedModifier(block.header)
      nodeViewHolder ! LocallyGeneratedModifier(block.payload)
      if (settings.node.sendStat) {
        context.actorSelection("/user/statsSender") ! MiningEnd(block.header, workerIdx, context.children.size)
        context.actorSelection("/user/statsSender") ! MiningTime(System.currentTimeMillis() - startTime)
      }
      if (settings.node.stateMode == StateMode.Digest)
        block.adProofsOpt.foreach(adp => nodeViewHolder ! LocallyGeneratedModifier(adp))
      candidateOpt = None
      sleepTime = System.currentTimeMillis()
    case GetMinerStatus => sender ! MinerStatus(context.children.nonEmpty && candidateOpt.nonEmpty, candidateOpt)
    case _ =>
  }

  def miningEnabled: Receive =
    receiveSemanticallySuccessfulModifier orElse
      receiverCandidateBlock orElse
      mining orElse
      chainEvents orElse
      unknownMessage

  def miningDisabled: Receive = {
    case EnableMining =>
      context.become(miningEnabled)
      self ! StartMining
    case GetMinerStatus => sender ! MinerStatus(context.children.nonEmpty, candidateOpt)
    case FullBlockChainSynced =>
      syncingDone = true
      if (settings.node.mining) self ! EnableMining
  }

  def receiveSemanticallySuccessfulModifier: Receive = {
    case SemanticallySuccessfulModifier(mod: EncryBlock) if needNewCandidate(mod) =>
      if (settings.logging.enableLogging)
        context.system.actorSelection("/user/loggingActor") !
          LogMessage("Info", s"Got new block. Starting to produce candidate at height: ${mod.header.height + 1} " +
            s"at ${dateFormat.format(new Date(System.currentTimeMillis()))}", System.currentTimeMillis())
      produceCandidate()
    case SemanticallySuccessfulModifier(_) =>
  }

  def receiverCandidateBlock: Receive = {
    case c: CandidateBlock => procCandidateBlock(c)
    case cEnv: CandidateEnvelope if cEnv.c.nonEmpty => procCandidateBlock(cEnv.c.get)
    case _: CandidateEnvelope =>
      if (settings.logging.enableLogging)
        context.system.actorSelection("/user/loggingActor") !
          LogMessage("Debug", "Received empty CandidateEnvelope, going to suspend mining for a while", System.currentTimeMillis())
      self ! DisableMining
  }

  def unknownMessage: Receive = {
    case m =>
      if (settings.logging.enableLogging) context.system.actorSelection("/user/loggingActor") !
        LogMessage("Warn", s"Unexpected message $m", System.currentTimeMillis())
  }

  def chainEvents: Receive = {
    case FullBlockChainSynced => syncingDone = true
  }

  def procCandidateBlock(c: CandidateBlock): Unit = {
    if (settings.logging.enableLogging)
      context.system.actorSelection("/user/loggingActor") !
        LogMessage("Info", s"Got candidate block $c in ${dateFormat.format(new Date(System.currentTimeMillis()))}", System.currentTimeMillis())
    candidateOpt = Some(c)
    self ! StartMining
  }

  def createCandidate(view: CurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool],
                      bestHeaderOpt: Option[EncryBlockHeader]): CandidateBlock = {
    val timestamp: Time = timeProvider.estimatedTime
    val height: Height = Height @@ (bestHeaderOpt.map(_.height).getOrElse(Constants.Chain.PreGenesisHeight) + 1)

    // `txsToPut` - valid, non-conflicting txs with respect to their fee amount.
    // `txsToDrop` - invalidated txs to be dropped from mempool.
    val (txsToPut: Seq[Transaction], txsToDrop: Seq[Transaction], _) = view.pool.takeAll.toSeq.sortBy(_.fee).reverse
      .foldLeft((Seq[Transaction](), Seq[Transaction](), Set[ByteArrayWrapper]())) {
        case ((validTxs, invalidTxs, bxsAcc), tx) =>
          val bxsRaw: IndexedSeq[ByteArrayWrapper] = tx.inputs.map(u => ByteArrayWrapper(u.boxId))
          if ((validTxs.map(_.length).sum + tx.length) <= Constants.BlockMaxSize - 124) {
            if (view.state.validate(tx).isSuccess && bxsRaw.forall(k =>
              !bxsAcc.contains(k)) && bxsRaw.size == bxsRaw.toSet.size)
              (validTxs :+ tx, invalidTxs, bxsAcc ++ bxsRaw)
            else (validTxs, invalidTxs :+ tx, bxsAcc)
          } else (validTxs, invalidTxs, bxsAcc)
      }
    // Remove stateful-invalid txs from mempool.
    view.pool.removeAsync(txsToDrop)

    val minerSecret: PrivateKey25519 = view.vault.accountManager.mandatoryAccount
    val feesTotal: Amount = txsToPut.map(_.fee).sum
    val supplyTotal: Amount = EncrySupplyController.supplyAt(view.state.height)
    val coinbase: EncryTransaction = TransactionFactory
      .coinbaseTransactionScratch(minerSecret.publicImage, timestamp, supplyTotal, feesTotal, view.state.height)

    val txs: Seq[Transaction] = txsToPut.sortBy(_.timestamp) :+ coinbase

    val (adProof: SerializedAdProof, adDigest: ADDigest) = view.state.generateProofs(txs)
      .getOrElse(throw new Exception("ADProof generation failed"))

    val difficulty: Difficulty = bestHeaderOpt.map(parent => view.history.requiredDifficultyAfter(parent))
      .getOrElse(Constants.Chain.InitialDifficulty)

    val candidate: CandidateBlock =
      CandidateBlock(bestHeaderOpt, adProof, adDigest, Constants.Chain.Version, txs, timestamp, difficulty)

    if (settings.logging.enableLogging)
      context.system.actorSelection("/user/loggingActor") !
        LogMessage("Info", s"Sending candidate block with ${candidate.transactions.length - 1} transactions " +
          s"and 1 coinbase for height $height", System.currentTimeMillis())

    candidate
  }

  def produceCandidate(): Unit =
    nodeViewHolder ! GetDataFromCurrentView[EncryHistory, UtxoState, EncryWallet, EncryMempool, CandidateEnvelope] { view =>
      val producingStartTime: Time = System.currentTimeMillis()
      startTime = producingStartTime
      val bestHeaderOpt: Option[EncryBlockHeader] = view.history.bestBlockOpt.map(_.header)
      bestHeaderOpt match {
        case Some(h) =>
          if (settings.logging.enableLogging)
            context.system.actorSelection("/user/loggingActor") !
              LogMessage("Info", s"Best header at height ${h.height}", System.currentTimeMillis())
        case None =>
          if (settings.logging.enableLogging)
            context.system.actorSelection("/user/loggingActor") !
              LogMessage("Info", s"No best header opt", System.currentTimeMillis())
      }
      val candidate: CandidateEnvelope =
        if ((bestHeaderOpt.isDefined && (syncingDone || view.history.isFullChainSynced)) || settings.node.offlineGeneration) {
          if (settings.logging.enableLogging)
            context.system.actorSelection("/user/loggingActor") !
              LogMessage("Info", s"Starting candidate generation at ${dateFormat.format(new Date(System.currentTimeMillis()))}", System.currentTimeMillis())
          if (settings.node.sendStat) context.actorSelection("user/statsSender") ! SleepTime(System.currentTimeMillis() - sleepTime)
          val envelope: CandidateEnvelope = CandidateEnvelope.fromCandidate(createCandidate(view, bestHeaderOpt))
          if (settings.node.sendStat) context.actorSelection("user/statsSender") ! CandidateProducingTime(System.currentTimeMillis() - producingStartTime)
          envelope
        } else CandidateEnvelope.empty
      candidate
    }
}

object Miner {

  case object DisableMining

  case object EnableMining

  case object StartMining

  case object GetMinerStatus

  case class MinedBlock(block: EncryBlock, workerIdx: Int)

  case class MinerStatus(isMining: Boolean, candidateBlock: Option[CandidateBlock]) {
    lazy val json: Json = Map(
      "isMining" -> isMining.asJson,
      "candidateBlock" -> candidateBlock.map(_.asJson).getOrElse("None".asJson)
    ).asJson
  }

  case class CandidateEnvelope(c: Option[CandidateBlock])

  object CandidateEnvelope {

    val empty: CandidateEnvelope = CandidateEnvelope(None)

    def fromCandidate(c: CandidateBlock): CandidateEnvelope = CandidateEnvelope(Some(c))
  }

  implicit val jsonEncoder: Encoder[MinerStatus] = (r: MinerStatus) =>
    Map("isMining" -> r.isMining.asJson,
      "candidateBlock" -> r.candidateBlock.map(_.asJson).getOrElse("None".asJson)).asJson
}