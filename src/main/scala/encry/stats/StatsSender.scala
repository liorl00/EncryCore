package encry.stats

import java.io.File
import java.net.{InetAddress, InetSocketAddress}
import java.util

import akka.actor.Actor
import encry.EncryApp.{settings, timeProvider}
import encry.consensus.EncrySupplyController
import encry.modifiers.history.block.header.EncryBlockHeader
import encry.settings.Algos
import encry.stats.StatsSender._
import encry.utils.{Logging, NetworkTimeProvider}
import encry.view.history
import encry.{ModifierId, ModifierTypeId}
import org.influxdb.{InfluxDB, InfluxDBFactory}

class StatsSender(ntp: NetworkTimeProvider) extends Actor with Logging {

  val influxDB: InfluxDB =
    InfluxDBFactory.connect(settings.influxDB.url, settings.influxDB.login, settings.influxDB.password)

  var modifiersToDownload: Map[String, (ModifierTypeId, Long)] = Map()

  influxDB.setRetentionPolicy("autogen")

  override def preStart(): Unit =
    influxDB.write(8189, s"""nodesStartTime value="${settings.network.nodeName}"""")

  override def receive: Receive = {

    case BlocksStat(notCompletedBlocks: Int, headerCache: Int, payloadCache: Int, completedBlocks: Int) =>
      influxDB.write(8189, s"blocksStatistic headerStats=$headerCache,payloadStats=$payloadCache,completedBlocksStat=$completedBlocks,notCompletedBlocksStat=$notCompletedBlocks")

    case BestHeaderInChain(fb: EncryBlockHeader) =>
      influxDB.write(8189, util.Arrays.asList(
        s"difficulty,nodeName=${settings.network.nodeName} diff=${fb.difficulty.toString},height=${fb.height}",
        s"height,nodeName=${settings.network.nodeName},header=${Algos.encode(fb.id)} height=${fb.height}",
        s"stateWeight,nodeName=${settings.network.nodeName},height=${fb.height} value=${new File("encry/data/state/").listFiles.foldLeft(0L)(_ + _.length())}",
        s"historyWeight,nodeName=${settings.network.nodeName},height=${fb.height} value=${new File("encry/data/history/").listFiles.foldLeft(0L)(_ + _.length())}",
        s"supply,nodeName=${settings.network.nodeName},height=${fb.height} value=${EncrySupplyController.supplyAt(fb.height.asInstanceOf[history.Height])}"
      )
      )

    case MiningEnd(blockHeader: EncryBlockHeader, workerIdx: Int, workersQty: Int) =>
      influxDB.write(
        8189,
        util.Arrays.asList(
          s"miningEnd,nodeName=${settings.network.nodeName},block=${Algos.encode(blockHeader.id)},height=${blockHeader.height},worker=$workerIdx value=${timeProvider.time() - blockHeader.timestamp}",
          s"minerIterCount,nodeName=${settings.network.nodeName},block=${Algos.encode(blockHeader.id)},height=${blockHeader.height} value=${blockHeader.nonce - Long.MaxValue / workersQty * workerIdx + 1}"
        )
      )

    case DownloadResponse(modifiersId: Seq[ModifierId], to: InetSocketAddress) =>
      modifiersId.map(Algos.encode).foreach(modId =>
        influxDB.write(
          8189,
          s"downloadResponse," +
            s"requestFrom=${to.getAddress.getHostAddress}," +
            s"modId=$modId" +
            s" value=${timeProvider.time()}"
        )
      )

    case SendDownloadRequest(modifierTypeId: ModifierTypeId, modifiers: Seq[ModifierId]) =>
      modifiersToDownload = modifiersToDownload ++ modifiers.map(mod => (Algos.encode(mod), (modifierTypeId, System.currentTimeMillis())))
      modifiers.map(Algos.encode).foreach(modId =>
        influxDB.write(
          8189,
          s"downloadRequest," +
            s"requestFrom=${InetAddress.getLocalHost.getHostAddress}," +
            s"modId=$modId" +
            s" value=${timeProvider.time()}"
        )
      )

    case GetModifiers(modifierTypeId: ModifierTypeId, modifiers: Seq[ModifierId]) =>
      modifiers.foreach(downloadedModifierId =>
        modifiersToDownload.get(Algos.encode(downloadedModifierId)).foreach { dowloadInfo =>
          influxDB.write(
            8189,
            s"modDownloadStat,nodeName=${settings.network.nodeName},modId=${Algos.encode(downloadedModifierId)},modType=${dowloadInfo._1} value=${System.currentTimeMillis() - dowloadInfo._2}"
          )
          modifiersToDownload = modifiersToDownload - Algos.encode(downloadedModifierId)
        }
      )
  }
}

object StatsSender {

  case class MiningEnd(blockHeader: EncryBlockHeader, workerIdx: Int, workersQty: Int)

  case class BestHeaderInChain(bestHeader: EncryBlockHeader)

  case class SendDownloadRequest(modifierTypeId: ModifierTypeId, modifiers: Seq[ModifierId])

  case class GetModifiers(modifierTypeId: ModifierTypeId, modifiers: Seq[ModifierId])

  case class BlocksStat(notCompletedBlocks: Int, headerCache: Int, payloadCache: Int, completedBlocks: Int)

  case class DownloadResponse(modifiersId: Seq[ModifierId], to: InetSocketAddress)
}
