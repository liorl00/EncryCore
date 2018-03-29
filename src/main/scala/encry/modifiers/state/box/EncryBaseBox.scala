package encry.modifiers.state.box

import com.google.common.primitives.Longs
import encry.modifiers.state.box.EncryBox.BxTypeId
import encry.modifiers.state.box.proof.Proof
import encry.settings.Algos
import io.circe.Encoder
import scorex.core.transaction.box.Box
import scorex.core.transaction.box.proposition.Proposition
import scorex.crypto.authds.ADKey

import scala.util.Try

trait EncryBaseBox extends Box[Proposition] {

  val typeId: BxTypeId

  val nonce: Long

  override lazy val id: ADKey = ADKey @@ Algos.hash(Longs.toByteArray(nonce)).updated(0, typeId) // 32 bytes!

  def unlockTry(proof: Proof)(implicit ctx: Context): Try[Unit]

  override def toString: String = s"<Box type=:$typeId id=:${Algos.encode(id)}>"
}

object EncryBaseBox {

  implicit val jsonEncoder: Encoder[EncryBaseBox] = {
    case ab: AssetBox => AssetBox.jsonEncoder(ab)
    case cb: CoinbaseBox => CoinbaseBox.jsonEncoder(cb)
    case op: OpenBox => OpenBox.jsonEncoder(op)
    case pkb: PubKeyInfoBox => PubKeyInfoBox.jsonEncoder(pkb)
  }
}
