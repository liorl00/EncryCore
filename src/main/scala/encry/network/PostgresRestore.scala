package encry.network

import akka.actor.Actor
import akka.persistence.RecoveryCompleted
import encry.EncryApp.{nodeViewHolder, peerManager, settings}
import encry.utils.Logging
import encry.local.explorer.database.DBService
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.HeaderDBVersion
import encry.modifiers.history.block.payload.EncryBlockPayload
import encry.modifiers.mempool.directive.DirectiveDBVersion
import encry.modifiers.mempool.{InputDBVersion, Transaction, TransactionDBVersion}
import encry.view.EncryNodeViewHolder.ReceivableMessages.BlocksFromLocalPersistence
import scorex.crypto.encode.Base16
import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}

class PostgresRestore(dbService: DBService) extends Actor with Logging {

  val heightFuture: Future[Int] = dbService.selectHeight

  heightFuture.onComplete {
    case Success(_) =>
    case Failure(_) => peerManager ! RecoveryCompleted
  }

  override def receive: Receive = {
    case StartRecovery => startRecovery()
  }

  def startRecovery(): Future[Unit] = heightFuture.flatMap { height =>
    settings.postgres.restoreBatchSize match {
      case Some(step) =>
        val slides: Iterator[immutable.IndexedSeq[Int]] = (0 to height).sliding(step)
        slides.foldLeft(Future.successful(List[EncryBlock]())) { case (prevBlocks, slide) =>
          val from: Int = slide.head
          val to: Int = slide.last
          prevBlocks.flatMap { retrievedBlocks =>
            nodeViewHolder ! BlocksFromLocalPersistence(retrievedBlocks)
            selectBlocksByRange(from, to)
          }
        }.map { _ =>
          peerManager ! RecoveryCompleted
          context.stop(self)
          Unit
        }
      case None => Future.successful(Unit)
    }
  }

  private def selectBlocksByRange(from: Int, to: Int): Future[List[EncryBlock]] = {
    val headersFuture: Future[List[HeaderDBVersion]] = dbService.headersByRange(from, to)
    val txsFuture: Future[List[TransactionDBVersion]] = dbService.txsByRange(from, to)
    for {
      headers    <- headersFuture
        .map(_.map(_.toHeader).map(Future.fromTry))
        .flatMap(fs => Future.sequence(fs))
      txs        <- txsFuture
      inputs     <- dbService.inputsByTxIds(txs.map(_.id))
      directives <- dbService.directivesByTxIds(txs.map(_.id))
    } yield {
      val groupedInputs: Map[String, List[InputDBVersion]] = inputs.groupBy(_.txId)
      val groupedDirectives: Map[String, List[DirectiveDBVersion]] = directives.groupBy(_.txId)
      val txsWithIO: Map[String, List[Transaction]] = txs.groupBy(_.blockId).mapValues {
        _.map { tx =>
          Transaction(
            tx.fee,
            tx.timestamp,
            groupedInputs.getOrElse(tx.id, IndexedSeq.empty).flatMap(_.toInput.toOption).toIndexedSeq,
            groupedDirectives.getOrElse(tx.id, List.empty).flatMap(_.toDirective).toIndexedSeq,
            None)
        }
      }
      headers.map { header =>
        EncryBlock(header, EncryBlockPayload(header.id, txsWithIO.getOrElse(Base16.encode(header.id), Seq.empty)), None)
      }
    }
  }

}

case object StartRecovery
