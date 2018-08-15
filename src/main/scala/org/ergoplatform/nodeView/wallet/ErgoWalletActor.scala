package org.ergoplatform.nodeView.wallet

import akka.actor.Actor
import io.iohk.iodb.ByteArrayWrapper
import org.ergoplatform.modifiers.mempool.{ErgoTransaction, UnsignedErgoTransaction}
import org.ergoplatform.nodeView.history.ErgoHistory.Height
import org.ergoplatform._
import org.ergoplatform.modifiers.ErgoFullBlock
import org.ergoplatform.nodeView.state.ErgoStateContext
import org.ergoplatform.nodeView.wallet.BoxCertainty.Uncertain
import org.ergoplatform.settings.ErgoSettings
import scorex.core.utils.ScorexLogging
import scorex.crypto.authds.ADDigest
import sigmastate.interpreter.ContextExtension
import sigmastate.{AvlTreeData, Values}

import scala.collection.Map
import scala.collection.mutable
import scala.util.{Failure, Random, Success}
import scala.concurrent.ExecutionContext.Implicits.global


case class BalancesSnapshot(height: Height, balance: Long, assetBalances: Map[ByteArrayWrapper, Long])


class ErgoWalletActor(settings: ErgoSettings) extends Actor with ScorexLogging {

  import ErgoWalletActor._

  private lazy val seed = settings.walletSettings.seed

  private lazy val scanningInterval = settings.walletSettings.scanningInterval

  private val registry = new Registry

  //todo: pass as a class argument, add to config
  val boxSelector: BoxSelector = DefaultBoxSelector

  private val prover = new ErgoProvingInterpreter(seed)

  private var height = 0
  private var lastBlockUtxoRootHash = ADDigest @@ Array.fill(32)(0: Byte)

  private val trackedAddresses: mutable.Buffer[ErgoAddress] =
    mutable.Buffer(prover.dlogPubkeys: _ *).map(P2PKAddress.apply)

  private val trackedBytes: mutable.Buffer[Array[Byte]] = trackedAddresses.map(_.contentBytes)

  //todo: make resolveUncertainty(boxId, witness)
  private def resolveUncertainty(): Unit = {
    registry.nextUncertain().foreach { uncertainBox =>
      val box = uncertainBox.box

      val lastUtxoDigest = AvlTreeData(lastBlockUtxoRootHash, 32)

      val testingTx = UnsignedErgoLikeTransaction(
        IndexedSeq(new UnsignedInput(box.id)),
        IndexedSeq(new ErgoBoxCandidate(1L, Values.TrueLeaf))
      )

      val context =
        ErgoLikeContext(height + 1, lastUtxoDigest, IndexedSeq(box), testingTx, box, ContextExtension.empty)

      prover.prove(box.proposition, context, testingTx.messageToSign) match {
        case Success(_) =>
          log.info(s"Uncertain box is mine! $uncertainBox")
          val certainBox = uncertainBox.makeCertain()
          registry.makeTransition(uncertainBox, certainBox)
        case Failure(_) =>
        //todo: remove after some time? remove spent after some time?
      }
    }
  }

  def scan(tx: ErgoTransaction, heightOpt: Option[Height]): Boolean = {
    tx.inputs.foreach { inp =>
      val boxId = ByteArrayWrapper(inp.boxId)
      if (registry.registryContains(boxId)) {
        registry.makeTransition(boxId, ProcessSpending(tx, heightOpt))
      }
    }

    tx.outputCandidates.zipWithIndex.count { case (outCandidate, outIndex) =>
      trackedBytes.find(t => outCandidate.propositionBytes.containsSlice(t)) match {
        case Some(_) =>
          val idxShort = outIndex.toShort
          val box = outCandidate.toBox(tx.serializedId, idxShort)
          val bu = heightOpt match {
            case Some(h) => UnspentOnchainBox(tx, idxShort, h, box, Uncertain)
            case None => UnspentOffchainBox(tx, idxShort, box, Uncertain)
          }
          bu.register(registry)
          true
        case None =>
          false
      }
    } > 0
  }

