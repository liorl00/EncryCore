package encry.modifiers.state.box.proposition

import scorex.core.serialization.Serializer

import scala.util.{Failure, Try}

object PropositionSerializer extends Serializer[EncryProposition] {

  override def toBytes(obj: EncryProposition): Array[Byte] = obj match {
    case op: OpenProposition.type =>
      OpenProposition.TypeId +: OpenPropositionSerializer.toBytes(op)
    case ap: AccountProposition =>
      AccountProposition.TypeId +: AccountPropositionSerializer.toBytes(ap)
    case hp: HeightProposition =>
      HeightProposition.TypeId +: HeightPropositionSerializer.toBytes(hp)
    case cp: ContractProposition =>
      ContractProposition.TypeId +: ContractPropositionSerializer.toBytes(cp)
    case dp: DeferredProposition =>
      DeferredProposition.TypeId +: DeferredPropositionSerializer.toBytes(dp)
    case m => throw new Error(s"Serialization of unknown proposition type: $m")
  }

  override def parseBytes(bytes: Array[Byte]): Try[EncryProposition] = Try(bytes.head).flatMap {
    case OpenProposition.`TypeId` => OpenPropositionSerializer.parseBytes(bytes.tail)
    case AccountProposition.`TypeId` => AccountPropositionSerializer.parseBytes(bytes.tail)
    case HeightProposition.`TypeId` => HeightPropositionSerializer.parseBytes(bytes.tail)
    case ContractProposition.`TypeId` => ContractPropositionSerializer.parseBytes(bytes.tail)
    case DeferredProposition.`TypeId` => DeferredPropositionSerializer.parseBytes(bytes.tail)
    case t => Failure(new Error(s"Got unknown typeId: $t"))
  }
}
