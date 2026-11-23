package com.jaspermeller.autoplus.voice

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech

/** The speaker against the platform's speech engine as Robolectric models it: no sound, real callbacks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TtsSpeakerTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val speaker = TtsSpeaker(context)
  private val ended = ArrayList<Spoken>()

  private val looper get() = shadowOf(Looper.getMainLooper())
  private fun engine(): TextToSpeech = ShadowTextToSpeech.getLastTextToSpeechInstance()

  /** The engine reports in; [thenIdle] false leaves the utterance hanging in mid-air. */
  private fun engineReports(status: Int = TextToSpeech.SUCCESS, thenIdle: Boolean = true) {
    shadowOf(engine()).onInitListener.onInit(status)
    if (thenIdle) looper.idle() else looper.runOneTask()
  }

  @Test
  fun aReadoutWaitsForTheEngineThenSpeaksAndReportsItsEnd() {
    speaker.say("Maya says, hello.") { ended += it }
    assertNull("nothing is spoken before the engine is ready", shadowOf(engine()).lastSpokenText)

    engineReports()

    assertEquals("Maya says, hello.", shadowOf(engine()).lastSpokenText)
    assertEquals(listOf(Spoken.FINISHED), ended)
  }

  @Test
  fun stoppingMidSentenceReportsAnInterruptionOnce() {
    speaker.say("a long message") { ended += it }
    engineReports(thenIdle = false)

    speaker.stop()
    // The engine's own "done" for the cut-off sentence still arrives afterwards.
    looper.idle()

    assertEquals(listOf(Spoken.INTERRUPTED), ended)
  }

  @Test
  fun stoppingBeforeTheEngineIsReadySpeaksNothing() {
    speaker.say("never heard") { ended += it }
    speaker.stop()
    engineReports()

    assertEquals(listOf(Spoken.INTERRUPTED), ended)
    assertNull(shadowOf(engine()).lastSpokenText)
  }

  @Test
  fun aNewReadoutCutsOffTheOneBefore() {
    speaker.say("first") { ended += it }
    engineReports(thenIdle = false)

    val second = ArrayList<Spoken>()
    speaker.say("second") { second += it }
    looper.idle()

    assertEquals(listOf(Spoken.INTERRUPTED), ended)
    assertEquals(listOf(Spoken.FINISHED), second)
  }

  @Test
  fun anEngineThatWillNotStartFailsTheReadoutAndIsReplacedNextTime() {
    speaker.say("hello") { ended += it }
    val dead = engine()
    engineReports(TextToSpeech.ERROR)

    assertEquals(listOf(Spoken.FAILED), ended)
    assertTrue("the dead engine is let go", shadowOf(dead).isShutdown)

    speaker.say("again") { ended += it }
    assertNotSame(dead, engine())
    engineReports()
    assertEquals(listOf(Spoken.FAILED, Spoken.FINISHED), ended)
  }

  @Test
  fun anEngineThatNeverReportsInFailsTheReadoutAndIsReplaced() {
    speaker.say("hello") { ended += it }
    val silent = engine()

    looper.idleFor(java.time.Duration.ofSeconds(15))

    assertEquals(listOf(Spoken.FAILED), ended)
    assertTrue(shadowOf(silent).isShutdown)

    // Its late answer, if it ever comes, is not taken for the next engine's.
    speaker.say("again") { ended += it }
    shadowOf(silent).onInitListener.onInit(TextToSpeech.SUCCESS)
    looper.idle()
    assertNull("the new engine has not reported in yet", shadowOf(engine()).lastSpokenText)
    engineReports()
    assertEquals(listOf(Spoken.FAILED, Spoken.FINISHED), ended)
  }

  @Test
  fun anEngineThatDiesMidSentenceIsNoticedAndTheReadoutFails() {
    speaker.say("hello") { ended += it }
    engineReports(thenIdle = false)
    // The speech service is killed: its callbacks never arrive.
    engine().setOnUtteranceProgressListener(null)
    looper.idle()
    assertTrue(ended.isEmpty())

    looper.idleFor(java.time.Duration.ofSeconds(30))

    assertEquals(listOf(Spoken.FAILED), ended)
  }

  @Test
  fun aRefusedHoldOnTheSoundFailsTheReadoutInsteadOfTalkingOverACall() {
    shadowOf(context.getSystemService(AudioManager::class.java)).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
    speaker.say("hello") { ended += it }
    engineReports()

    assertEquals(listOf(Spoken.FAILED), ended)
    assertNull(shadowOf(engine()).lastSpokenText)
  }
}
