package encry.modifiers.history

import encry.modifiers.EncryPersistentModifier
import encry.modifiers.history.block.header.{EncryBlockHeader, EncryBlockHeaderSerializer}
import encry.modifiers.history.block.payload.{EncryBlockPayload, EncryPaymentBlockPayloadSerializer}
import scorex.core.serialization.Serializer

import scala.util.Try

object HistoryModifierSerializer extends Serializer[EncryPersistentModifier] {

  override def toBytes(obj: EncryPersistentModifier): Array[Byte] = obj match {
    case m: EncryBlockHeader =>
      EncryBlockHeader.modifierTypeId +: EncryBlockHeaderSerializer.toBytes(m)
    //TODO: ADProofs
    //    case m: ADProofs =>
    //      ADProofs.modifierTypeId +: ADProofSerializer.toBytes(m)
    case m: EncryBlockPayload =>
      EncryBlockPayload.modifierTypeId +: EncryPaymentBlockPayloadSerializer.toBytes(m)
    case m =>
      throw new Error(s"Serialization for unknown modifier: ${m.json.noSpaces}")
  }

  override def parseBytes(bytes: Array[Byte]): Try[EncryPersistentModifier] = Try {
    bytes.head match {
      case EncryBlockHeader.`modifierTypeId` =>
        EncryBlockHeaderSerializer.parseBytes(bytes.tail).get
      //TODO: ADproofs
      //      case ADProofs.`modifierTypeId` =>
      //        ADProofSerializer.parseBytes(bytes.tail).get
      case EncryBlockPayload.`modifierTypeId` =>
        EncryPaymentBlockPayloadSerializer.parseBytes(bytes.tail).get
      case m =>
        throw new Error(s"Deserialization for unknown type byte: $m")
    }
  }
}