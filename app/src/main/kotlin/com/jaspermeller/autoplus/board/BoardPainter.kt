package com.jaspermeller.autoplus.board

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import com.jaspermeller.autoplus.board.SignKit.Companion.EDGE
import com.jaspermeller.autoplus.board.SignKit.Companion.GAP
import com.jaspermeller.autoplus.board.SignKit.Companion.MARGIN
import com.jaspermeller.autoplus.board.SignKit.Companion.PAD
import com.jaspermeller.autoplus.board.SignKit.Companion.TAB
import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.NowPlaying
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** One frame's worth of surface facts. [safe] is the part the host promises not to cover. */
class Frame(
    val width: Int,
    val height: Int,
    val safe: Rect,
    /** Pixels per dp on this surface. */
    val dp: Float,
    val night: Boolean,
    val now: Long,
    /** False when the phone has animations switched off. */
    val motion: Boolean = true,
)

/**
 * Draws the board as highway signs and remembers where each one is so a tap can find it.
 *
 * The same painter serves the car surface, the phone preview and the render tests, so what a
 * test saves to a PNG is what the car shows. It keeps no state between frames beyond the tap
 * targets and the brief flash that confirms a tap.
 */
class BoardPainter(
    lettering: Lettering,
    private val timeOf: (Long) -> String,
    /** What to do about missing notification access, worded for where this board is showing. */
    private val setupHint: String = "Open Auto++ on your phone and turn on notification access.",
) {

  private class Target(val box: RectF, val act: () -> Unit)

  /** A sign that is also a button. */
  private class Plate(
      val label: String,
      val ground: Int,
      val edge: Int,
      val ink: Int,
      val weight: Float = 1f,
      val act: () -> Unit,
  )

  private val kit = SignKit(lettering)
  private val fill get() = kit.fill
  private val stroke get() = kit.stroke
  private val colors get() = kit.colors
  private val targets = ArrayList<Target>()
  private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  private val path = Path()
  private val source = Rect()

  private var dp = 1f
  private var now = 0L
  private var motion = true
  private var animating = false

  private var flashBox: RectF? = null
  private var flashUntil = 0L
  private var flashScene: Any? = null

  /**
   * Taps are ignored until this time. Whenever the set of signs changes, the next sign can
   * appear right under a finger that is still coming down for a second tap: a double tap on a
   * conversation would land on "Yes", a hurried double tap on Cancel on "No". So for a moment
   * after any change of layout the board takes no taps, until the driver can have seen it.
   */
  private var deafUntil = 0L
  private var lastLayout: Any? = null

  /** Where the tappable signs were on the last frame, for tests and for hit testing. */
  val targetBoxes: List<RectF> get() = targets.map { RectF(it.box) }

  /** Paints one frame. Returns true while something is moving and another frame is wanted. */
  fun paint(canvas: Canvas, frame: Frame, board: BoardModel): Boolean {
    dp = frame.dp
    now = frame.now
    motion = frame.motion
    kit.dp = frame.dp
    kit.colors = if (frame.night) Palette.NIGHT else Palette.DAY
    animating = false
    targets.clear()

    // Covers changes nobody tapped for too: a countdown running out, a readout failing.
    val layout = layoutOf(board.scene)
    if (layout != lastLayout) {
      if (lastLayout != null) deafUntil = max(deafUntil, frame.now + SETTLE_MS)
      lastLayout = layout
    }

    canvas.drawColor(colors.ground)
    val safe = if (frame.safe.isEmpty) Rect(0, 0, frame.width, frame.height) else frame.safe
    val area = RectF(safe).apply { inset(MARGIN * dp, MARGIN * dp) }
    // Smaller than this and no sign could be read or pressed, so the ground is all there is:
    // a blank board is honest, a board of clipped fragments is not. What "too small" means
    // depends on what has to fit: the setup notice is one sign; a narrow board stacks its signs
    // and needs the height for all of them, the words of a reply above all.
    val least = when {
      board.scene == Scene.Gantry && !board.ready -> MIN_HEIGHT_NOTICE
      area.width() < SIDE_BY_SIDE * dp -> MIN_HEIGHT_STACKED
      else -> MIN_HEIGHT
    }
    if (area.width() < MIN_WIDTH * dp || area.height() < least * dp) return false

    when (val scene = board.scene) {
      Scene.Gantry -> if (board.ready) gantry(canvas, area, board) else setup(canvas, area)
      is Scene.Thread -> thread(canvas, area, board, scene)
    }
    flash(canvas, layout)
    return animating
  }

  /** Runs the sign under the point, if any. */
  fun tap(x: Float, y: Float, now: Long, board: BoardModel): Boolean {
    if (now < deafUntil) return false
    val hit = targets.lastOrNull { it.box.contains(x, y) } ?: return false
    flashBox = RectF(hit.box)
    flashUntil = now + FLASH_MS
    flashScene = layoutOf(board.scene)
    val before = layoutOf(board.scene)
    hit.act()
    // The signs under the finger are about to change, before the next frame is even painted.
    if (layoutOf(board.scene) != before) deafUntil = now + SETTLE_MS
    return true
  }

  /**
   * Which arrangement of signs a scene shows. Reading and Ready share one on purpose, so the
   * end of a readout never swallows a tap on Reply.
   */
  private fun layoutOf(scene: Scene): Any = when (scene) {
    Scene.Gantry -> Scene.Gantry
    is Scene.Thread -> when (scene.phase) {
      Phase.Reading, Phase.Ready -> Phase.Ready
      is Phase.Listening -> Phase.Listening::class
      is Phase.Confirm -> Phase.Confirm::class
      Phase.Sent -> Phase.Sent
      is Phase.Trouble -> Phase.Trouble::class
    }
  }

  // ---------------------------------------------------------------- scenes

  private fun setup(c: Canvas, area: RectF) {
    val body = RectF(area.left, area.top, area.right, min(area.bottom, area.top + 220 * dp))
    kit.sign(c, body, colors.yellow, colors.ink)
    val words = kit.inside(body)
    val size = (words.height() * 0.24f).coerceIn(24 * dp, 44 * dp)
    kit.titleBlock(c, words, "Finish setup", setupHint, size, colors.ink, secondLines = 2)
  }

  private fun gantry(c: Canvas, area: RectF, board: BoardModel) {
    val talks = board.conversations
    val gap = GAP * dp
    // Side by side needs real width; a small card stacks the two instead of squeezing both.
    val wide = area.width() >= area.height() * 1.3f && area.width() >= SIDE_BY_SIDE * dp
    val playing = RectF()
    val inbox = RectF()
    if (wide) {
      val share = if (talks.isEmpty()) 0.6f else 0.48f
      playing.set(area.left, area.top, area.left + (area.width() - gap) * share, area.bottom)
      inbox.set(playing.right + gap, area.top, area.right, area.bottom)
    } else {
      // Tall enough to stack its controls under the legend, as long as one conversation still fits below.
      val wanted = max((area.height() - gap) * 0.44f, (TAB + STACKED) * dp)
      val spare = area.height() - gap - (TAB + 92) * dp
      playing.set(area.left, area.top, area.right, area.top + min(wanted, max(spare, area.height() * 0.3f)))
      inbox.set(area.left, playing.bottom + gap, area.right, area.bottom)
    }
    playing(c, playing, board)
    inbox(c, inbox, board, talks)
  }

  private fun playing(c: Canvas, cell: RectF, board: BoardModel) {
    val track = board.nowPlaying
    val tabH = TAB * dp
    val body = RectF(cell.left, cell.top + tabH, cell.right, cell.bottom)
    kit.tab(c, body, track?.source?.ifBlank { null } ?: "Music", colors.brown, right = false)
    kit.sign(c, body, colors.brown, colors.white)
    if (track == null) {
      val words = kit.inside(body)
      kit.titleBlock(c, words, "Nothing playing", "", (words.height() * 0.2f).coerceIn(24 * dp, 48 * dp), colors.white)
      return
    }

    val rim = kit.rim()
    val rule = EDGE * dp
    val pad = PAD * dp
    fill.color = colors.white
    if (body.height() < STACKED * dp) {
      // Too short to stack: the controls go beside the legend, and the sleeve is left out.
      val controlsW = (body.width() * 0.5f).coerceIn(min(3 * 78 * dp, body.width() * 0.68f), body.width() * 0.68f)
      val strip = RectF(body.right - rim - controlsW, body.top + rim, body.right - rim, body.bottom - rim)
      c.drawRect(strip.left - rule, strip.top, strip.left, strip.bottom, fill)
      controls(c, strip, track, board)
      val words = RectF(body.left + rim + pad, body.top + rim + pad * 0.4f, strip.left - rule - pad * 0.8f, body.bottom - rim - pad * 0.4f)
      // In a narrow column the artist would crowd the title into fragments, and in a sliver
      // there is no room for words at all.
      val artist = if (words.width() >= 150 * dp) track.artist else ""
      if (words.width() >= 48 * dp) {
        kit.titleBlock(c, words, track.title, artist, (words.height() * 0.34f).coerceIn(18 * dp, 40 * dp), colors.white, lines = 2)
      }
      return
    }

    // The controls sit in a ruled strip along the bottom, like the lane panel of a guide sign.
    val stripH = (body.height() * 0.38f).coerceIn(80 * dp, 128 * dp)
    val strip = RectF(body.left + rim, body.bottom - rim - stripH, body.right - rim, body.bottom - rim)
    c.drawRect(strip.left, strip.top - rule, strip.right, strip.top, fill)
    controls(c, strip, track, board)

    val top = RectF(body.left + rim + pad, body.top + rim + pad, body.right - rim - pad, strip.top - rule - pad * 0.8f)
    var left = top.left
    val art = track.art
    val side = min(top.height(), top.width() * 0.42f)
    if (art != null && !art.isRecycled && side >= 56 * dp) {
      val frame = RectF(top.left, top.centerY() - side / 2, top.left + side, top.centerY() + side / 2)
      cover(c, art, frame)
      left = frame.right + pad
    }
    val words = RectF(left, top.top, top.right, top.bottom)
    kit.titleBlock(c, words, track.title, track.artist, (words.height() * 0.3f).coerceIn(24 * dp, 60 * dp), colors.white, lines = 2)
  }

  /** Previous, play or pause, next: three ruled cells, each a full-size target. */
  private fun controls(c: Canvas, strip: RectF, track: NowPlaying, board: BoardModel) {
    val rule = EDGE * dp
    val cellW = strip.width() / 3f
    for (i in 0..2) {
      val box = RectF(strip.left + cellW * i, strip.top, strip.left + cellW * (i + 1), strip.bottom)
      fill.color = colors.white
      if (i > 0) c.drawRect(box.left - rule / 2, strip.top, box.left + rule / 2, strip.bottom, fill)
      val enabled = when (i) {
        0 -> track.canPrevious
        2 -> track.canNext
        else -> true
      }
      fill.alpha = if (enabled) 255 else 80
      val g = min(box.width(), box.height()) * 0.24f
      when (i) {
        0 -> skipGlyph(c, box.centerX(), box.centerY(), g, forward = false)
        1 -> if (track.playing) pauseGlyph(c, box.centerX(), box.centerY(), g) else playGlyph(c, box.centerX(), box.centerY(), g)
        else -> skipGlyph(c, box.centerX(), box.centerY(), g, forward = true)
      }
      fill.alpha = 255
      if (enabled) {
        target(box) {
          when (i) {
            0 -> board.previous()
            1 -> board.toggle()
            else -> board.next()
          }
        }
      }
    }
  }

  private fun inbox(c: Canvas, cell: RectF, board: BoardModel, talks: List<Conversation>) {
    val gap = GAP * dp
    val tabH = TAB * dp
    if (talks.isEmpty()) {
      val body = RectF(cell.left, cell.top + tabH, cell.right, min(cell.bottom, cell.top + tabH + 150 * dp))
      kit.sign(c, body, colors.blue, colors.white)
      val words = kit.inside(body)
      kit.titleBlock(c, words, "No new messages", "", (words.height() * 0.3f).coerceIn(22 * dp, 44 * dp), colors.white)
      return
    }

    val least = tabH + 88 * dp
    val most = floor((cell.height() + gap) / (least + gap)).toInt().coerceIn(1, 4)
    val paged = talks.size > most
    // Fewer conversations get taller signs, but one alone does not swell to fill the column.
    val rows = if (paged) most else max(talks.size, min(2, most))
    val perPage = if (paged) max(1, rows - 1) else rows
    val rowH = min((cell.height() - gap * (rows - 1)) / rows, tabH + 200 * dp)
    val first = board.firstShown.coerceIn(0, talks.size - 1)
    val shown = talks.drop(first).take(perPage)

    // With room for only one row, the pager shares it rather than replacing the conversation.
    val pagerBeside = paged && rows == 1
    val talkRight = if (pagerBeside) cell.right - (cell.width() * 0.3f) - gap else cell.right
    var y = cell.top
    for (talk in shown) {
      talkSign(c, RectF(cell.left, y, talkRight, y + rowH), talk, board)
      y += rowH + gap
    }
    if (paged) {
      val body = if (pagerBeside) {
        RectF(talkRight + gap, cell.top + tabH, cell.right, cell.top + rowH)
      } else {
        RectF(cell.left, cell.top + (rowH + gap) * (rows - 1) + tabH, cell.right, cell.top + (rowH + gap) * (rows - 1) + rowH)
      }
      val waiting = talks.size - shown.size
      plate(c, body, Plate("$waiting more", colors.paper, colors.ink, colors.ink) { board.nextPage(perPage) })
    }
  }

  private fun talkSign(c: Canvas, cell: RectF, talk: Conversation, board: BoardModel) {
    val body = RectF(cell.left, cell.top + TAB * dp, cell.right, cell.bottom)
    kit.tab(c, body, "${talk.messages.size} new", colors.blue, right = true)
    kit.sign(c, body, colors.blue, colors.white)
    val at = timeOf(talk.latestAt)
    val from = if (talk.app.isBlank()) "At $at" else "${talk.app} at $at"
    kit.titleBlock(c, kit.inside(body), talk.title, from, (body.height() * 0.3f).coerceIn(24 * dp, 56 * dp), colors.white, lines = 2)
    target(body) { board.open(talk) }
  }

  private fun thread(c: Canvas, area: RectF, board: BoardModel, scene: Scene.Thread) {
    val gap = GAP * dp
    val rim = kit.rim()
    val rule = EDGE * dp
    val pad = PAD * dp
    // On a very short surface the header gives way, so the choices below never climb over it.
    val headH = (area.height() * 0.34f).coerceIn(104 * dp, 176 * dp).coerceAtMost(area.height() * 0.46f)
    val head = RectF(area.left, area.top, area.right, area.top + headH)
    kit.sign(c, head, colors.blue, colors.white)

    // On a narrow board the name needs the width, so the arrow tightens and the pixels move
    // down to lead the status line instead of standing at the far end.
    val narrow = head.width() < 560 * dp
    val backW = if (narrow) 80 * dp else (headH * 0.95f).coerceIn(92 * dp, 150 * dp)
    val back = RectF(head.left + rim, head.top + rim, head.left + rim + backW, head.bottom - rim)
    fill.color = colors.white
    arrowGlyph(c, back.centerX(), back.centerY(), min(back.width(), back.height()) * 0.3f)
    c.drawRect(back.right, back.top, back.right + rule, back.bottom, fill)
    target(RectF(head.left, head.top, back.right, head.bottom)) { board.back() }

    val size = (headH * 0.3f).coerceIn(26 * dp, 52 * dp)
    if (narrow) {
      val words = RectF(back.right + rule + pad, head.top + rim, head.right - rim - pad, head.bottom - rim)
      val middle = words.top + words.height() * 0.56f
      kit.titleBlock(c, RectF(words.left, words.top + pad * 0.3f, words.right, middle), scene.talk.title, "", size, colors.white)
      val px = 13 * dp
      val row = RectF(words.left, middle, words.right, words.bottom - pad * 0.3f)
      pixels(c, row.left, row.centerY() - px / 2, px, scene.phase)
      val pixelsW = px * 4 + px * PIXEL_GAP * 3
      kit.plain(c, RectF(row.left + pixelsW + pad * 0.6f, row.top, row.right, row.bottom), status(scene), size * 0.62f, colors.white)
    } else {
      val px = (headH * 0.15f).coerceIn(14 * dp, 24 * dp)
      val pixelsW = px * 4 + px * PIXEL_GAP * 3
      val pixelsLeft = head.right - rim - pad - pixelsW
      pixels(c, pixelsLeft, head.centerY() - px / 2, px, scene.phase)
      val words = RectF(back.right + rule + pad, head.top + rim, pixelsLeft - pad, head.bottom - rim)
      kit.titleBlock(c, words, scene.talk.title, status(scene), size, colors.white)
    }

    val below = RectF(area.left, head.bottom + gap, area.right, area.bottom)
    val talk = scene.talk
    when (val phase = scene.phase) {
      Phase.Reading, Phase.Ready -> {
        // The same signs stay put through reading and after it, so nothing moves under a finger.
        val again = Plate("Hear again", colors.blue, colors.white, colors.white) { board.hearAgain() }
        if (!talk.canReply) {
          plates(c, below, listOf(again))
        } else {
          val reply = Plate("Reply", colors.green, colors.white, colors.white, weight = 2f) { board.reply() }
          val room = floor(below.width() / (190 * dp)).toInt().coerceIn(2, 4)
          val quick = board.quickReplies.take(room).map { line ->
            Plate(line, colors.paper, colors.ink, colors.ink) { board.quick(line) }
          }
          if (below.height() >= 2 * 92 * dp + gap) {
            val rowH = min((below.height() - gap) / 2, 190 * dp)
            plates(c, RectF(below.left, below.top, below.right, below.top + rowH), listOf(reply, again))
            plates(c, RectF(below.left, below.top + rowH + gap, below.right, below.top + 2 * rowH + gap), quick)
          } else {
            plates(c, below, listOf(reply) + quick.take(2) + again)
          }
        }
      }
      is Phase.Listening -> {
        val (wordsBox, side) = split(below)
        speech(c, wordsBox, phase.words.ifBlank { "Say your reply" })
        stack(c, side, listOf(Plate("Cancel", colors.red, colors.white, colors.white) { board.cancelReply() }))
      }
      is Phase.Confirm -> {
        val (wordsBox, side) = split(below)
        speech(c, wordsBox, phase.words)
        stack(c, side, listOf(
            Plate("Send now", colors.green, colors.white, colors.white) { board.sendNow() },
            Plate("Say it again", colors.paper, colors.ink, colors.ink) { board.reply() },
            Plate("Cancel", colors.red, colors.white, colors.white) { board.cancelReply() },
        ))
      }
      Phase.Sent -> {
        val body = RectF(below.left, below.top, below.right, min(below.bottom, below.top + 190 * dp))
        kit.sign(c, body, colors.green, colors.white)
        kit.centered(c, body, "Sent", (body.height() * 0.3f).coerceIn(26 * dp, 52 * dp), colors.white)
      }
      is Phase.Trouble -> {
        val (wordsBox, side) = split(below)
        kit.sign(c, wordsBox, colors.orange, colors.ink)
        kit.paragraph(c, kit.inside(wordsBox), phase.what, (wordsBox.height() * 0.2f).coerceIn(24 * dp, 40 * dp), colors.ink)
        val plates = ArrayList<Plate>()
        if (phase.canRetry) plates += Plate("Try again", colors.paper, colors.ink, colors.ink) { board.reply() }
        plates += Plate("Go back", colors.paper, colors.ink, colors.ink) { board.cancelReply() }
        stack(c, side, plates)
      }
    }
  }

  private fun status(scene: Scene.Thread): String {
    val count = scene.talk.messages.size
    val messages = if (count == 1) "1 message" else "$count messages"
    return when (val phase = scene.phase) {
      Phase.Reading -> "Reading $messages"
      Phase.Ready -> "Last at ${timeOf(scene.talk.latestAt)}"
      is Phase.Listening -> "Listening"
      is Phase.Confirm -> "Sending in ${phase.ticksLeft}"
      Phase.Sent -> "Sent"
      is Phase.Trouble -> ""
    }
  }

  // ---------------------------------------------------------------- plates

  private fun plates(c: Canvas, box: RectF, plates: List<Plate>) {
    if (plates.isEmpty()) return
    val gap = GAP * dp
    val perRow = if (box.width() / plates.size >= 150 * dp) plates.size else ceil(plates.size / 2f).toInt()
    val rows = plates.chunked(perRow)
    val rowH = min((box.height() - gap * (rows.size - 1)) / rows.size, 190 * dp)
    var y = box.top
    for (row in rows) {
      val total = row.sumOf { it.weight.toDouble() }.toFloat()
      val free = box.width() - gap * (row.size - 1)
      var x = box.left
      for (plate in row) {
        val width = free * plate.weight / total
        plate(c, RectF(x, y, x + width, y + rowH), plate)
        x += width + gap
      }
      y += rowH + gap
    }
  }

  private fun stack(c: Canvas, box: RectF, plates: List<Plate>) {
    if (plates.isEmpty()) return
    val gap = GAP * dp
    // Side by side when the space is a short strip, stacked when it is a column.
    if (box.width() > box.height() * 1.8f) {
      val width = (box.width() - gap * (plates.size - 1)) / plates.size
      plates.forEachIndexed { i, plate ->
        val x = box.left + (width + gap) * i
        plate(c, RectF(x, box.top, x + width, box.bottom), plate)
      }
    } else {
      val height = min((box.height() - gap * (plates.size - 1)) / plates.size, 150 * dp)
      plates.forEachIndexed { i, plate ->
        val y = box.top + (height + gap) * i
        plate(c, RectF(box.left, y, box.right, y + height), plate)
      }
    }
  }

  private fun plate(c: Canvas, box: RectF, plate: Plate) {
    kit.sign(c, box, plate.ground, plate.edge)
    kit.centered(c, box, plate.label, (box.height() * 0.24f).coerceIn(20 * dp, 38 * dp), plate.ink)
    target(box, plate.act)
  }

  /** The driver's own words, large, on a regulatory sign. */
  private fun speech(c: Canvas, box: RectF, words: String) {
    kit.sign(c, box, colors.paper, colors.ink)
    kit.paragraph(c, kit.inside(box), words, (box.height() * 0.2f).coerceIn(24 * dp, 56 * dp), colors.ink)
  }

  /** Splits the space under the header into the words and the choices beside (or under) them. */
  private fun split(box: RectF): Pair<RectF, RectF> {
    val gap = GAP * dp
    return if (box.width() >= SIDE_BY_SIDE * dp) {
      val side = (box.width() * 0.3f).coerceIn(180 * dp, 320 * dp)
      RectF(box.left, box.top, box.right - side - gap, box.bottom) to RectF(box.right - side, box.top, box.right, box.bottom)
    } else {
      // The words keep the larger share: they are what the driver has to check before it sends.
      val bottom = (box.height() * 0.4f).coerceAtLeast(84 * dp).coerceAtMost(box.height() * 0.45f)
      RectF(box.left, box.top, box.right, box.bottom - bottom - gap) to RectF(box.left, box.bottom - bottom, box.right, box.bottom)
    }
  }

  // ---------------------------------------------------------------- marks

  /**
   * Four pixels, the Ioniq's own signal. They chase while the car reads, rise with the driver's
   * voice while it listens, and go out one a second while a reply waits to send.
   */
  private fun pixels(c: Canvas, left: Float, top: Float, size: Float, phase: Phase) {
    val beat = if (motion) ((now / 240L) % 4L).toInt() else -1
    for (i in 0..3) {
      val lit = when (phase) {
        Phase.Reading -> if (motion) i == beat else true
        is Phase.Listening -> phase.level >= (i + 1) / 5f || (phase.level < 0.2f && i == beat)
        is Phase.Confirm -> i < phase.ticksLeft
        Phase.Sent -> true
        Phase.Ready, is Phase.Trouble -> false
      }
      fill.color = colors.white
      fill.alpha = if (lit) 255 else 64
      val x = left + i * size * (1 + PIXEL_GAP)
      c.drawRect(x, top, x + size, top + size, fill)
    }
    fill.alpha = 255
    if (motion && (phase == Phase.Reading || phase is Phase.Listening)) animating = true
  }

  private fun cover(c: Canvas, art: Bitmap, frame: RectF) {
    val radius = 6 * dp
    // Centre-crop to a square so a wide podcast banner does not stretch.
    val edge = min(art.width, art.height)
    source.set((art.width - edge) / 2, (art.height - edge) / 2, (art.width + edge) / 2, (art.height + edge) / 2)
    path.reset()
    path.addRoundRect(frame, radius, radius, Path.Direction.CW)
    c.save()
    c.clipPath(path)
    // A bright sleeve would be the one glaring thing in a dark cabin.
    picture.colorFilter = if (colors === Palette.NIGHT) NIGHT_COVER else null
    c.drawBitmap(art, source, frame, picture)
    c.restore()
    stroke.color = colors.white
    stroke.strokeWidth = EDGE * dp
    c.drawRoundRect(frame, radius, radius, stroke)
  }

  private fun playGlyph(c: Canvas, cx: Float, cy: Float, g: Float) {
    path.reset()
    path.moveTo(cx - g * 0.75f, cy - g)
    path.lineTo(cx + g, cy)
    path.lineTo(cx - g * 0.75f, cy + g)
    path.close()
    c.drawPath(path, fill)
  }

  private fun pauseGlyph(c: Canvas, cx: Float, cy: Float, g: Float) {
    val bar = g * 0.62f
    c.drawRect(cx - g * 0.85f, cy - g, cx - g * 0.85f + bar, cy + g, fill)
    c.drawRect(cx + g * 0.85f - bar, cy - g, cx + g * 0.85f, cy + g, fill)
  }

  private fun skipGlyph(c: Canvas, cx: Float, cy: Float, g: Float, forward: Boolean) {
    val d = if (forward) 1f else -1f
    path.reset()
    path.moveTo(cx - d * g, cy - g)
    path.lineTo(cx + d * g * 0.45f, cy)
    path.lineTo(cx - d * g, cy + g)
    path.close()
    c.drawPath(path, fill)
    val near = cx + d * g * 0.62f
    val far = cx + d * g
    c.drawRect(min(near, far), cy - g, max(near, far), cy + g, fill)
  }

  /** The left arrow off a guide sign: a shaft and a head, not a chevron. */
  private fun arrowGlyph(c: Canvas, cx: Float, cy: Float, g: Float) {
    path.reset()
    path.moveTo(cx - g * 1.1f, cy)
    path.lineTo(cx - g * 0.1f, cy - g * 0.9f)
    path.lineTo(cx - g * 0.1f, cy - g * 0.32f)
    path.lineTo(cx + g * 1.1f, cy - g * 0.32f)
    path.lineTo(cx + g * 1.1f, cy + g * 0.32f)
    path.lineTo(cx - g * 0.1f, cy + g * 0.32f)
    path.lineTo(cx - g * 0.1f, cy + g * 0.9f)
    path.close()
    c.drawPath(path, fill)
  }

  // ---------------------------------------------------------------- plumbing

  private fun target(box: RectF, act: () -> Unit) {
    targets += Target(RectF(box), act)
  }

  /** The car only reports a finished tap, so the sign itself has to say it was pressed. */
  private fun flash(c: Canvas, scene: Any) {
    val box = flashBox ?: return
    if (now >= flashUntil || scene != flashScene) {
      flashBox = null
      return
    }
    fill.color = colors.white
    fill.alpha = 70
    c.drawRect(box, fill)
    fill.alpha = 255
    animating = true
  }

  companion object {
    const val PIXEL_GAP = 0.5f

    /** The smallest area, in dp inside the margins, the board will draw on. */
    const val MIN_WIDTH = 300f
    const val MIN_HEIGHT = 170f
    const val MIN_HEIGHT_STACKED = 280f
    const val MIN_HEIGHT_NOTICE = 96f

    /** Narrower than this, signs stack instead of sitting side by side. */
    const val SIDE_BY_SIDE = 560f

    /** The least height, in dp, at which the music sign can carry its controls under its legend. */
    const val STACKED = 190f
    const val FLASH_MS = 180L

    /** How long the board ignores taps after its signs change. */
    const val SETTLE_MS = 500L
    private val NIGHT_COVER = PorterDuffColorFilter(0xFFA8A8A8.toInt(), PorterDuff.Mode.MULTIPLY)
  }
}
