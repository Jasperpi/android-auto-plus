package com.jaspermeller.autoplus.car

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.text.format.DateFormat
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioRecord
import com.jaspermeller.autoplus.voice.MicFeed
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The car's cabin microphone, piped to the speech recognizer. Android Auto records it on the
 * app's behalf, which is also the only microphone a car app may use while it is not on the
 * phone's screen. The caller holds the cabin's audio focus for as long as this is open.
 *
 * The car has one microphone session, and the library's start and stop both talk to the host,
 * so each recording is a [Take] with its own state and takes never overlap: a new one waits
 * for the one before it to finish stopping.
 */
class CarMic(private val carContext: CarContext) : MicFeed {
  private class Take(val record: CarAudioRecord) {
    @Volatile var running = true

    /** Counted down when this take has fully stopped. */
    val ended = CountDownLatch(1)
  }

  private var take: Take? = null
  private var latest: Take? = null

  override fun open(refused: () -> Unit): ParcelFileDescriptor? {
    close()
    if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
    return try {
      val record = CarAudioRecord.create(carContext)
      val (read, write) = ParcelFileDescriptor.createPipe()
      val previous = latest
      val mine = Take(record).also {
        take = it
        latest = it
      }
      thread(name = "car-mic") {
        try {
          // A late stop from the take before must not land on this one.
          previous?.ended?.await(2, TimeUnit.SECONDS)
          ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
            // Checked under the take's lock, so a close that came first is never followed by a start.
            val started = synchronized(mine) {
              if (mine.running) record.startRecording()
              mine.running
            }
            var delivered = 0L
            if (started) {
              val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
              while (mine.running) {
                val count = record.read(buffer, 0, buffer.size)
                if (count < 0) break
                out.write(buffer, 0, count)
                delivered += count
              }
            }
            // A host that will not lend its microphone answers with an empty stream, not an
            // error. Say so before the pipe closes, or the recognizer's own "heard nothing"
            // would arrive first and hide the reason.
            if (started && mine.running && delivered == 0L) refused()
          }
        } catch (closed: Exception) {
          // The recognizer hung up, or the car went away. Either way listening is over.
        } finally {
          try {
            record.stopRecording()
          } catch (alreadyStopped: Exception) {
            // Nothing left to release.
          }
          mine.ended.countDown()
        }
      }
      read
    } catch (unavailable: Exception) {
      // A host too old to lend its mic: the phone's own mic is used.
      null
    }
  }

  override fun close() {
    val ending = take ?: return
    take = null
    ending.running = false
    // Stop now rather than when the reader next wakes: it frees the cabin microphone and wakes
    // a reader blocked on the car. Off the main thread, because the library holds its lock
    // while it asks the host to start recording, and a slow host must not freeze the board.
    thread(name = "car-mic-stop") {
      synchronized(ending) {
        try {
          ending.record.stopRecording()
        } catch (alreadyStopped: Exception) {
          // Nothing left to release.
        }
      }
    }
  }
}

fun timeFormat(context: Context): (Long) -> String {
  val format = DateFormat.getTimeFormat(context)
  return { at -> format.format(Date(at)) }
}

/** False when the driver has turned animations off on the phone. */
fun motionAllowed(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
