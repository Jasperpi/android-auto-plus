package com.jaspermeller.autoplus.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.IOException

/**
 * A microphone other than the phone's own. In the car the phone is in a pocket or a cubby,
 * so the board hands the recognizer the car's cabin microphone instead.
 */
interface MicFeed {
  /**
   * 16 kHz mono 16-bit PCM, or null when the feed cannot start (the phone's mic is used instead).
   * [refused] is called, from any thread, if the feed ends without delivering any sound.
   */
  fun open(refused: () -> Unit): ParcelFileDescriptor?

  fun close()
}

/** Speech to text through the phone's recognizer, on-device when the phone has one. Main thread only. */
class RecognizerDictation(private val context: Context, private val feed: MicFeed? = null) : Dictation {
  private val main = Handler(Looper.getMainLooper())
  private val audio = context.getSystemService(AudioManager::class.java)

  private var recognizer: SpeechRecognizer? = null
  private var partial: ((String) -> Unit)? = null
  private var level: ((Float) -> Unit)? = null
  private var done: ((Heard) -> Unit)? = null
  private var pcm: ParcelFileDescriptor? = null
  private var focus: AudioFocusRequest? = null

  /**
   * The latest words the recognizer reported while still listening. With a piped microphone
   * some recognizers end with no final result, and what they had is better than "nothing":
   * the driver still sees it on the board before it sends.
   */
  private var soFar: String? = null

  /** Bumped for every recognizer started, so a late callback from an old one is ignored. */
  private var session = 0

  private val wrapUp = Runnable { recognizer?.stopListening() }
  private val giveUp = Runnable { finish(heardOr(null)) }

  override fun listen(partial: (String) -> Unit, level: (Float) -> Unit, done: (Heard) -> Unit) {
    cancel()
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      done(Heard.Blocked("Allow the microphone in Auto++ on your phone"))
      return
    }
    val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
    if (!onDevice && !SpeechRecognizer.isRecognitionAvailable(context)) {
      done(Heard.Blocked("This phone has no speech recognition"))
      return
    }

    // Quiet the cabin before opening the microphone. With music still playing, the recognizer
    // would hear the lyrics as the driver's words and the countdown would send them.
    val quiet = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        .setOnAudioFocusChangeListener({ change ->
          // Something else has taken the sound for good (music started elsewhere): stop listening.
          if (change == AudioManager.AUDIOFOCUS_LOSS) finish(Heard.Silence)
        }, main)
        .build()
    if (audio.requestAudioFocus(quiet) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
      done(Heard.Blocked("Cannot listen during a call"))
      return
    }
    focus = quiet
    this.partial = partial
    this.level = level
    this.done = done
    start(onDevice)
  }

  override fun cancel() {
    done = null
    recognizer?.cancel()
    release()
  }

  private fun start(onDevice: Boolean) {
    val mine = ++session
    val created = if (onDevice) {
      SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } else {
      SpeechRecognizer.createSpeechRecognizer(context)
    }
    recognizer = created
    created.setRecognitionListener(object : RecognitionListener {
      override fun onRmsChanged(rmsdB: Float) {
        if (mine == session) level?.invoke(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
      }

      override fun onPartialResults(results: Bundle?) {
        if (mine != session) return
        best(results)?.let { words ->
          soFar = words
          partial?.invoke(words)
        }
      }

      override fun onResults(results: Bundle?) {
        if (mine == session) finish(heardOr(best(results)))
      }

      override fun onError(error: Int) {
        if (mine != session) return
        when {
          // The on-device recognizer has no model for this language; the phone's usual one may.
          onDevice && error in NO_LANGUAGE && SpeechRecognizer.isRecognitionAvailable(context) -> {
            stopRecognizer()
            start(onDevice = false)
          }
          error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
              finish(Heard.Blocked("Allow the microphone in Auto++ on your phone"))
          else -> finish(heardOr(null))
        }
      }

      override fun onReadyForSpeech(params: Bundle?) = Unit

      override fun onBeginningOfSpeech() = Unit

      override fun onBufferReceived(buffer: ByteArray?) = Unit

      override fun onEndOfSpeech() = Unit

      override fun onEvent(eventType: Int, params: Bundle?) = Unit
    })

    val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    // The recognizer gets its own copy of the pipe when the request is delivered, which happens
    // after this method returns, so ours is closed with the session rather than here.
    pcm = feed?.open {
      main.post { if (mine == session) finish(Heard.Blocked("The car did not share its microphone")) }
    }
    pcm?.let { source ->
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    }
    try {
      created.startListening(request)
    } catch (refused: RuntimeException) {
      // The recognition service would not take the request. End the round properly, so the
      // microphone and the hold on the cabin's sound are both let go.
      finish(Heard.Silence)
      return
    }

    // A piped microphone never reaches its end, so a recognizer that waits for one would listen
    // forever with the cabin mic open. Ask for what it has, then stop regardless.
    main.postDelayed(wrapUp, WRAP_UP_MS)
    main.postDelayed(giveUp, GIVE_UP_MS)
  }

  private fun finish(heard: Heard) {
    val report = done
    release()
    report?.invoke(heard)
  }

  /** Ends the whole round: the recognizer, the microphone and the hold on the cabin's sound. */
  private fun release() {
    done = null
    partial = null
    level = null
    stopRecognizer()
    focus?.let(audio::abandonAudioFocusRequest)
    focus = null
  }

  /** Ends the current recognizer and its microphone but keeps the round open, for the fallback. */
  private fun stopRecognizer() {
    session++
    soFar = null
    main.removeCallbacks(wrapUp)
    main.removeCallbacks(giveUp)
    recognizer?.destroy()
    recognizer = null
    feed?.close()
    try {
      pcm?.close()
    } catch (alreadyClosed: IOException) {
      // Nothing left to release.
    }
    pcm = null
  }

  private fun heardOr(final: String?): Heard = (final ?: soFar)?.let(Heard::Words) ?: Heard.Silence

  private fun best(results: Bundle?): String? =
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

  private companion object {
    const val WRAP_UP_MS = 12_000L
    const val GIVE_UP_MS = 16_000L
    val NO_LANGUAGE = setOf(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
  }
}
