package com.jaspermeller.autoplus.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener

/** Reads messages through the phone's text-to-speech engine, ducking the music while it talks. */
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
      .build()

  private var engine: TextToSpeech? = null
  private var ready = false
  private var waiting: Pair<String, (Boolean) -> Unit>? = null
  private var speakingId: String? = null
  private var speakingDone: ((Boolean) -> Unit)? = null
  private var serial = 0

  override fun say(text: String, done: (finished: Boolean) -> Unit) {
    stop()
    if (engine == null) {
      engine = TextToSpeech(context) { status -> main.post { started(status) } }
    }
    if (ready) speak(text, done) else waiting = text to done
  }

  override fun stop() {
    waiting?.let { (_, done) ->
      waiting = null
      done(false)
    }
    speakingId?.let { id ->
      engine?.stop()
      finish(id, false)
    }
  }

  private fun started(status: Int) {
    val tts = engine
    if (status != TextToSpeech.SUCCESS || tts == null) {
      engine = null
      waiting?.let { (_, done) ->
        waiting = null
        done(false)
      }
      return
    }
    tts.setAudioAttributes(attributes)
    tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
      override fun onStart(id: String) = Unit

      override fun onDone(id: String) {
        main.post { finish(id, true) }
      }

      @Deprecated("Deprecated in the platform; still the callback older engines use")
      override fun onError(id: String) {
        main.post { finish(id, false) }
      }

      override fun onStop(id: String, interrupted: Boolean) {
        main.post { finish(id, false) }
      }
    })
    ready = true
    waiting?.let { (text, done) ->
      waiting = null
      speak(text, done)
    }
  }

  private fun speak(text: String, done: (Boolean) -> Unit) {
    val id = "say-${++serial}"
    speakingId = id
    speakingDone = done
    audio.requestAudioFocus(focus)
    if (engine?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(id, false)
  }

  private fun finish(id: String, finished: Boolean) {
    if (id != speakingId) return
    val done = speakingDone
    speakingId = null
    speakingDone = null
    audio.abandonAudioFocusRequest(focus)
    done?.invoke(finished)
  }
}
