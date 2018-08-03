package encry.local.miner

import java.util.Date

import akka.actor.Actor
import encry.EncryApp.miner
import encry.consensus.{CandidateBlock, ConsensusSchemeReaders}
import encry.local.miner.EncryMiner.MinedBlock
import encry.local.miner.EncryMiningWorker.{DropChallenge, MineBlock, NextChallenge, Ping}
import encry.utils.Logging
import scala.concurrent.duration._
import java.text.SimpleDateFormat
import scala.concurrent.ExecutionContext.Implicits.global
import encry.settings.Constants

class EncryMiningWorker(myIdx: Int, numberOfWorkers: Int) extends Actor with Logging {

  val sdf: SimpleDateFormat = new SimpleDateFormat("HH:mm:ss")
  var challengeStartTime: Date = new Date(System.currentTimeMillis())

  override def receive: Receive = miningPaused

  override def preStart(): Unit =
    context.system.scheduler.schedule(20.seconds, 10.second)(self ! Ping)

  def miningInProgress: Receive = {
    case MineBlock(candidate: CandidateBlock, nonce: Long) =>
      val initialNonce: Long = Long.MaxValue / numberOfWorkers * myIdx
      log.info(s"Trying nonce: $nonce. Start nonce is: $initialNonce. " +
        s"Iter qty: ${nonce - initialNonce + 1} on worker: $myIdx with diff: ${candidate.difficulty} at height: ${candidate.parentOpt.map(_.height + 1).getOrElse(Constants.Chain.PreGenesisHeight.toString)}")
      ConsensusSchemeReaders.consensusScheme.verifyCandidate(candidate, nonce)
        .fold({
          log.info(s"Send to self: ${myIdx} with nonce: ${nonce + 1}")
          self ! MineBlock(candidate, nonce + 1)
        }) { block =>
          log.info(s"New block is found: $block on worker $self at " +
            s"${sdf.format(new Date(System.currentTimeMillis()))}. Iter qty: ${nonce - initialNonce + 1}")
          log.info(s"Send to miner block: ${block.dataString}")
          context.parent ! MinedBlock(block, myIdx)
        }
    case DropChallenge =>
      log.info(s"Paused mining on worker: $myIdx")
      context.become(miningPaused)
    case NextChallenge(candidate: CandidateBlock) =>
      challengeStartTime = new Date(System.currentTimeMillis())
      context.become(miningInProgress)
      log.info(s"Start challenge on worker: $myIdx at height " +
        s"${candidate.parentOpt.map(_.height + 1).getOrElse(Constants.Chain.PreGenesisHeight.toString)} at ${sdf.format(challengeStartTime)}")
      log.info(s"Send to self mined block with nonce: ${Long.MaxValue / numberOfWorkers * myIdx}")
      self ! MineBlock(candidate, Long.MaxValue / numberOfWorkers * myIdx)
    case Ping => log.info(s"Worker $myIdx is working")
  }

  def miningPaused: Receive = {
    case NextChallenge(candidate: CandidateBlock) =>
      challengeStartTime = new Date(System.currentTimeMillis())
      context.become(miningInProgress)
      log.info(s"Start challenge on worker: $myIdx at height " +
        s"${candidate.parentOpt.map(_.height + 1).getOrElse(Constants.Chain.PreGenesisHeight.toString)} at ${sdf.format(challengeStartTime)}")
      log.info(s"Send to self mined block with nonce: ${Long.MaxValue / numberOfWorkers * myIdx}")
      self ! MineBlock(candidate, Long.MaxValue / numberOfWorkers * myIdx)
    case message => log.info(s"Get smth strange on worker $myIdx when mining is paused")
    case Ping => log.info(s"Worker $myIdx is sleeping")
  }
}

object EncryMiningWorker {

  case object Ping

  case object DropChallenge

  case class NextChallenge(candidateBlock: CandidateBlock)

  case class MineBlock(candidateBlock: CandidateBlock, nonce: Long)

}
