package org.ergoplatform.settings

import org.ergoplatform.modifiers.history.{ExtensionCandidate, Header}
import org.ergoplatform.nodeView.state.{ErgoStateContext, VotingData}
import org.ergoplatform.settings.ValidationRules.rulesSpec
import org.ergoplatform.utils.ErgoPropertyTest
import org.ergoplatform.validation.{DisabledRule, ReplacedRule, ValidationRules => VR}
import scorex.crypto.authds.ADDigest

import scala.language.implicitConversions
import scala.util.Try

class VotingSpecification extends ErgoPropertyTest {

  import Parameters._

  private val headerId = scorex.util.bytesToId(Array.fill(32)(0: Byte))

  private val votingEpochLength = 2

  override implicit val votingSettings: VotingSettings =
    VotingSettings(votingEpochLength, softForkEpochs = 2, activationEpochs = 3)

  private val proposedUpdate = ErgoValidationSettingsUpdate(
    Seq(ValidationRules.exDuplicateKeys, ValidationRules.exValueLength),
    Seq(VR.CheckDeserializedScriptType.id -> DisabledRule, VR.CheckValidOpCode.id -> ReplacedRule((VR.FirstRuleId + 11).toShort)))
  private val proposedUpdate2 = ErgoValidationSettingsUpdate(Seq(ValidationRules.fbOperationFailed), Seq())
  val ctx: ErgoStateContext = {
    new ErgoStateContext(Seq.empty, None, genesisStateDigest, LaunchParameters, validationSettingsNoIl, VotingData.empty)(votingSettings)
      .upcoming(org.ergoplatform.mining.group.generator, 0L, settings.chainSettings.initialNBits, Array.fill(3)(0.toByte), emptyVSUpdate, 0.toByte)
  }
  val initialVs: ErgoValidationRules = ctx.validationSettings
  val extensionWithAllParams: ExtensionCandidate = {
    LaunchParameters.toExtensionCandidate ++ initialVs.toExtensionCandidate
  }

  property("correct rule ids") {
    rulesSpec foreach { r =>
      r._1 < org.ergoplatform.validation.ValidationRules.FirstRuleId shouldBe true
    }
  }

  property("ErgoValidationSettings toExtension/fromExtension roundtrip") {
    // initial settings should not be written into Extension at all
    val initial = ErgoValidationRules.initial
    val extension = initial.toExtensionCandidate
    extension.fields.size shouldBe 0
    initial shouldBe ErgoValidationRules.parseExtension(extension).get

    forAll(ergoValidationSettingsGen) { vs =>
      val extension = vs.toExtensionCandidate
      vs shouldBe ErgoValidationRules.parseExtension(extension).get
    }
  }

