package com.jaspermeller.autoplus.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * A microphone other than the phone's own. In the car the phone is in a pocket or a cubby,
 * so the board hands the recognizer the car's cabin microphone instead.
 */
interface MicFeed {
  /** 16 kHz mono 16-bit PCM, or null when the feed cannot start (the phone's mic is used instead). */
  fun open(): ParcelFileDescriptor?

  fun close()
}

/** Speech to text through the phone's recognizer, on-device when the phone has one. Main thread only. */
class RecognizerDictation(private val context: Context, private val feed: MicFeed? = null) : Dictation {
  private var recognizer: SpeechRecognizer? = null
  private var done: ((Heard) -> Unit)? = null
  private var pcm: ParcelFileDescriptor? = null

  override fun listen(partial: (String) -> Unit, level: (Float) -> Unit, done: (Heard) -> Unit) {
    cancel()
    if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
      done(Heard.Blocked("Allow the microphone in Auto++ on your phone"))
      return
    }
    val created = when {
      SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
      SpeechRecognizer.isRecognitionAvailable(context) -> SpeechRecognizer.createSpeechRecognizer(context)
      else -> {
        done(Heard.Blocked("This phone has no speech recognition"))
        return
      }
    }
    recognizer = created
    this.done = done
    created.setRecognitionListener(object : RecognitionListener {
      override fun onRmsChanged(rmsdB: Float) = level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))

      override fun onPartialResults(results: Bundle?) {
        best(results)?.let(partial)
      }

      override fun onResults(results: Bundle?) = finish(best(results)?.let(Heard::Words) ?: Heard.Silence)

      override fun onError(error: Int) = finish(
          if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            Heard.Blocked("Allow the microphone in Auto++ on your phone")
          } else {
            Heard.Silence
          })

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
    pcm = feed?.open()
    pcm?.let { source ->
      request.putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, source)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
          .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    }
    created.startListening(request)
  }

  override fun cancel() {
    done = null
    recognizer?.cancel()
    release()
  }

  private fun finish(heard: Heard) {
    val report = done
    release()
    report?.invoke(heard)
  }

  private fun release() {
    done = null
    recognizer?.destroy()
    recognizer = null
    feed?.close()
    try {
      pcm?.close()
    } catch (alreadyClosed: java.io.IOException) {
      // Nothing left to release.
    }
    pcm = null
  }

  private fun best(results: Bundle?): String? =
      results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
