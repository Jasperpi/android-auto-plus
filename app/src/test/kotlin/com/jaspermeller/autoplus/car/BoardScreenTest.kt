package com.jaspermeller.autoplus.car

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Looper
import androidx.car.app.OnDoneCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestAppManager
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.jaspermeller.autoplus.FakeDictation
import com.jaspermeller.autoplus.FakeSpeaker
import com.jaspermeller.autoplus.app
import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.Message
import com.jaspermeller.autoplus.signal.PhoneListener
import com.jaspermeller.autoplus.signal.Reading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration

/**
 * Drives the car screen the way Android Auto does, through the Car App Library's own test host:
 * the template it accepts, the surface it hands over, and the taps it reports back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BoardScreenTest {
  private val carContext = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext<Context>())
  private val speaker = FakeSpeaker()
  private val dictation = FakeDictation()
  private val screen = BoardScreen(carContext, speaker, dictation)
  private val controller = ScreenController(screen)
  private val phone get() = carContext.app.phone

  @Before
  fun connectThePhone() {
    phone.listenerConnected(Robolectric.buildService(PhoneListener::class.java).get())
    phone.inbox.put(Reading(
        Conversation("a", "Maya Okafor", "Messages", false, listOf(Message("Maya Okafor", "are you coming", 100L)), canReply = true),
        reply = null,
        markRead = null,
    ))
  }

  private fun idle() = shadowOf(Looper.getMainLooper()).idle()

  /** Long enough after a change of signs for the board to take taps again. */
  private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))

  private fun surface(width: Int, height: Int): SurfaceContainer {
    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    return SurfaceContainer(reader.surface, width, height, 160)
  }

  @Test
  fun theTemplatePassesTheLibrarysOwnChecks() {
    controller.moveToState(Lifecycle.State.RESUMED)

    // Building it runs the library's own checks on the template. Whether a real host then
    // shows it is only known on a head unit.
    val template = screen.onGetTemplate() as NavigationTemplate
    assertEquals(1, template.actionStrip!!.actions.size)
  }

  @Test
  fun theMapStripDeclaresThePanActionThatTurnsOnTouch() {
    val template = screen.onGetTemplate() as NavigationTemplate

    assertEquals(listOf(Action.TYPE_PAN), template.mapActionStrip!!.actions.map { it.type })
  }

  @Test
  fun theScreenAsksForTheDrawingSurface() {
    controller.moveToState(Lifecycle.State.CREATED)

    assertSame(screen, carContext.getCarService(TestAppManager::class.java).surfaceCallback)
  }

  @Test
  fun theHostButtonReadsTheNewestMessageAloud() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val template = screen.onGetTemplate() as NavigationTemplate

    template.actionStrip!!.actions.single().onClickDelegate!!.sendClick(object : OnDoneCallback {})
    idle()

    assertEquals(listOf("Maya Okafor says, are you coming."), speaker.said)
  }

  @Test
  fun aTapOnAConversationSignOpensIt() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    callback.onSurfaceAvailable(surface(1280, 720))
    callback.onStableAreaChanged(Rect(0, 0, 1280, 720))
    idle()

    // The conversations hang in the right-hand column of a wide board.
    callback.onClick(1000f, 150f)
    idle()

    assertEquals(listOf("Maya Okafor says, are you coming."), speaker.said)
  }

  @Test
  fun aSecondTapRightAfterOpeningAConversationIsIgnored() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    callback.onSurfaceAvailable(surface(1280, 720))
    idle()

    callback.onClick(1000f, 150f)
    idle()
    // The same finger, still coming down: it now lands on the reply signs.
    callback.onClick(300f, 330f)
    callback.onClick(1000f, 500f)
    idle()

    assertEquals(false, dictation.listening)
    assertEquals(1, speaker.said.size)
  }

  @Test
  fun aTapOnBareGroundDoesNothing() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    callback.onSurfaceAvailable(surface(1280, 720))
    idle()

    callback.onClick(3f, 3f)
    idle()

    assertTrue(speaker.said.isEmpty())
  }

  @Test
  fun switchingToAnotherAppDropsAReplyInProgress() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    callback.onSurfaceAvailable(surface(1280, 720))
    idle()
    callback.onClick(1000f, 150f)
    idle()
    speaker.finish()
    settle()
    callback.onClick(300f, 330f)
    idle()
    assertTrue(dictation.listening)

    // The driver opens Maps: the screen stops but the session lives on.
    controller.moveToState(Lifecycle.State.CREATED)

    assertEquals(false, dictation.listening)
  }

  @Test
  fun shrinkingTheBoardToACardTooSmallToShowDropsAReplyInProgress() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    callback.onSurfaceAvailable(surface(1280, 720))
    idle()
    callback.onClick(1000f, 150f)
    idle()
    speaker.finish()
    settle()
    callback.onClick(300f, 330f)
    idle()
    assertTrue(dictation.listening)

    // Android Auto moves the app into a small dashboard card: same screen, smaller surface.
    callback.onSurfaceAvailable(surface(300, 200))
    // The next frame is already scheduled a few milliseconds out, for the listening pixels.
    settle()

    assertEquals("the microphone closes when the reply can no longer be seen", false, dictation.listening)
  }

  @Test
  fun unpluggingMidReplyStopsListening() {
    controller.moveToState(Lifecycle.State.RESUMED)
    val callback = carContext.getCarService(TestAppManager::class.java).surfaceCallback!!
    val container = surface(1280, 720)
    callback.onSurfaceAvailable(container)
    idle()
    callback.onClick(1000f, 150f)
    idle()
    speaker.finish()
    settle()
    // "Reply" is the wide green sign under the header.
    callback.onClick(300f, 330f)
    idle()
    assertTrue(dictation.listening)

    callback.onSurfaceDestroyed(container)
    controller.moveToState(Lifecycle.State.DESTROYED)

    assertEquals(false, dictation.listening)
  }
}
