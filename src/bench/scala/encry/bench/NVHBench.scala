package encry.bench

import java.io.File

import akka.actor.{ActorRef, ActorSystem, Props}
import akka.stream.ActorMaterializer
import encry.EncryApp
import encry.bench.ModsApplicationBencher.StartModifiersApplication
import encry.settings.NodeSettings
import encry.utils.{Logging, NetworkTimeProvider}
import encry.view.EncryNodeViewHolder
import encry.view.history.EncryHistory
import encry.view.history.processors.payload.BlockPayloadProcessor
import encry.view.history.processors.proofs.FullStateProofProcessor
import encry.view.history.storage.{FileHistoryObjectsStore, HistoryStorage}
import io.iohk.iodb.LSMStore

import scala.concurrent.ExecutionContextExecutor

object NVHBench extends App with Logging {

  implicit val system: ActorSystem = ActorSystem()
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  implicit val ec: ExecutionContextExecutor = system.dispatcher

  EncryApp.main(Array.empty)

  lazy val nodeViewHolder: ActorRef = system.actorOf(EncryNodeViewHolder.props(), "nodeViewHolder")

  val modsApplicationBencher: ActorRef = system.actorOf(Props[ModsApplicationBencher], "modsApplicationBencher")

  val historyContainer: EncryHistory = {
    val historyDir: File = new File(s"""${System.getProperty("user.dir")}/bench/data/history""")
    val db: LSMStore = new LSMStore(historyDir, keepVersions = 0)
    val objectsStore: FileHistoryObjectsStore = new FileHistoryObjectsStore(historyDir.getAbsolutePath)

    val storage: HistoryStorage = new HistoryStorage(db, objectsStore)

    new EncryHistory with FullStateProofProcessor with BlockPayloadProcessor {
      override protected val nodeSettings: NodeSettings = EncryApp.settings.node
      override protected val historyStorage: HistoryStorage = storage
      override protected val timeProvider: NetworkTimeProvider = EncryApp.timeProvider
    }
  }

  modsApplicationBencher ! StartModifiersApplication(historyContainer)

  def forceStopApplication(code: Int = 0): Nothing = sys.exit(code)
}
