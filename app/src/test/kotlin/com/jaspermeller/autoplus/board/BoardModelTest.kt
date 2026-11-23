package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.FakeDictation
import com.jaspermeller.autoplus.FakeSignals
import com.jaspermeller.autoplus.FakeSpeaker
import com.jaspermeller.autoplus.FakeTimer
import com.jaspermeller.autoplus.talk
import com.jaspermeller.autoplus.voice.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BoardModelTest {
  private val signals = FakeSignals()
  private val speaker = FakeSpeaker()
  private val dictation = FakeDictation()
  private val timer = FakeTimer()
  private val board = BoardModel(signals, speaker, dictation, timer)
  private val maya = talk("a", "Maya", "are you coming", "we saved you a seat")
  private val dad = talk("b", "Dad", "Call when you get there")

  @Before
  fun setUp() {
    signals.talks = listOf(maya, dad)
  }

  private val phase: Phase get() = (board.scene as Scene.Thread).phase

  @Test
  fun openingAConversationReadsItAloudAndMarksItHeard() {
    board.open(maya)

    assertEquals(Phase.Reading, phase)
    assertEquals(listOf("2 messages from Maya. are you coming. we saved you a seat."), speaker.said)
    assertTrue(signals.heardKeys.isEmpty())

    speaker.finish()

    assertEquals(Phase.Ready, phase)
    assertEquals(listOf("a"), signals.heardKeys)
  }

  @Test
  fun leavingMidReadoutDoesNotMarkItHeard() {
    board.open(maya)
    board.back()

    assertEquals(Scene.Gantry, board.scene)
    assertTrue(signals.heardKeys.isEmpty())
    assertEquals(listOf(maya, dad), signals.talks)
  }

  @Test
  fun aSpokenReplyCountsDownFourSecondsThenSendsAndReturnsHome() {
    board.open(maya)
    speaker.finish()
    board.reply()
    assertTrue(dictation.listening)
    assertEquals(Phase.Listening(), phase)

    dictation.hearing("yes save", loudness = 0.5f)
    assertEquals(Phase.Listening("yes save", 0.5f), phase)

    dictation.hear(Heard.Words("Yes, save me a seat"))
    assertEquals(Phase.Confirm("Yes, save me a seat", 4), phase)

    timer.advance(3000)
    assertEquals(Phase.Confirm("Yes, save me a seat", 1), phase)
    assertTrue(signals.sent.isEmpty())

    timer.advance(1000)
    assertEquals(listOf("a" to "Yes, save me a seat"), signals.sent)
    assertEquals(Phase.Sent, phase)

    timer.advance(BoardModel.SENT_MS)
    assertEquals(Scene.Gantry, board.scene)
  }

  @Test
  fun sendNowSkipsTheCountdownAndSendsExactlyOnce() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    board.sendNow()

    assertEquals(listOf("a" to "On my way"), signals.sent)
    timer.advance(10_000)
    assertEquals(1, signals.sent.size)
  }

  @Test
  fun cancellingDuringTheCountdownSendsNothing() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("wrong words"))
    timer.advance(2000)
    board.cancelReply()

    assertEquals(Phase.Ready, phase)
    timer.advance(10_000)
    assertTrue(signals.sent.isEmpty())
  }

  @Test
  fun sayingItAgainDropsTheFirstAttempt() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("wrong words"))
    board.reply()
    assertEquals(Phase.Listening(), phase)

    // The first countdown must not fire while the driver is speaking again.
    timer.advance(10_000)
    assertTrue(signals.sent.isEmpty())

    dictation.hear(Heard.Words("right words"))
    timer.advance(4000)
    assertEquals(listOf("a" to "right words"), signals.sent)
  }

  @Test
  fun aQuickReplySendsAtOnce() {
    board.open(maya)
    board.quick("On my way")

    assertEquals(listOf("a" to "On my way"), signals.sent)
    assertEquals(Phase.Sent, phase)
  }

  @Test
  fun silenceOffersAnotherTry() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Silence)

    assertEquals(Phase.Trouble("Did not catch that", canRetry = true), phase)
    board.reply()
    assertEquals(Phase.Listening(), phase)
  }

  @Test
  fun aBlockedMicrophoneSaysWhyAndDoesNotOfferARetry() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Blocked("Allow the microphone in Auto++ on your phone"))

    assertEquals(Phase.Trouble("Allow the microphone in Auto++ on your phone", canRetry = false), phase)
  }

  @Test
  fun aReplyTheAppRefusesIsReportedNotDropped() {
    signals.accepts = false
    board.open(maya)
    board.quick("Yes")

    assertTrue(phase is Phase.Trouble)
    assertTrue(signals.sent.isEmpty())
    timer.advance(10_000)
    assertTrue("stays on the conversation so the driver sees it failed", board.scene is Scene.Thread)
  }

  @Test
  fun aConversationThatCannotTakeRepliesIgnoresReplyAttempts() {
    val alerts = talk("z", "Bank alerts", "Statement ready", canReply = false)
    board.open(alerts)
    speaker.finish()
    board.reply()
    board.quick("Yes")

    assertEquals(Phase.Ready, phase)
    assertFalse(dictation.listening)
    assertTrue(signals.sent.isEmpty())
  }

  @Test
  fun openingAnotherConversationAbandonsTheFirstReadout() {
    board.open(maya)
    board.open(dad)

    assertEquals("Dad", (board.scene as Scene.Thread).talk.title)
    assertEquals(Phase.Reading, phase)
    assertTrue("Maya was cut off, so she is still unread", signals.heardKeys.isEmpty())

    speaker.finish()
    assertEquals(listOf("b"), signals.heardKeys)
  }

  @Test
  fun hearAgainRereadsTheSameConversation() {
    board.open(maya)
    speaker.finish()
    board.hearAgain()

    assertEquals(Phase.Reading, phase)
    assertEquals(2, speaker.said.size)
    assertEquals(speaker.said[0], speaker.said[1])
  }

  @Test
  fun theHostButtonReadsWhoeverWroteLast() {
    board.readNewest()
    assertEquals("Maya", (board.scene as Scene.Thread).talk.title)
  }

  @Test
  fun theHostButtonDoesNothingWithAnEmptyInbox() {
    signals.talks = emptyList()
    board.readNewest()
    assertEquals(Scene.Gantry, board.scene)
  }

  @Test
  fun pagingWalksThroughConversationsAndWraps() {
    signals.talks = (1..5).map { talk("k$it", "Person $it", "hi") }

    board.nextPage(perPage = 2)
    assertEquals(2, board.firstShown)
    board.nextPage(perPage = 2)
    assertEquals(4, board.firstShown)
    board.nextPage(perPage = 2)
    assertEquals(0, board.firstShown)
  }

  @Test
  fun aShrinkingInboxResetsThePage() {
    signals.talks = (1..5).map { talk("k$it", "Person $it", "hi") }
    board.nextPage(perPage = 2)
    board.nextPage(perPage = 2)

    signals.talks = signals.talks.take(2)
    board.signalsChanged()

    assertEquals(0, board.firstShown)
  }

  @Test
  fun closingGoesQuiet() {
    board.open(maya)
    board.reply()
    board.close()

    assertEquals(Scene.Gantry, board.scene)
    assertFalse(dictation.listening)
    timer.advance(10_000)
    assertTrue(signals.sent.isEmpty())
  }

  @Test
  fun mediaButtonsReachThePlayer() {
    board.previous()
    board.toggle()
    board.next()
    assertEquals(listOf("previous", "toggle", "next"), signals.presses)
  }

  @Test
  fun everyChangeAsksForARedraw() {
    var redraws = 0
    board.onChange = { redraws++ }
    board.open(maya)
    speaker.finish()
    board.reply()
    assertEquals(3, redraws)
  }
}
