package com.jaspermeller.autoplus.board

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * How a sign is built: the blank, its inset border, the plaque on its top edge and the legend
 * set as large as the blank allows. The car board and the phone's setup list both draw with it.
 * Set [dp] and [colors] before drawing a frame.
 */
class SignKit(val lettering: Lettering) {
  var dp = 1f
  var colors = Palette.DAY

  val fill = Paint(Paint.ANTI_ALIAS_FLAG)
  val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
  private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
  private val scratch = RectF()

  /** A sign blank: rounded corners and the inset border every US road sign carries. */
  fun sign(c: Canvas, box: RectF, ground: Int, edge: Int) {
    val radius = corner(box)
    fill.color = ground
    c.drawRoundRect(box, radius, radius, fill)
    val gap = EDGE_GAP * dp
    val width = EDGE * dp
    stroke.color = edge
    stroke.strokeWidth = width
    scratch.set(box)
    scratch.inset(gap + width / 2, gap + width / 2)
    val inner = max(0f, radius - gap - width / 2)
    c.drawRoundRect(scratch, inner, inner, stroke)
  }

  /** A small plaque riding on the sign's top edge, the way an exit number does. */
  fun tab(c: Canvas, body: RectF, label: String, ground: Int, right: Boolean, height: Float = TAB * dp) {
    val size = height * 0.5f
    text.typeface = lettering.legend
    text.textSize = size
    val width = min(text.measureText(label) + 26 * dp, body.width() - 2 * corner(body))
    val x = if (right) body.right - corner(body) - width else body.left + corner(body)
    // Its foot touches the sign below, so the two read as one assembly.
    val plaque = RectF(x, body.top - height, x + width, body.top - 1.5f * dp)
    sign(c, plaque, ground, colors.white)
    val words = RectF(plaque.left + 13 * dp, plaque.top, plaque.right - 13 * dp, plaque.bottom)
    block(c, words, listOf(fit(label, words.width(), size, size * SHRINK, lettering.legend, colors.white, 1)))
  }

  /** A legend with an optional second line, set as large as the box allows and centred by its capitals. */
  fun titleBlock(c: Canvas, box: RectF, title: String, second: String, size: Float, ink: Int, lines: Int = 1, secondLines: Int = 1) {
    val smallest = size * SHRINK
    var trial = size
    while (true) {
      val legend = layout(title, box.width(), trial, lettering.legend, ink, lines, Layout.Alignment.ALIGN_NORMAL)
      val under = if (second.isEmpty()) null else {
        layout(second, box.width(), trial * 0.62f, lettering.plain, ink, secondLines, Layout.Alignment.ALIGN_NORMAL)
      }
      val whole = !clipped(legend) && (under == null || !clipped(under)) && legend.height + (under?.height ?: 0) <= box.height()
      if (whole) {
        block(c, box, listOfNotNull(legend, under))
        return
      }
      if (trial <= smallest) break
      trial = max(smallest, trial * 0.93f)
    }
    // Too much for this sign even at the smallest size. The second line gives way first, then
    // the legend drops to one line; an ellipsis is better than lettering across the border.
    val legend = layout(title, box.width(), smallest, lettering.legend, ink, lines, Layout.Alignment.ALIGN_NORMAL)
    val under = if (second.isEmpty()) null else layout(second, box.width(), smallest * 0.62f, lettering.plain, ink, 1, Layout.Alignment.ALIGN_NORMAL)
    when {
      under != null && legend.height + under.height <= box.height() -> block(c, box, listOf(legend, under))
      legend.height <= box.height() -> block(c, box, listOf(legend))
      else -> block(c, box, listOf(layout(title, box.width(), smallest, lettering.legend, ink, 1, Layout.Alignment.ALIGN_NORMAL)))
    }
  }

  private fun clipped(layout: StaticLayout): Boolean = layout.getEllipsisCount(layout.lineCount - 1) > 0

  /** One line in the lighter face, centred vertically. */
  fun plain(c: Canvas, box: RectF, words: String, size: Float, ink: Int) {
    block(c, box, listOf(fit(words, box.width(), size, size * SHRINK, lettering.plain, ink, 1)))
  }

  /** Up to two centred lines, for the face of a plate. */
  fun centered(c: Canvas, box: RectF, label: String, size: Float, ink: Int) {
    val words = RectF(box).apply { inset(rim() + 8 * dp, rim()) }
    val lines = if (label.contains(' ')) 2 else 1
    block(c, words, listOf(fit(label, words.width(), size, size * SHRINK, lettering.legend, ink, lines, Layout.Alignment.ALIGN_CENTER)))
  }

  /** Left-aligned running words, centred vertically. */
  fun paragraph(c: Canvas, box: RectF, words: String, size: Float, ink: Int) {
    val lines = floor(box.height() / (size * SHRINK * 1.25f)).toInt().coerceIn(1, 5)
    block(c, box, listOf(fit(words, box.width(), size, size * SHRINK, lettering.legend, ink, lines, tallest = box.height())))
  }

  fun corner(box: RectF): Float = min(16 * dp, min(box.width(), box.height()) * 0.18f)

  /** How far in from a sign's edge its border ends. */
  fun rim(): Float = (EDGE_GAP + EDGE) * dp

  /** The part of a sign a legend may use. */
  fun inside(box: RectF): RectF = RectF(box).apply { inset(rim() + PAD * dp, rim() + PAD * dp * 0.7f) }

  /**
   * Sets [words] at the largest size from [size] down to [smallest] that fits the width, the line
   * limit and [tallest] without losing a word. At the smallest size it ends in an ellipsis instead.
   */
  private fun fit(
      words: String,
      width: Float,
      size: Float,
      smallest: Float,
      face: Typeface,
      ink: Int,
      lines: Int,
      align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL,
      tallest: Float = Float.MAX_VALUE,
  ): StaticLayout {
    var trial = size
    while (true) {
      val layout = layout(words, width, trial, face, ink, lines, align)
      val whole = !clipped(layout) && layout.height <= tallest
      if (whole || trial <= smallest) return layout
      trial = max(smallest, trial * 0.93f)
    }
  }

  private fun layout(words: String, width: Float, size: Float, face: Typeface, ink: Int, lines: Int, align: Layout.Alignment): StaticLayout {
    val paint = TextPaint(text).apply {
      typeface = face
      color = ink
      textSize = size
    }
    return StaticLayout.Builder.obtain(words, 0, words.length, paint, max(1, width.toInt()))
        .setAlignment(align)
        .setMaxLines(lines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .setIncludePad(false)
        .setLineSpacing(0f, 0.96f)
        .build()
  }

  /**
   * Stacks the pieces and centres them on the box by what the eye sees: from the top of the first
   * line's capitals to the last baseline, not the font's taller line box.
   */
  private fun block(c: Canvas, box: RectF, pieces: List<StaticLayout>) {
    if (pieces.isEmpty()) return
    val first = pieces.first()
    val last = pieces.last()
    val above = -first.getLineAscent(0) - lettering.capRatio * first.paint.textSize
    val below = last.getLineDescent(last.lineCount - 1).toFloat()
    val total = pieces.sumOf { it.height }.toFloat()
    var y = box.centerY() - (total - above - below) / 2 - above
    for (piece in pieces) {
      c.save()
      c.translate(box.left, y)
      piece.draw(c)
      c.restore()
      y += piece.height
    }
  }

  companion object {
    const val MARGIN = 14f
    const val GAP = 12f
    const val PAD = 16f
    const val TAB = 38f
    const val EDGE = 2.5f
    const val EDGE_GAP = 3f
    const val SHRINK = 0.74f
  }
}
