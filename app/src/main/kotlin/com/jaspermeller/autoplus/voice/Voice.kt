package com.jaspermeller.autoplus.voice

/** Reads text aloud. [say] reports whether the whole thing was spoken (false when cut off). */
interface Speaker {
  fun say(text: String, done: (finished: Boolean) -> Unit)

  fun stop()
}

/** What a round of listening produced. */
sealed interface Heard {
  data class Words(val text: String) : Heard

  /** The driver said nothing the recognizer could use. */
  data object Silence : Heard

  /** Listening cannot work until something outside the board changes. [why] is shown as written. */
  data class Blocked(val why: String) : Heard
}

/** Turns one spoken reply into text. */
interface Dictation {
  /**
   * [partial] reports words as they are recognised, [level] the voice level from 0 to 1,
   * and [done] is called exactly once unless [cancel] comes first.
   */
  fun listen(partial: (String) -> Unit, level: (Float) -> Unit, done: (Heard) -> Unit)

  fun cancel()
}

/** Runs [run] after [ms]; call the returned function to cancel. Lets tests own the clock. */
fun interface Timer {
  fun after(ms: Long, run: () -> Unit): () -> Unit
}
