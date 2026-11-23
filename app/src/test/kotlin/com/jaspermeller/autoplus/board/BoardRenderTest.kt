package com.jaspermeller.autoplus.board

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.jaspermeller.autoplus.FakeDictation
import com.jaspermeller.autoplus.FakeSignals
import com.jaspermeller.autoplus.FakeSpeaker
import com.jaspermeller.autoplus.FakeTimer
import com.jaspermeller.autoplus.signal.NowPlaying
import com.jaspermeller.autoplus.talk
import com.jaspermeller.autoplus.voice.Heard
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Paints every scene with the real painter and saves the PNGs under app/build/renders, so the
 * design can be looked at without a car. It also checks what a driver's finger needs: every
 * target is big enough, on the surface, and not on top of another.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class BoardRenderTest {

  private class Surface(val name: String, val width: Int, val height: Int, val dpi: Int, val safe: Rect? = null)

  private val surfaces = listOf(
      // The Ioniq 6's wide panel, with the host's rail taking the left edge.
      Surface("ioniq", 1920, 720, 160, Rect(150, 0, 1920, 720)),
      Surface("hd", 1280, 720, 160),
      Surface("small", 800, 480, 160),
      // The map half of Android Auto's split dashboard.
      Surface("split", 1080, 620, 160),
      Surface("phone", 1080, 1500, 420),
  )

  private val signals = FakeSignals()
  private val speaker = FakeSpeaker()
  private val dictation = FakeDictation()
  private val timer = FakeTimer()
  private lateinit var painter: BoardPainter
  private lateinit var board: BoardModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    painter = BoardPainter(Lettering.load(context), timeOf = { "2:41" })
    board = BoardModel(signals, speaker, dictation, timer)
    signals.track = NowPlaying("Pink + White", "Frank Ocean", "Spotify", playing = true, canPrevious = true, canNext = true, art = art())
    signals.talks = listOf(
        talk("a", "Maya Okafor", "are you still coming tonight", "we saved you a seat", at = 3),
        talk("b", "Mock trial captains", "Scrimmage moved to 4", app = "WhatsApp", at = 2, group = true, senders = listOf("Dev")),
        talk("c", "Dad", "Call when you get there", at = 1),
    )
  }

  @Test
  fun gantry() = eachSurface("gantry") {}

  @Test
  fun gantryAtNight() = eachSurface("gantry-night", night = true) {}

  @Test
  fun gantryWithManyConversations() = eachSurface("gantry-many") {
    signals.talks = signals.talks + listOf(
        talk("d", "Priya", "ok", at = 0),
        talk("e", "ASB officers", "agenda is up", at = 0, group = true),
        talk("f", "Coach Reyes", "bring the binders", at = 0),
    )
  }

  @Test
  fun gantryQuiet() = eachSurface("gantry-quiet") {
    signals.talks = emptyList()
    signals.track = null
  }

  @Test
  fun gantryWithLongTitles() = eachSurface("gantry-long") {
    signals.track = NowPlaying(
        "The Only Living Boy in New York (Live at Carnegie Hall, 1969)", "Simon & Garfunkel with the Philharmonic",
        "Pocket Casts", playing = false, canPrevious = false, canNext = true, art = null)
    signals.talks = listOf(talk("a", "Bartholomew Featherstonehaugh-Montgomery III", "hello", at = 3))
  }

  @Test
  fun setupNeeded() = eachSurface("setup") { signals.ready = false }

  @Test
  fun reading() = eachSurface("thread-reading") { board.open(signals.talks.first()) }

  @Test
  fun ready() = eachSurface("thread-ready") {
    board.open(signals.talks.first())
    speaker.finish()
  }

  @Test
  fun readyAtNight() = eachSurface("thread-ready-night", night = true) {
    board.open(signals.talks.first())
    speaker.finish()
  }

  @Test
  fun cannotReply() = eachSurface("thread-no-reply") {
    board.open(talk("z", "Bank alerts", "Your statement is ready", canReply = false))
    speaker.finish()
  }

  @Test
  fun listening() = eachSurface("thread-listening") {
    board.open(signals.talks.first())
    board.reply()
    dictation.hearing("yes save me a seat", loudness = 0.7f)
  }

  @Test
  fun confirm() = eachSurface("thread-confirm") {
    board.open(signals.talks.first())
    board.reply()
    dictation.hear(Heard.Words("Yes, save me a seat. I'm leaving practice in ten minutes"))
    timer.advance(1000)
  }

  @Test
  fun sent() = eachSurface("thread-sent") {
    board.open(signals.talks.first())
    board.quick("On my way")
  }

  @Test
  fun trouble() = eachSurface("thread-trouble") {
    board.open(signals.talks.first())
    board.reply()
    dictation.hear(Heard.Silence)
  }

  @Test
  fun tapOnASignOpensItsConversation() {
    val frame = Frame(1280, 720, Rect(0, 0, 1280, 720), 1f, night = false, now = 0)
    val bitmap = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
    painter.paint(Canvas(bitmap), frame, board)
    // The right-hand column holds the conversations, newest at the top.
    assertTrue(painter.tap(1000f, 150f, 0, board))
    val scene = board.scene as Scene.Thread
    assertEquals("Maya Okafor", scene.talk.title)
    assertFalse(painter.tap(2f, 2f, 0, board))
  }

  private fun eachSurface(name: String, night: Boolean = false, arrange: () -> Unit) {
    arrange()
    val dir = File(System.getProperty("autoplus.renderDir") ?: "build/renders").apply { mkdirs() }
    for (surface in surfaces) {
      val dp = surface.dpi / 160f
      val safe = surface.safe ?: Rect(0, 0, surface.width, surface.height)
      val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
      painter.paint(Canvas(bitmap), Frame(surface.width, surface.height, safe, dp, night, now = 480), board)
      File(dir, "$name-${surface.name}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
      assertTargetsAreReachable("$name on ${surface.name}", painter.targetBoxes, safe, dp)
    }
  }

  private fun assertTargetsAreReachable(where: String, boxes: List<RectF>, safe: Rect, dp: Float) {
    val bounds = RectF(safe)
    for (box in boxes) {
      assertTrue("$where: target $box leaves the safe area $safe", bounds.contains(box))
      // Android's driving guidance asks for 76dp; a finger at arm's length needs every bit of it.
      assertTrue("$where: target $box is under 76dp", box.width() >= 76 * dp - 0.5f && box.height() >= 76 * dp - 0.5f)
    }
    for (i in boxes.indices) for (j in i + 1 until boxes.size) {
      assertFalse("$where: targets ${boxes[i]} and ${boxes[j]} overlap", RectF.intersects(boxes[i], boxes[j]))
    }
  }

  private fun art(): Bitmap {
    val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
      shader = LinearGradient(0f, 0f, 300f, 300f, Color.rgb(238, 170, 190), Color.rgb(250, 250, 244), Shader.TileMode.CLAMP)
    }
    Canvas(bitmap).drawRect(0f, 0f, 300f, 300f, paint)
    return bitmap
  }
}
