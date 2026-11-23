package com.jaspermeller.autoplus.voice

/** How a readout ended. */
enum class Spoken {
  /** Every word was spoken. */
  FINISHED,

  /** Cut off: by the driver moving on, or by another readout starting. */
  INTERRUPTED,

  /** Nothing could be spoken: no speech engine, or the phone would not give up the sound (a call). */
  FAILED,
}

/** Reads text aloud. [say] reports how the readout ended, exactly once. */
interface Speaker {
  fun say(text: String, done: (Spoken) -> Unit)

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