  private def extractFromBlock(fb: ErgoFullBlock): Int = {
    height = fb.header.height
    lastBlockUtxoRootHash = fb.header.stateRoot
    fb.transactions.count(tx => scan(tx, Some(height)))
  }

  def scanLogic: Receive = {
    case ScanOffchain(tx) =>
      if (scan(tx, None)) {
        self ! Resolve
      }

    case Resolve =>
      resolveUncertainty()
      //todo: use non-default executor?
      if (registry.uncertainBoxes.nonEmpty) {
        context.system.scheduler.scheduleOnce(scanningInterval)(self ! Resolve)
      }

    case ScanOnchain(fullBlock) =>
      val txsFound = extractFromBlock(fullBlock)
      (1 to txsFound).foreach(_ => self ! Resolve)

    //todo: update utxo root hash
    case Rollback(heightTo) =>
      height.until(heightTo, -1).foreach { h =>
        val toRemove = registry.confirmedAt(h)
        toRemove.foreach { boxId =>
          registry.removeFromRegistry(boxId).foreach { tb =>
            tb.transitionBack(heightTo) match {
              case Some(newBox) =>
                registry.makeTransition(tb, newBox)
              case None =>
              //todo: should we be here at all?
            }
          }
        }
      }

      height = heightTo
  }


  override def receive: Receive = scanLogic orElse {
    case WatchFor(address) =>
      trackedAddresses.append(address)
      trackedBytes.append(address.contentBytes)

    case ReadBalances(confirmed) =>
      if (confirmed) {
        sender() ! BalancesSnapshot(height, registry.confirmedBalance, registry.confirmedAssetBalances)
      } else {
        sender() ! BalancesSnapshot(height, registry.unconfirmedBalance, registry.unconfirmedAssetBalances)
      }

    case ReadWalletAddresses =>
      sender() ! trackedAddresses.toIndexedSeq

    //generate a transaction paying to a sequence of boxes payTo
    case GenerateTransaction(payTo) =>
      require(prover.dlogPubkeys.nonEmpty, "No public keys in the prover to extract change address from")

      val targetBalance = payTo.map(_.value).sum

      val targetAssets = mutable.Map[ByteArrayWrapper, Long]()

      /* todo: uncomment when sigma-state dependency will be updated from 0.9.5-SNAPSHOT
      payTo.map(_.additionalTokens).foreach { boxTokens =>
        AssetUtils.mergeAssets(targetAssets, boxTokens.map(t => ByteArrayWrapper(t._1) -> t._2).toMap)
      } */

      //we currently do not use off-chain boxes to create a transaction
      def filterFn(bu: UnspentBox) = bu.onchain

      val txOpt = boxSelector.select(registry.unspentBoxes, filterFn, targetBalance, targetAssets.toMap).flatMap { r =>
        val inputs = r.boxes.toIndexedSeq

        val changeAddress = prover.dlogPubkeys(Random.nextInt(prover.dlogPubkeys.size))

        val changeBoxCandidates = r.changeBoxes.map { case (chb, cha) =>

          // todo: uncomment when sigma-state dependency will be updated from 0.9.5-SNAPSHOT
          val assets = IndexedSeq() //cha.map(t => Digest32 @@ t._1.data -> t._2).toIndexedSeq

          new ErgoBoxCandidate(chb, changeAddress, assets)
        }

        val unsignedTx = new UnsignedErgoTransaction(
          inputs.map(_.id).map(id => new UnsignedInput(id)),
          (payTo ++ changeBoxCandidates).toIndexedSeq)

        prover.sign(unsignedTx, inputs, ErgoStateContext(height, lastBlockUtxoRootHash)).toOption
      }

      sender() ! txOpt
  }
}

object ErgoWalletActor {

  private[ErgoWalletActor] case object Resolve

  case class WatchFor(address: ErgoAddress)

  case class ScanOffchain(tx: ErgoTransaction)

  case class ScanOnchain(block: ErgoFullBlock)

  case class Rollback(height: Int)

  case class GenerateTransaction(payTo: Seq[ErgoBoxCandidate])

  case class ReadBalances(confirmed: Boolean)

  case object ReadWalletAddresses

}