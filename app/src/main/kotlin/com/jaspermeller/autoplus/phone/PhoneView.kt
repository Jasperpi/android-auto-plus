package com.jaspermeller.autoplus.phone

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import com.jaspermeller.autoplus.app
import com.jaspermeller.autoplus.board.BoardModel
import com.jaspermeller.autoplus.board.BoardPainter
import com.jaspermeller.autoplus.board.Frame
import com.jaspermeller.autoplus.board.Lettering
import com.jaspermeller.autoplus.board.Palette
import com.jaspermeller.autoplus.board.Scene
import com.jaspermeller.autoplus.board.SignKit
import com.jaspermeller.autoplus.car.HandlerTimer
import com.jaspermeller.autoplus.car.motionAllowed
import com.jaspermeller.autoplus.car.timeFormat
import com.jaspermeller.autoplus.voice.RecognizerDictation
import kotlin.math.abs

/**
 * The phone screen, drawn with the same signs as the car: the live board on top (it works, so
 * it doubles as a phone-mount mode), and under it the setup list as regulatory signs.
 */
@SuppressLint("ViewConstructor")
class PhoneView(
    context: Context,
    private val currentSteps: () -> List<Step>,
    private val open: (Step.Kind) -> Unit,
) : View(context) {
  private val lettering = Lettering.load(context)
  private val kit = SignKit(lettering)
  private val painter = BoardPainter(lettering, timeFormat(context), setupHint = "Turn on notification access below.")
  private val handler = Handler(Looper.getMainLooper())
  private val board = BoardModel(
      signals = context.app.phone,
      speaker = context.app.speaker,
      dictation = RecognizerDictation(context),
      timer = HandlerTimer(handler),
  )
  private val phoneChanged: () -> Unit = {
    // Notification access shows up here as the listener binding, so the list is re-read too.
    refresh()
    board.signalsChanged()
  }
  private val boardArea = Rect()
  private val stepBoxes = ArrayList<Pair<RectF, Step.Kind>>()
  private val bars = Rect()
  private var steps: List<Step> = emptyList()
  private var motion = true
  private var downX = 0f
  private var downY = 0f

  init {
    board.onChange = ::invalidate
    setOnApplyWindowInsetsListener { _, insets ->
      val edge = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
      bars.set(edge.left, edge.top, edge.right, edge.bottom)
      invalidate()
      insets
    }
  }

  /** Where a step's sign was last drawn, so a test can press it without guessing at the layout. */
  internal fun boxOf(kind: Step.Kind): RectF? = stepBoxes.firstOrNull { it.second == kind }?.first

  /** Re-reads which setup steps are done. */
  fun refresh() {
    steps = currentSteps()
    invalidate()
  }

  fun start() {
    context.app.phone.watch(phoneChanged)
    motion = motionAllowed(context)
    // The inbox may have changed while the screen was off.
    board.signalsChanged()
    refresh()
  }

  /** Steps out of a conversation. False when the board is already home. */
  fun back(): Boolean {
    if (board.scene !is Scene.Thread) return false
    board.back()
    return true
  }

  fun stop() {
    context.app.phone.unwatch(phoneChanged)
    board.close()
  }

  override fun onDraw(canvas: Canvas) {
    val dp = resources.displayMetrics.density
    val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val left = bars.left
    val top = bars.top
    val right = width - bars.right
    val bottom = height - bars.bottom
    val landscape = right - left > bottom - top

    // In landscape (a phone on a dash mount) the board takes the whole screen. Upright, it gets
    // most of the screen once it has something to show, and a single sign's worth until then.
    val share = if (board.ready) 0.64f else 0.24f
    val boardBottom = if (landscape) bottom else top + ((bottom - top) * share).toInt()
    boardArea.set(left, top, right, boardBottom)
    val moving = painter.paint(
        canvas,
        Frame(width, height, boardArea, dp, night, SystemClock.uptimeMillis(), motion),
        board,
    )
    if (moving) postInvalidateDelayed(FRAME_MS)

    stepBoxes.clear()
    if (landscape || steps.isEmpty()) return
    kit.dp = dp
    kit.colors = if (night) Palette.NIGHT else Palette.DAY
    val colors = kit.colors
    val margin = SignKit.MARGIN * dp
    val gap = SignKit.GAP * dp * 0.5f
    val tabH = TAB * dp
    val area = RectF(left + margin, boardBottom.toFloat(), right - margin, bottom - margin)
    // A finished step shrinks to its name; the steps still to do share what is left, so the
    // explanation is there exactly when it is needed.
    val brief = 46 * dp
    val finished = steps.count { it.done == true }
    val tabs = steps.count { it.done != null }
    val open = steps.size - finished
    val spare = area.height() - gap * (steps.size - 1) - tabs * tabH - finished * brief
    val full = if (open == 0) 0f else (spare / open).coerceIn(brief, 124 * dp)
    var y = area.top
    for (step in steps) {
      if (step.done != null) y += tabH
      val body = RectF(area.left, y, area.right, y + if (step.done == true) brief else full)
      when (step.done) {
        true -> kit.tab(canvas, body, "On", colors.green, right = true, height = tabH)
        false -> kit.tab(canvas, body, "Off", colors.red, right = true, height = tabH)
        null -> Unit
      }
      kit.sign(canvas, body, colors.paper, colors.ink)
      val why = if (step.done == true || body.height() < 68 * dp) "" else step.why
      val size = (body.height() * 0.26f).coerceIn(17 * dp, 24 * dp)
      kit.titleBlock(canvas, kit.inside(body), step.title, why, size, colors.ink, secondLines = 3)
      stepBoxes += body to step.kind
      y = body.bottom + gap
    }
  }

  @SuppressLint("ClickableViewAccessibility")
  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        downX = event.x
        downY = event.y
      }
      MotionEvent.ACTION_UP -> {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) return true
        if (painter.tap(event.x, event.y, SystemClock.uptimeMillis(), board)) {
          invalidate()
        } else {
          stepBoxes.firstOrNull { it.first.contains(event.x, event.y) }?.let { open(it.second) }
        }
      }
    }
    return true
  }

  private companion object {
    const val FRAME_MS = 66L

    /** The setup list is read in the hand, not at arm's length, so its plaques are smaller. */
    const val TAB = 26f
  }
}
