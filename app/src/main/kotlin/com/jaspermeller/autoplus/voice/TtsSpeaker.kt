package com.jaspermeller.autoplus.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Reads messages through the phone's text-to-speech engine, ducking the music while it talks. Main thread only. */
class TtsSpeaker(context: Context) : Speaker {
  private val context = context.applicationContext
  private val main = Handler(Looper.getMainLooper())
  private val audio = this.context.getSystemService(AudioManager::class.java)

  // Guidance usage is what Android Auto routes to the car's prompt channel, over the music.
  private val attributes = AudioAttributes.Builder()
      .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
      .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
      .build()
  private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
      .setAudioAttributes(attributes)
      .setOnAudioFocusChangeListener({ change ->
        // A call came in mid-sentence. Stop, so the message is neither talked over the call
        // nor counted as heard.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
      }, main)
      .build()

  private var engine: TextToSpeech? = null
  private var ready = false
  private var waiting: Pair<String, (Spoken) -> Unit>? = null
  private var speakingId: String? = null
  private var speakingDone: ((Spoken) -> Unit)? = null
  private var serial = 0

  /**
   * A speech service that is killed mid-sentence never calls back. Without this the music
   * would stay ducked and the board would say "Reading" until someone tapped it.
   */
  private val overdue = Runnable { speakingId?.let(::failed) }

  override fun say(text: String, done: (Spoken) -> Unit) {
    stop()
    if (engine == null) {
      engine = TextToSpeech(context) { status -> main.post { started(status) } }
    }
    if (ready) speak(text, done) else waiting = text to done
  }

  override fun stop() {
    waiting?.let { (_, done) ->
      waiting = null
      done(Spoken.INTERRUPTED)
    }
    speakingId?.let { id ->
      engine?.stop()
      finish(id, Spoken.INTERRUPTED)
    }
  }

  private fun started(status: Int) {
    val tts = engine
    if (status != TextToSpeech.SUCCESS || tts == null) {
      discardEngine()
      waiting?.let { (_, done) ->
        waiting = null
        done(Spoken.FAILED)
      }
      return
    }
    tts.setAudioAttributes(attributes)
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String) = Unit

      override fun onDone(id: String) {
        main.post { finish(id, Spoken.FINISHED) }
      }

      @Deprecated("Deprecated in the platform; still the callback older engines use")
      override fun onError(id: String) {
        main.post { failed(id) }
      }

      override fun onStop(id: String, interrupted: Boolean) {
        main.post { finish(id, Spoken.INTERRUPTED) }
      }
    })
    ready = true
    waiting?.let { (text, done) ->
      waiting = null
      speak(text, done)
    }
  }

  private fun speak(text: String, done: (Spoken) -> Unit) {
    // Refused means something that outranks a message has the sound, in practice a phone call.
    // Talking over it would be worse than saying nothing.
    if (audio.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      done(Spoken.FAILED)
      return
    }
    val id = "say-${++serial}"
    speakingId = id
    speakingDone = done
    main.postDelayed(overdue, OVERDUE_MS + text.length * OVERDUE_PER_LETTER_MS)
    if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) failed(id)
  }

  /**
   * The engine could not speak. This object lives as long as the app does, and a speech
   * service that was updated or killed stays dead, so the next readout starts a fresh one.
   */
  private fun failed(id: String) {
    if (id != speakingId) return
    finish(id, Spoken.FAILED)
    discardEngine()
  }

  private fun discardEngine() {
    engine?.shutdown()
    engine = null
    ready = false
  }

  private fun finish(id: String, how: Spoken) {
    if (id != speakingId) return
    val done = speakingDone
    speakingId = null
    speakingDone = null
    main.removeCallbacks(overdue)
    audio.abandonAudioFocusRequest(focus)
    done?.invoke(how)
  }

  private companion object {
    /** Far longer than any engine takes to say the text: fifteen seconds, plus time per letter. */
    const val OVERDUE_MS = 15_000L
    const val OVERDUE_PER_LETTER_MS = 150L
  }
}
