package scorex.block

import com.google.common.primitives.{Bytes, Ints, Longs}
import play.api.libs.json.{JsObject, Json}
import scorex.account.PublicKeyAccount
import scorex.block.Block._
import scorex.consensus.{ConsensusModule}
import scorex.crypto.{Base58, SigningFunctionsImpl}
import scorex.transaction.{Transaction, TransactionModule}

import scala.util.Try

abstract class BlockField[T] {
  val name: String
  val value: T

  def json: JsObject

  def bytes: Array[Byte]
}

case class ByteBlockField(override val name: String, override val value: Byte)
  extends BlockField[Byte] {

  override lazy val json: JsObject = Json.obj(name -> value.toInt)
  override lazy val bytes: Array[Byte] = Array(value)
}

case class IntBlockField(override val name: String, override val value: Int)
  extends BlockField[Int] {

  override lazy val json: JsObject = Json.obj(name -> value)
  override lazy val bytes: Array[Byte] = Bytes.ensureCapacity(Ints.toByteArray(value), 4, 0)
}

case class LongBlockField(override val name: String, override val value: Long)
  extends BlockField[Long] {

  override lazy val json: JsObject = Json.obj(name -> value)
  override lazy val bytes: Array[Byte] = Bytes.ensureCapacity(Longs.toByteArray(value), 8, 0)
}

case class BlockIdField(override val name: String, override val value: Block.BlockId)
  extends BlockField[Block.BlockId] {

  override lazy val json: JsObject = Json.obj(name -> Base58.encode(value))
  override lazy val bytes: Array[Byte] = value
}

case class TransactionBlockField(override val name: String, override val value: Transaction)
  extends BlockField[Transaction] {

  override lazy val json: JsObject = value.json()
  override lazy val bytes: Array[Byte] = value.bytes()
}


case class SignerData(generator: PublicKeyAccount, signature: Array[Byte])

//todo: Seq[SignerData] to support multiple signers?
case class SignerDataBlockField(override val name: String, override val value: SignerData)
  extends BlockField[SignerData] {

  override lazy val json: JsObject = Json.obj("generator" -> value.generator.toString,
    "signature" -> value.signature)

  override lazy val bytes: Array[Byte] = value.generator.publicKey ++ value.signature
}

trait Block {
  type CT
  type TT

  val versionField: ByteBlockField
  val timestampField: LongBlockField
  val referenceField: BlockIdField
  val consensusDataField: BlockField[CT]
  val transactionDataField: BlockField[TT]
  val signerDataField: SignerDataBlockField


  implicit val consensusModule: ConsensusModule[CT]
  implicit val transactionModule: TransactionModule[TT]

  // Some block characteristic which is uniq e.g. hash or signature(if timestamp is included there).
  // Used in referencing
  val uniqueId: Block.BlockId

  lazy val transactions = transactionModule.transactions(this)

  lazy val json =
    versionField.json ++
      timestampField.json ++
      referenceField.json ++
      consensusDataField.json ++
      transactionDataField.json ++
      signerDataField.json

  lazy val bytes = {
    val txBytesSize = transactionDataField.bytes.length
    val txBytes = Bytes.ensureCapacity(Ints.toByteArray(txBytesSize), 4, 0) ++ transactionDataField.bytes

    val cBytesSize = consensusDataField.bytes.length
    val cBytes = Bytes.ensureCapacity(Ints.toByteArray(cBytesSize), 4, 0) ++ consensusDataField.bytes

    versionField.bytes ++
      timestampField.bytes ++
      referenceField.bytes ++
      cBytes ++
      txBytes ++
      signerDataField.bytes
  }

  def isValid = {
    val history = transactionModule.history
    val state = transactionModule.state

    consensusModule.isValid(this, history, state) &&
      transactionModule.isValid(this) &&
      history.contains(referenceField.value) &&
      SigningFunctionsImpl.verify(signerDataField.value.signature,
        bytes.dropRight(SigningFunctionsImpl.KeyLength),
        signerDataField.value.generator.publicKey)
  }
}


object Block {
  type BlockId = Array[Byte]

  val BlockIdLength = SigningFunctionsImpl.SignatureLength

  def parse[CDT, TDT](bytes: Array[Byte])
           (implicit consensusModule: ConsensusModule[CDT],
            transactionModule: TransactionModule[TDT]): Try[Block] = Try {
    var position = 1

    val version = bytes.head

    val timestamp = Longs.fromByteArray(bytes.slice(position, position + 8))
    position += 8

    val reference = bytes.slice(position, position+Block.BlockIdLength)
    position += BlockIdLength

    val cBytesLength = Ints.fromByteArray(bytes.slice(position, position+4))
    position += 4
    val cBytes = bytes.slice(position, position + cBytesLength)
    val consBlockField = consensusModule.parseBlockData(cBytes)
    position += cBytesLength


    val tBytesLength = Ints.fromByteArray(bytes.slice(position, position+4))
    position += 4
    val tBytes = bytes.slice(position, position + tBytesLength)
    val txBlockField = transactionModule.parseBlockData(tBytes)
    position += tBytesLength

    val genPK = bytes.slice(position, position + SigningFunctionsImpl.KeyLength)
    position += SigningFunctionsImpl.KeyLength

    val signature = bytes.slice(position, position + SigningFunctionsImpl.SignatureLength)

    new Block{
      override type CT = CDT
      override type TT = TDT

      override val transactionDataField: BlockField[TT] = txBlockField

      override implicit val transactionModule: TransactionModule[TT] = transactionModule.ensuring(_ != null)

      override val versionField: ByteBlockField = ByteBlockField("version", version)
      override val referenceField: BlockIdField = BlockIdField("reference", reference)
      override val signerDataField: SignerDataBlockField =
        SignerDataBlockField("signature", SignerData(new PublicKeyAccount(genPK), signature))

      override val consensusDataField: BlockField[CT] = consBlockField

      //todo: wrong!
      override val uniqueId: BlockId = signature

      override implicit val consensusModule: ConsensusModule[CT] = consensusModule.ensuring(_ != null)
      override val timestampField: LongBlockField = LongBlockField("timestamp", timestamp)
    }
  }
}

trait BlockBuilder[CDT, TDT] {
  val version: Byte

  def build(timestamp: Long,
                 reference: BlockId,
                 consensusData: CDT,
                 transactionData: TDT,
                 generator:PublicKeyAccount,
                 signature:Array[Byte])
                (implicit consensusModule: ConsensusModule[CDT],
                 transactionModule: TransactionModule[TDT]): Block{type CT = CDT; type TT = TDT}
}