  property("Parameters should be defined at the beginning of the epoch") {
    val chain = genChain(votingEpochLength * 4).map { b =>
      if (b.header.votingStarts(votingEpochLength)) {
        b.copy(extension = extensionWithAllParams.toExtension(b.header.id))
      } else {
        b
      }
    }
    val validChain = chain.init
    val lastBlock = chain.last
    val invalidExtBlock1 = { // extension does not contain all required params
      lastBlock.copy(extension = lastBlock.extension.copy(
        fields = Seq(Array(0: Byte, 1: Byte) -> Array.fill(4)(2: Byte)))
      )
    }
    val invalidExtBlock2 = { // extension contains redundant parameter
      lastBlock.copy(extension = lastBlock.extension.copy(
        fields = LaunchParameters.toExtensionCandidate.fields :+ Array(0: Byte, 99: Byte) -> Array.fill(4)(2: Byte))
      )
    }
    val invalidExtBlock3 = { // extension does not contain params at all
      lastBlock.copy(extension = lastBlock.extension.copy(fields = Seq()))
    }
    val validCtx = validChain.foldLeft(ctx)((acc, mod) => acc.appendFullBlock(mod, votingSettings).get)
    validCtx.appendFullBlock(invalidExtBlock1, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(invalidExtBlock2, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(invalidExtBlock3, votingSettings) shouldBe 'failure
    validCtx.appendFullBlock(lastBlock, votingSettings) shouldBe 'success
  }


  //Simple checks for votes in header could be found also in NonVerifyADHistorySpecification("Header votes")
  property("simple voting - start - conditions") {
    val kInit = 1000000

    val p: Parameters = Parameters(2, Map(StorageFeeFactorIncrease -> kInit, BlockVersion -> 0), proposedUpdate)
    val vr: VotingData = VotingData.empty
    val esc = new ErgoStateContext(Seq(), None, ADDigest @@ Array.fill(33)(0: Byte), p, validationSettingsNoIl, vr)
    val votes = Array(StorageFeeFactorIncrease, NoParameter, NoParameter)
    val h = defaultHeaderGen.sample.get.copy(height = 2, votes = votes, version = 0: Byte)
    val esc2 = process(esc, p, h).get

    //no quorum gathered - no parameter change
    val he = defaultHeaderGen.sample.get.copy(votes = Array.fill(3)(NoParameter), version = 0: Byte)
    val esc30 = process(esc2, p, he).get
    val esc40 = process(esc30, p, he).get
    esc40.currentParameters.storageFeeFactor shouldBe kInit

    //quorum gathered - parameter change
    val esc31 = process(esc2, p, h.copy(height = 3)).get
    esc31.votingData.epochVotes.find(_._1 == StorageFeeFactorIncrease).get._2 shouldBe 2

    val p4 = Parameters(4, Map(StorageFeeFactorIncrease -> (kInit + Parameters.StorageFeeFactorStep), BlockVersion -> 0), proposedUpdate)
    val esc41 = process(esc31, p4, he.copy(height = 4)).get
    esc41.currentParameters.storageFeeFactor shouldBe (kInit + Parameters.StorageFeeFactorStep)
  }

  /**
    * A test which is ensuring that approved soft-fork activates properly.
    * For the test, we have:
    *   - epoch length is about 2 blocks
    *   - 2 epochs to vote
    *   - 3 epochs to activate the fork
    *
    * So the fork would be activated only if 4 votes out of 4 are for it.
    */
  property("soft fork - w. activation") {

    val p: Parameters = Parameters(1, Map(BlockVersion -> 0), proposedUpdate)
    val vr: VotingData = VotingData.empty
    val esc1 = new ErgoStateContext(Seq(), None, ADDigest @@ Array.fill(33)(0: Byte), p, validationSettings, vr)
    checkValidationSettings(esc1.validationSettings, emptyVSUpdate)
    val forkVote = Array(SoftFork, NoParameter, NoParameter)
    val emptyVotes = Array(NoParameter, NoParameter, NoParameter)

    // Soft-fork vote is proposed @ height == 2
    val h2 = defaultHeaderGen.sample.get.copy(votes = forkVote, version = 0: Byte, height = 2)
    val expectedParameters2 = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 0), proposedUpdate)
    val esc2 = process(esc1, expectedParameters2, h2).get
    esc2.currentParameters.softForkStartingHeight.get shouldBe 2
    esc2.currentParameters.proposedUpdate shouldBe proposedUpdate
    checkValidationSettings(esc2.validationSettings, emptyVSUpdate)

    // wrong parameters: started voting is not reflected in the parameters
    val wrongParameters2 = Parameters(2, Map(BlockVersion -> 0), proposedUpdate)
    process(esc1, wrongParameters2, h2).isFailure shouldBe true

    // wrong parameters: voting just started, but collected votes is more than 0
    val wrongParameters2a = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 1, BlockVersion -> 0), proposedUpdate)
    process(esc1, wrongParameters2a, h2).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters2b = Parameters(2, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 0, BlockVersion -> 0), proposedUpdate)
    process(esc1, wrongParameters2b, h2).isFailure shouldBe true

    // wrong parameters: incorrect block version
    val wrongParameters2c = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 1), proposedUpdate)
    process(esc1, wrongParameters2c, h2).isFailure shouldBe true

    // voting for the fork @ height == 3
    val h3 = h2.copy(height = 3)
    val esc3 = process(esc2, expectedParameters2, h3).get
    esc3.currentParameters.softForkStartingHeight.get shouldBe 2
    esc3.currentParameters.proposedUpdate shouldBe proposedUpdate
    checkValidationSettings(esc3.validationSettings, emptyVSUpdate)


    // voting for the fork @ height == 4
    // new epoch is starting, thus the block should contain number of votes for the fork collected in the previous epoch
    val h4 = h3.copy(height = 4)
    val expectedParameters4 = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 2, BlockVersion -> 0), proposedUpdate)
    val esc4 = process(esc3, expectedParameters4, h4).get

    esc4.currentParameters.softForkStartingHeight.get shouldBe 2
    esc4.currentParameters.softForkVotesCollected.get shouldBe 2
    esc4.currentParameters.proposedUpdate shouldBe proposedUpdate
    checkValidationSettings(esc4.validationSettings, emptyVSUpdate)

    // wrong parameters: voting is not reflected in the parameters
    val wrongParameters4 = Parameters(4, Map(BlockVersion -> 0), proposedUpdate)
    process(esc3, wrongParameters4, h4).isFailure shouldBe true

    // wrong parameters: collected votes value is wrong
    val wrongParameters4a = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0), proposedUpdate)
    process(esc3, wrongParameters4a, h4).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters4b = Parameters(4, Map(SoftForkStartingHeight -> 3, SoftForkVotesCollected -> 2, BlockVersion -> 0), proposedUpdate)
    process(esc3, wrongParameters4b, h4).isFailure shouldBe true

    // voting for the fork @ height == 5
    val h5 = h4.copy(height = 5)
    val esc5 = process(esc4, expectedParameters4, h5).get
    checkValidationSettings(esc5.validationSettings, emptyVSUpdate)

    // voting is finished, and we check collected votes @ height == 6
    val h6 = h5.copy(height = 6, votes = emptyVotes)
    val expectedParameters6 = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 0), proposedUpdate)
    val esc6 = process(esc5, expectedParameters6, h6).get
    checkValidationSettings(esc5.validationSettings, emptyVSUpdate)

    // wrong parameters: voting is not reflected in the parameters
    val wrongParameters6 = Parameters(6, Map(BlockVersion -> 0), proposedUpdate)
    process(esc5, wrongParameters6, h6).isFailure shouldBe true

    // wrong parameters: collected votes value is wrong
    val wrongParameters6a = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 5, BlockVersion -> 0), proposedUpdate)
    process(esc5, wrongParameters6a, h6).isFailure shouldBe true

    // wrong parameters: invalid starting height
    val wrongParameters6b = Parameters(6, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 2, BlockVersion -> 0), proposedUpdate)
    process(esc5, wrongParameters6b, h6).isFailure shouldBe true

    // voting for soft-fork is prohibited @ height == 6
    val h6w = h5.copy(height = 6)
    process(esc5, expectedParameters6, h6w).isFailure shouldBe true

    val esc11 = (7 to 11).foldLeft(esc6) { case (esc, i) =>
      // voting for soft-fork is prohibited during activation period
      val hw = h6.copy(height = i, votes = forkVote)
      process(esc, expectedParameters6, hw).isFailure shouldBe true

      val h = h6.copy(height = i)

      // wrong parameters checks
      if (i % 2 == 0) {
        // wrong parameters: voting is not reflected in the parameters
        val wrongParametersI = Parameters(i, Map(BlockVersion -> 0), proposedUpdate)
        process(esc, wrongParametersI, h).isFailure shouldBe true

        // wrong parameters: collected votes value is wrong
        val wrongParametersIa = Parameters(i, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 5, BlockVersion -> 0), proposedUpdate)
        process(esc, wrongParametersIa, h).isFailure shouldBe true

        // wrong parameters: invalid starting height
        val wrongParametersIb = Parameters(i, Map(SoftForkStartingHeight -> 4, SoftForkVotesCollected -> 4, BlockVersion -> 0), proposedUpdate)
        process(esc, wrongParametersIb, h).isFailure shouldBe true

        // wrong parameters: invalid block version
        val wrongParametersIc = Parameters(i, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1), proposedUpdate)
        process(esc, wrongParametersIc, h).isFailure shouldBe true
      }

      process(esc, expectedParameters6, h).get
    }

    // activation period done @ height = 12, block version is increased, rules to disable are disabled
    val h12 = h6.copy(height = 12, version = 1: Byte)
    val expectedParameters12 = Parameters(12, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1), proposedUpdate)

    val esc12 = process(esc11, expectedParameters12, h12).get
    checkValidationSettings(esc12.validationSettings, proposedUpdate)

    // vote for soft-fork @ activation height
    val h12w = h12.copy(votes = forkVote)
    process(esc11, expectedParameters12, h12w).isFailure shouldBe true

    val h13 = h12.copy(height = 13)
    val esc13 = process(esc12, expectedParameters12, h13).get

    // vote for soft-fork is prohibited before next epoch after activation height
    val h13w = h13.copy(votes = forkVote)
    process(esc12, expectedParameters12, h13w).isFailure shouldBe true

    // voting for soft-fork is possible on the first block of the next epoch after activation height
    val h14 = h13.copy(height = 14, votes = forkVote)
    val expectedParameters14 = Parameters(14, Map(SoftForkStartingHeight -> 14, SoftForkVotesCollected -> 0, BlockVersion -> 1), proposedUpdate2)
    val esc14 = process(esc13, expectedParameters14, h14).get
    checkValidationSettings(esc14.validationSettings, proposedUpdate)

    // next epoch after activation height - soft-fork related parameters are cleared
    val h14b = h13.copy(height = 14, votes = emptyVotes)
    val expectedParameters14a = Parameters(14, Map(BlockVersion -> 1), proposedUpdate2)
    val esc14b = process(esc13, expectedParameters14a, h14b).get
    checkValidationSettings(esc14.validationSettings, proposedUpdate)

    //wrong parameters: no vote for the fork, but parameters are there
    val wrongParameters14 = Parameters(14, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 4, BlockVersion -> 1), proposedUpdate2)
    process(esc13, wrongParameters14, h14b).isFailure shouldBe true
  }

  /**
    * A vote for a soft-fork which is not gathering enough votes to be activated.
    * The voting settings are :
    *   - epoch length is about 2 blocks
    *   - 2 epochs to vote
    *   - 3 epochs to activate the fork
    *
    */
  property("soft fork - unsuccessful voting") {
    val p: Parameters = Parameters(1, Map(BlockVersion -> 0), proposedUpdate)
    val vr: VotingData = VotingData.empty
    val esc1 = new ErgoStateContext(Seq(), None, ADDigest @@ Array.fill(33)(0: Byte), p, validationSettingsNoIl, vr)
    val forkVote = Array(SoftFork, NoParameter, NoParameter)
    val emptyVotes = Array(NoParameter, NoParameter, NoParameter)

    // Soft-fork vote is proposed @ height == 2
    val h2 = defaultHeaderGen.sample.get.copy(votes = forkVote, version = 0: Byte, height = 2)
    val expectedParameters2 = Parameters(2, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 0, BlockVersion -> 0), proposedUpdate)
    val esc2 = process(esc1, expectedParameters2, h2).get
    esc2.currentParameters.softForkStartingHeight.get shouldBe 2

    // voting for the fork @ height == 3
    val h3 = h2.copy(height = 3)
    val esc3 = process(esc2, expectedParameters2, h3).get
    esc3.currentParameters.softForkStartingHeight.get shouldBe 2

    // voting for the fork @ height == 4
    val h4 = h3.copy(height = 4)
    val expectedParameters4 = Parameters(4, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 2, BlockVersion -> 0), proposedUpdate)
    val esc4 = process(esc3, expectedParameters4, h4).get
    esc4.currentParameters.softForkStartingHeight.get shouldBe 2
    esc4.currentParameters.softForkVotesCollected.get shouldBe 2

    // no vote for the fork @ height == 5, so only soft-fork proposal has gathered 75% only
    val h5 = h4.copy(height = 5, votes = emptyVotes)
    val esc5 = process(esc4, expectedParameters4, h5).get

    // first epoch after the voting done, data should still be in the block
    val h6 = h5.copy(height = 6)
    val expectedParameters6 = Parameters(6, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0), proposedUpdate)
    val esc6 = process(esc5, expectedParameters6, h6).get

    // in the first epoch after the voting done, it is prohibited to propose a new voting for a fork
    val h6w = h5.copy(height = 6, votes = forkVote)
    process(esc5, expectedParameters6, h6w).isFailure shouldBe true

    val h7 = h6.copy(height = 7)
    val esc7 = process(esc6, expectedParameters6, h7).get

    //... also prohibited to vote for a fork during the first epoch after the voting done
    val h7w = h6.copy(height = 7, votes = forkVote)
    process(esc6, expectedParameters6, h7w).isFailure shouldBe true

    // a new fork voting is proposed on the first block of the second epoch after voting (which has not gathered enough)
    val h8 = h7.copy(height = 8, votes = forkVote)
    val expectedParameters8 = Parameters(8, Map(SoftForkStartingHeight -> 8, SoftForkVotesCollected -> 0, BlockVersion -> 0), proposedUpdate)
    val esc8 = process(esc7, expectedParameters8, h8).get

    // on the second epoch after voting (not fathered enough) parameters are to be cleared,
    // and block version to be the same
    val h8e = h7.copy(height = 8, votes = emptyVotes)
    val expectedParameters8e = Parameters(8, Map(BlockVersion -> 0), proposedUpdate)
    val esc8e = process(esc7, expectedParameters8e, h8e).get

    // parameters are not cleared
    val wrongParameters8 = Parameters(8, Map(SoftForkStartingHeight -> 2, SoftForkVotesCollected -> 3, BlockVersion -> 0), proposedUpdate)
    process(esc7, wrongParameters8, h8e).isFailure shouldBe true
  }

  private def checkValidationSettings(vs: ErgoValidationRules, updated: ErgoValidationSettingsUpdate): Unit = {
    vs.rules.foreach { r =>
      vs.isActive(r._1) shouldBe !updated.rulesToDisable.contains(r._1)
    }
    updated.statusUpdates.foreach { su =>
      vs.sigmaSettings.getStatus(su._1).get shouldBe su._2
    }
  }

  private def process(esc: ErgoStateContext, p: Parameters, h2: Header): Try[ErgoStateContext] = {
    val upcoming = esc.upcoming(h2.minerPk, h2.timestamp, h2.nBits, h2.votes, proposedUpdate, h2.version)
    val extension = (p.toExtensionCandidate ++ upcoming.validationSettings.toExtensionCandidate).toExtension(headerId)
    esc.process(h2, Some(extension))
  }
}
