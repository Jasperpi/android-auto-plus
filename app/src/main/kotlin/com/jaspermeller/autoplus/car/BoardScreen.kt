package com.jaspermeller.autoplus.car

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
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
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

  init {
    board.onChange = ::redraw
    lifecycle.addObserver(this)
  }

  override fun onCreate(owner: LifecycleOwner) {
    carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
    phone.watch(phoneChanged)
  }

  override fun onDestroy(owner: LifecycleOwner) {
    phone.unwatch(phoneChanged)
    board.close()
    handler.removeCallbacks(drawFrame)
    surface = null
  }

  override fun onGetTemplate(): Template {
    // The template insists on one host-drawn button. Make it the most useful one: read the newest message.
    val read = Action.Builder()
        .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_read)).build())
        .setOnClickListener { board.readNewest() }
        .build()
    return NavigationTemplate.Builder()
        .setActionStrip(ActionStrip.Builder().addAction(read).build())
        .build()
  }

  fun redraw() {
    if (framePending) return
    framePending = true
    handler.post(drawFrame)
  }

  // ---- SurfaceCallback: the host calls these on the main thread.

  override fun onSurfaceAvailable(container: SurfaceContainer) {
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
  }

  override fun onClick(x: Float, y: Float) {
    if (painter.tap(x, y, SystemClock.uptimeMillis(), board)) redraw()
  }

  private fun draw() {
    framePending = false
    val target = surface?.takeIf { it.isValid } ?: return
    // The stable area never changes as host buttons come and go, so signs do not jump around.
    val safe = when {
      !stable.isEmpty -> stable
      !visible.isEmpty -> visible
      else -> Rect(0, 0, width, height)
    }
    val frame = Frame(width, height, safe, dpi / 160f, carContext.isDarkMode, SystemClock.uptimeMillis(), motionAllowed(carContext))
    val canvas = try {
      target.lockHardwareCanvas()
    } catch (gone: RuntimeException) {
      // The car was unplugged between the check and the lock.
      return
    }
    val moving = try {
      painter.paint(canvas, frame, board)
    } finally {
      target.unlockCanvasAndPost(canvas)
    }
    if (moving) {
      framePending = true
      handler.postDelayed(drawFrame, FRAME_MS)
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
