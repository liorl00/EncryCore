package encry.bench

import akka.actor.Actor
import encry.ModifierId
import encry.bench.ModsApplicationBencher.{Conclude, StartModifiersApplication}
import encry.bench.NVHBench._
import encry.modifiers.history.block.EncryBlock
import encry.modifiers.history.block.header.{EncryBlockHeader, EncryBlockHeaderSerializer}
import encry.modifiers.history.block.payload.{EncryBlockPayload, EncryBlockPayloadSerializer}
import encry.network.EncryNodeViewSynchronizer.ReceivableMessages.SemanticallySuccessfulModifier
import encry.utils.Logging
import encry.view.EncryNodeViewHolder.ReceivableMessages.ModifiersFromRemote
import encry.view.history.EncryHistory

class ModsApplicationBencher extends Actor with Logging {

  var applicationJournal: Map[ModifierId, Long] = Map.empty[ModifierId, Long]
  var applicationTime: Map[ModifierId, (Long, Long)] = Map.empty[ModifierId, (Long, Long)]
  var maxModHeight: Int = 0

  override def preStart(): Unit = context.system.eventStream.subscribe(self, classOf[SemanticallySuccessfulModifier[_]])

  override def receive: Receive = {
    case SemanticallySuccessfulModifier(block: EncryBlock) =>
      val headerRecord: Long = applicationJournal
        .getOrElse(block.id, throw new Error(s"Bench failed. Unable to find record for block ${block.encodedId}"))
      val payloadRecord: Long = applicationJournal
        .getOrElse(block.payload.id, throw new Error(s"Bench failed. Unable to find record for payload ${block.payload.encodedId}"))
      val currentTime: Long = System.currentTimeMillis()
      val payloadApplicationTime: Long = currentTime - payloadRecord
      val fullApplicationTime: Long =  currentTime - headerRecord

      applicationTime = applicationTime.updated(block.id, (payloadApplicationTime, fullApplicationTime))

      logInfo(s"Block(id = ${block.encodedId} h = ${block.header.height} " +
        s"payloadApplicationTime = $payloadApplicationTime, fullApplicationTime = $fullApplicationTime)")

      if (block.header.height >= maxModHeight) self ! Conclude

    case StartModifiersApplication(container) =>
      logInfo("Starting modifiers application bench")

      val headers: IndexedSeq[EncryBlockHeader] = container.lastHeaders(container.bestHeaderHeight).headers
      val payloads: IndexedSeq[EncryBlockPayload] = headers.map { h =>
        container.getBlock(h).getOrElse(throw new Error(s"Bench failed. Unable to find block for header $h"))
      }.map(_.payload)

      maxModHeight = container.bestHeaderHeight

      logInfo(s"Sending ${headers.size} headers to NodeViewHolder")
      nodeViewHolder ! ModifiersFromRemote(null, EncryBlockHeader.modifierTypeId, headers.map(EncryBlockHeaderSerializer.toBytes))

      val headersApplicationStart: Long = System.currentTimeMillis()
      applicationJournal = headers.foldLeft(applicationJournal) { case (acc, h) =>
        acc.updated(h.id, headersApplicationStart)
      }

      logInfo(s"Sending ${headers.size} payloads to NodeViewHolder")
      nodeViewHolder ! ModifiersFromRemote(null, EncryBlockPayload.modifierTypeId, payloads.map(EncryBlockPayloadSerializer.toBytes))

      val payloadsApplicationStart: Long = System.currentTimeMillis()
      applicationJournal = payloads.foldLeft(applicationJournal) { case (acc, p) =>
        acc.updated(p.id, payloadsApplicationStart)
      }

    case Conclude =>
      logInfo(s"Average block application time delta: ${applicationTime.map(_._2._1).sum / maxModHeight}")
      logInfo("Done. Exiting")
      NVHBench.forceStopApplication()
  }
}

object ModsApplicationBencher {
  case class StartModifiersApplication(container: EncryHistory)
  case object Conclude
}
