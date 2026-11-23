package com.jaspermeller.autoplus.car

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.jaspermeller.autoplus.R
import com.jaspermeller.autoplus.app
import com.jaspermeller.autoplus.board.BoardModel
import com.jaspermeller.autoplus.board.BoardPainter
import com.jaspermeller.autoplus.board.Frame
import com.jaspermeller.autoplus.board.Lettering
import com.jaspermeller.autoplus.voice.Dictation
import com.jaspermeller.autoplus.voice.RecognizerDictation
import com.jaspermeller.autoplus.voice.Speaker
import com.jaspermeller.autoplus.voice.Timer

/**
 * The board on the car's display.
 *
 * Android Auto only lets an app draw freely on the surface behind a navigation template, so
 * that is the template this screen returns; everything the driver sees is painted on that
 * surface by [BoardPainter], and the host reports taps back as surface coordinates.
 */
class BoardScreen(
    carContext: CarContext,
    speaker: Speaker = carContext.app.speaker,
    dictation: Dictation = RecognizerDictation(carContext, CarMic(carContext)),
) : Screen(carContext), SurfaceCallback, DefaultLifecycleObserver {
  private val phone = carContext.app.phone
  private val handler = Handler(Looper.getMainLooper())
  private val painter = BoardPainter(Lettering.load(carContext), timeFormat(carContext))
  private val board = BoardModel(
      signals = phone,
      speaker = speaker,
      dictation = dictation,
      timer = HandlerTimer(handler),
  )
  private val phoneChanged: () -> Unit = { board.signalsChanged() }
  private val drawFrame = Runnable { draw() }

  private var surface: Surface? = null
  private var width = 0
  private var height = 0
  private var dpi = 160
  private val visible = Rect()
  private val stable = Rect()
  private var framePending = false
  private var motion = true

  init {
    board.onChange = ::redraw
    lifecycle.addObserver(this)
  }

  override fun onCreate(owner: LifecycleOwner) {
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    phone.watch(phoneChanged)
  }

  override fun onStart(owner: LifecycleOwner) {
    // Read once per appearance, not once per frame.
    motion = motionAllowed(carContext)
    redraw()
  }

  /** Another app took the car screen. */
  override fun onStop(owner: LifecycleOwner) {
    board.pause()
  }

  override fun onDestroy(owner: LifecycleOwner) {
    phone.unwatch(phoneChanged)
    board.close()
    handler.removeCallbacks(drawFrame)
    surface = null
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
  }

  override fun onGetTemplate(): Template {
    // The template insists on one host-drawn button. Make it the most useful one: read the newest message.
    val read = Action.Builder()
        // Tinted by the host, so the mark stays visible on its day and night button colours.
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_read)).setTint(CarColor.DEFAULT).build())
        .setOnClickListener { board.readNewest() }
        .build()
    return NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(read).build())
        // The library says a host withholds surface gestures from an app that leaves the pan
        // action off its map strip. It does not say whether a plain tap counts, and every sign
        // here depends on taps, so the action is declared.
        .setMapActionStrip(ActionStrip.Builder().addAction(Action.PAN).build())
        .build()
  }

  fun redraw() {
    if (framePending) return
    framePending = true
    handler.post(drawFrame)
  }

  // ---- SurfaceCallback: the host calls these on the main thread.

  override fun onSurfaceAvailable(container: SurfaceContainer) {
    if (container.width != width || container.height != height) {
      // The old safe areas belong to the old size (full screen against a split-screen tile);
      // until the host sends new ones, the whole new surface is the best guess.
      visible.setEmpty()
      stable.setEmpty()
    }
    surface = container.surface
    width = container.width
    height = container.height
    dpi = container.dpi.takeIf { it > 0 } ?: 160
    redraw()
  }

  override fun onVisibleAreaChanged(area: Rect) {
    visible.set(area)
    redraw()
  }

  override fun onStableAreaChanged(area: Rect) {
    stable.set(area)
    redraw()
  }

  override fun onSurfaceDestroyed(container: SurfaceContainer) {
    surface = null
    handler.removeCallbacks(drawFrame)
    framePending = false
    // With nothing to show it on, a reply cannot be checked before it sends.
    board.pause()
  }

  override fun onClick(x: Float, y: Float) {
    // A tap the host delivers after another app has taken the screen is not one the driver can
    // see the result of. Acting on it could open the microphone behind that app.
    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
    if (painter.tap(x, y, SystemClock.uptimeMillis(), board)) redraw()
  }

  private fun draw() {
    framePending = false
    val target = surface?.takeIf { it.isValid } ?: return
    if (width <= 0 || height <= 0) return
    // The stable area never changes as host buttons come and go, so signs do not jump around.
    val safe = when {
      !stable.isEmpty -> stable
      !visible.isEmpty -> visible
      else -> Rect(0, 0, width, height)
    }
    val frame = Frame(width, height, safe, dpi / 160f, carContext.isDarkMode, SystemClock.uptimeMillis(), motion)
    val canvas = lock(target) ?: return
    val moving = try {
      painter.paint(canvas, frame, board)
    } finally {
      try {
        target.unlockCanvasAndPost(canvas)
      } catch (gone: RuntimeException) {
        // Unplugged mid-frame: the surface was released while it was being painted.
        surface = null
      }
    }
    // The host squeezed the board into a card too small to show it (it resizes the surface
    // rather than stopping the screen). A reply in progress can no longer be seen or cancelled.
    if (!painter.showing) board.pause()
    if (moving && surface != null) {
      framePending = true
      handler.postDelayed(drawFrame, FRAME_MS)
    }
  }

  /** A hardware canvas where the surface allows one, a software canvas where it does not. */
  private fun lock(target: Surface): Canvas? =
      try {
        target.lockHardwareCanvas()
      } catch (refused: RuntimeException) {
        try {
          target.lockCanvas(null)
        } catch (gone: Exception) {
          // The car was unplugged between the check and the lock, or the surface has no buffer to give.
          null
        }
      }

  private companion object {
    /** Fifteen frames a second carries the pixel chase and costs the video link very little. */
    const val FRAME_MS = 66L
  }
}

class HandlerTimer(private val handler: Handler) : Timer {
  override fun after(ms: Long, run: () -> Unit): () -> Unit {
    val task = Runnable(run)
    handler.postDelayed(task, ms)
    return { handler.removeCallbacks(task) }
  }
}
