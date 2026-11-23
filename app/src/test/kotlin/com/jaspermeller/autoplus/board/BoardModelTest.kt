package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.FakeDictation
import com.jaspermeller.autoplus.FakeSignals
import com.jaspermeller.autoplus.FakeSpeaker
import com.jaspermeller.autoplus.FakeTimer
import com.jaspermeller.autoplus.signal.Message
import com.jaspermeller.autoplus.talk
import com.jaspermeller.autoplus.voice.Heard
import com.jaspermeller.autoplus.voice.Spoken
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
  fun aThreadCutDownToItsLastFewMessagesIsNotMarkedRead() {
    val long = talk("l", "Maya", "one", "two", "three", "four", "five", "six", "seven")
    board.open(long)
    assertTrue(speaker.said.single().startsWith("7 messages from Maya. Here are the last 5."))

    speaker.finish()

    assertEquals(Phase.Ready, phase)
    assertTrue("two messages were never spoken, so the phone keeps them unread", signals.heardKeys.isEmpty())
  }

  @Test
  fun aReadoutThatCannotBeSpokenSaysSoAndLeavesTheMessageUnread() {
    board.open(maya)
    speaker.finish(Spoken.FAILED)

    assertEquals(Phase.Trouble("Could not read this aloud", canRetry = false), phase)
    assertTrue(signals.heardKeys.isEmpty())
  }

  @Test
  fun aReadoutCutOffByTheOtherBoardIsNotMarkedHeard() {
    board.open(maya)
    speaker.finish(Spoken.INTERRUPTED)

    assertEquals(Phase.Ready, phase)
    assertTrue(signals.heardKeys.isEmpty())
  }

  @Test
  fun aBoardOnlyEverStopsItsOwnSpeech() {
    val phoneBoard = BoardModel(signals, speaker, FakeDictation(), FakeTimer())
    board.open(maya)
    val stopsBefore = speaker.stops

    // The phone's screen times out while the car is still reading.
    phoneBoard.close()

    assertEquals(stopsBefore, speaker.stops)
    speaker.finish()
    assertEquals(listOf("a"), signals.heardKeys)
  }

  @Test
  fun aReadyMadeReplyIsHeldBackWhenSomethingNewHasArrived() {
    board.open(maya)
    speaker.finish()
    // The phone posts the thread again with a new message while the reply signs are up.
    val updated = maya.copy(messages = maya.messages + Message("Maya", "actually don't come", 99L))
    signals.talks = listOf(updated, dad)

    board.quick("On my way")

    assertTrue("nothing is sent past a message the driver has not heard", signals.sent.isEmpty())
    assertEquals(updated, (board.scene as Scene.Thread).talk)
    assertEquals(Phase.Reading, phase)
    assertTrue("the driver is told first that nothing went out", speaker.said.last().startsWith("Your reply was not sent. 3 messages from Maya."))
    assertTrue(speaker.said.last().endsWith("actually don't come."))
  }

  @Test
  fun tappingAgainAfterAReplyWasHeldBackCannotAnswerTheNewMessageUnheard() {
    board.open(maya)
    speaker.finish()
    val updated = maya.copy(messages = maya.messages + Message("Maya", "actually don't come", 99L))
    signals.talks = listOf(updated, dad)
    board.quick("On my way")
    val stopsBefore = speaker.stops

    // The signs did not change to "Sent", so the driver taps the same reply again.
    board.quick("On my way")
    board.reply()

    assertTrue(signals.sent.isEmpty())
    assertEquals("the new message is still being read", Phase.Reading, phase)
    assertEquals("and was not cut off", stopsBefore, speaker.stops)
    assertFalse(dictation.listening)

    // Once it has been heard, replying works as usual.
    speaker.finish()
    board.quick("OK")
    assertEquals(listOf("a" to "OK"), signals.sent)
  }

  @Test
  fun aSpokenReplyIsHeldBackWhenSomethingNewArrivesDuringTheCountdown() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("See you at six"))
    timer.advance(2000)
    val updated = maya.copy(messages = maya.messages + Message("Maya", "make it seven", 99L))
    signals.talks = listOf(updated, dad)

    timer.advance(2000)

    assertTrue(signals.sent.isEmpty())
    assertEquals(Phase.Reading, phase)
    assertTrue(speaker.said.last().startsWith("Your reply was not sent. "))
    assertTrue(speaker.said.last().endsWith("make it seven."))
  }

  @Test
  fun aRepostThatOnlyChangesTheTimesDoesNotHoldAReplyBack() {
    board.open(maya)
    // An app that sends no message times: every repost stamps them afresh.
    signals.talks = listOf(maya.copy(messages = maya.messages.map { it.copy(at = it.at + 5000) }), dad)

    board.quick("On my way")

    assertEquals(listOf("a" to "On my way"), signals.sent)
  }

  @Test
  fun aDoubleTapOnAReadyMadeReplySendsItOnce() {
    board.open(maya)
    board.quick("On my way")
    // The second tap lands on signs the painter has not yet redrawn.
    board.quick("On my way")
    board.quick("Yes")

    assertEquals(listOf("a" to "On my way"), signals.sent)
  }

  @Test
  fun aSecondTapOnReplyDoesNotRestartListening() {
    board.open(maya)
    board.reply()
    dictation.hearing("yes save")
    board.reply()

    assertEquals("the words so far are kept", Phase.Listening("yes save", 0.6f), phase)
    assertEquals(1, dictation.starts)
  }

  @Test
  fun leavingTheScreenDropsAReplyThatHasNotSent() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    timer.advance(2000)

    board.pause()

    assertEquals(Phase.Ready, phase)
    timer.advance(10_000)
    assertTrue("nothing sends while the driver cannot see the board", signals.sent.isEmpty())
  }

  @Test
  fun leavingDuringTheCountdownSaysTheReplyDidNotGo() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    timer.advance(1000)

    board.pause()

    assertEquals("Your reply was not sent.", speaker.said.last())
    timer.advance(10_000)
    assertTrue(signals.sent.isEmpty())
  }

  @Test
  fun whenTheNotSentNoticeCannotBeSpokenTheBoardCarriesIt() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    board.pause()

    // A call took the screen, and it has the sound too.
    speaker.finish(Spoken.FAILED)

    assertEquals(Phase.Trouble("Your reply was not sent", canRetry = false), phase)
  }

  @Test
  fun aSpokenNotSentNoticeLeavesTheBoardAsItWas() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    board.pause()

    speaker.finish()

    assertEquals(Phase.Ready, phase)
  }

  @Test
  fun leavingWhileStillListeningDropsTheReplyWithoutAnAnnouncement() {
    board.open(maya)
    speaker.finish()
    board.reply()
    val saidBefore = speaker.said.size

    board.pause()

    assertEquals("nothing had been composed yet", saidBefore, speaker.said.size)
  }

  @Test
  fun unpluggingStopsTheNotSentAnnouncementToo() {
    board.open(maya)
    board.reply()
    dictation.hear(Heard.Words("On my way"))
    board.pause()
    val stopsBefore = speaker.stops

    board.close()

    assertEquals(stopsBefore + 1, speaker.stops)
    assertEquals("an unplug is not a failed notice", Scene.Gantry, board.scene)
  }

  @Test
  fun aHeldReplyThatCannotBeReadOutSaysOnTheBoardThatItWasNotSent() {
    board.open(maya)
    speaker.finish()
    signals.talks = listOf(maya.copy(messages = maya.messages + Message("Maya", "actually don't come", 99L)), dad)
    board.quick("On my way")

    // The phone will not give up the sound (a call): the spoken notice never happens.
    speaker.finish(Spoken.FAILED)

    assertEquals(Phase.Trouble("Reply not sent. Could not read the new message", canRetry = false), phase)
    assertTrue(board.replyHeld)
  }

  @Test
  fun aHeldReplyIsReleasedOnceTheNewMessageHasBeenHeard() {
    board.open(maya)
    speaker.finish()
    signals.talks = listOf(maya.copy(messages = maya.messages + Message("Maya", "actually don't come", 99L)), dad)
    board.quick("On my way")
    assertTrue(board.replyHeld)

    // Cut off by a call: still held, and Hear again is the way forward.
    speaker.finish(Spoken.INTERRUPTED)
    assertTrue(board.replyHeld)
    board.hearAgain()
    assertTrue(board.replyHeld)
    speaker.finish()

    assertFalse(board.replyHeld)
  }

  @Test
  fun aTapOnAStaleCancelCannotCutOffAReadout() {
    board.open(maya)
    val stopsBefore = speaker.stops

    board.cancelReply()

    assertEquals(Phase.Reading, phase)
    assertEquals(stopsBefore, speaker.stops)
  }

  @Test
  fun aTapOnAStaleCancelCannotUndoSent() {
    board.open(maya)
    board.quick("On my way")

    board.cancelReply()

    assertEquals(Phase.Sent, phase)
  }

  @Test
  fun leavingTheScreenClosesTheMicrophone() {
    board.open(maya)
    board.reply()

    board.pause()

    assertFalse(dictation.listening)
    assertEquals(Phase.Ready, phase)
  }

  @Test
  fun leavingTheScreenLetsAReadoutFinish() {
    board.open(maya)
    val stopsBefore = speaker.stops

    board.pause()

    assertEquals(Phase.Reading, phase)
    assertEquals(stopsBefore, speaker.stops)
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
