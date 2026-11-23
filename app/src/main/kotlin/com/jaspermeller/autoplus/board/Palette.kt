package com.jaspermeller.autoplus.board

import android.content.Context
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import com.jaspermeller.autoplus.R

/*
 * Thesis: the screen is a gantry of highway signs, because a driver already reads those in one
 * glance at speed, and each colour already means something on the road.
 *
 * Every colour is a highway sign colour, taken from the Pantone matches the Federal Highway
 * Administration publishes for the MUTCD colour specification, and it keeps its road meaning:
 *
 *   green   PMS 342   guide signs: the way to go        -> the action that moves things on
 *   blue    PMS 294   motorist services                 -> messages
 *   brown   PMS 469   recreation and culture            -> music
 *   red     PMS 187   stop                              -> cancel
 *   orange  PMS 152   temporary traffic control         -> something went wrong
 *   yellow  PMS 116   warning                           -> setup needed
 *   paper   white regulatory sheeting                   -> plain choices, black legend
 *
 * The ground is not a sign. By day it is the overcast behind a sign on the 101; at night it is
 * unlit asphalt. Night colours are the same hues with the luminance pulled down, the way a
 * retroreflective sign looks outside the headlights, so the screen does not glare in a dark cabin.
 */
class Palette(
    val ground: Int,
    val green: Int,
    val blue: Int,
    val brown: Int,
    val red: Int,
    val orange: Int,
    val yellow: Int,
    /** Legends and borders on a coloured sign. */
    val white: Int,
    /** The face of a regulatory sign. */
    val paper: Int,
    /** Legends on paper, yellow and orange. */
    val ink: Int,
) {
  companion object {
    val DAY = Palette(
        ground = 0xFFC8D1D5.toInt(),
        green = 0xFF006747.toInt(),
        blue = 0xFF002F6C.toInt(),
        brown = 0xFF693F23.toInt(),
        red = 0xFFA6192E.toInt(),
        orange = 0xFFE57200.toInt(),
        yellow = 0xFFFFCD00.toInt(),
        white = 0xFFFDFDF8.toInt(),
        paper = 0xFFFDFDF8.toInt(),
        ink = 0xFF15120F.toInt(),
    )

    val NIGHT = Palette(
        ground = 0xFF0B1013.toInt(),
        green = 0xFF00553A.toInt(),
        blue = 0xFF01295A.toInt(),
        brown = 0xFF56341D.toInt(),
        red = 0xFF891426.toInt(),
        orange = 0xFFBD5E00.toInt(),
        yellow = 0xFFD2A900.toInt(),
        white = 0xFFDCDED8.toInt(),
        paper = 0xFFB4B7B1.toInt(),
        ink = 0xFF100E0C.toInt(),
    )
  }
}

/**
 * Overpass, an open cut of Highway Gothic: the lettering on the signs themselves. Bold for
 * legends (close to FHWA Series E), medium for the second line.
 */
class Lettering(val legend: Typeface, val plain: Typeface) {
  /** Height of a capital as a share of the text size, for centring legends by their letters. */
  val capRatio: Float = run {
    val paint = Paint().apply { typeface = legend; textSize = 100f }
    val bounds = Rect()
    paint.getTextBounds("H", 0, 1, bounds)
    if (bounds.height() > 0) bounds.height() / 100f else 0.7f
  }

  companion object {
    fun load(context: Context): Lettering {
      // One variable font file; each weight is an instance of its 'wght' axis.
      fun weight(value: Int): Typeface? =
          try {
            val font = Font.Builder(context.resources, R.font.overpass)
                .setFontVariationSettings("'wght' $value")
                .setWeight(value)
                .build()
            Typeface.CustomFallbackBuilder(FontFamily.Builder(font).build())
                .setStyle(FontStyle(value, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
          } catch (missing: Exception) {
            null
          }
      return Lettering(weight(700) ?: Typeface.DEFAULT_BOLD, weight(500) ?: Typeface.DEFAULT)
    }
  }
}
