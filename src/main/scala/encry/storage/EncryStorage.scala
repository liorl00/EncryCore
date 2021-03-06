package encry.storage

import encry.utils.CoreTaggedTypes.VersionTag
import encry.storage.codec.FixLenComplexValueCodec
import io.iohk.iodb.{ByteArrayWrapper, Store}
import org.encryfoundation.common.Algos
import scala.util.{Failure, Success, Try}
import encry.utils.Logging

trait EncryStorage extends AutoCloseable with Logging {

  val store: Store

  def insert(version: ByteArrayWrapper,
             toInsert: Seq[(ByteArrayWrapper, ByteArrayWrapper)]): Unit =
    store.update(version, Seq.empty, toInsert)

  def remove(version: ByteArrayWrapper,
             toRemove: Seq[ByteArrayWrapper]): Unit =
    store.update(version, toRemove, Seq.empty)

  def update(version: ByteArrayWrapper,
             toRemove: Seq[ByteArrayWrapper],
             toUpdate: Seq[(ByteArrayWrapper, ByteArrayWrapper)]): Unit = {
    remove(version, toRemove)
    insert(ByteArrayWrapper(Algos.hash(version.data)), toUpdate)
  }

  def get(key: ByteArrayWrapper): Option[Array[Byte]] = store.get(key).map(_.data)

  def readComplexValue(key: ByteArrayWrapper, unitLen: Int): Option[Seq[Array[Byte]]] =
    store.get(key).flatMap { v =>
      FixLenComplexValueCodec.parseComplexValue(v.data, unitLen).toOption
    }

  def rollbackTo(version: VersionTag): Try[Unit] =
    store.get(ByteArrayWrapper(version)) match {
      case Some(_) =>
        Success(store.rollback(ByteArrayWrapper(version)))
      case None =>
        Failure(new Exception(s"Unable to get root hash at version ${Algos.encoder.encode(version)}"))
    }

  override def close(): Unit = {
    logInfo("Closing storage")
    store.close()
  }
}

object EncryStorage {

  implicit def liftByteArray(array: Array[Byte]): ByteArrayWrapper = ByteArrayWrapper(array)
}
