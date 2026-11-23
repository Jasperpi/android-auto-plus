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
import kotlin.concurrent.thread

/**
 * The car's cabin microphone, piped to the speech recognizer. Android Auto records it on the
 * app's behalf, which is also the only microphone a car app may use while it is not on the
 * phone's screen. The caller holds the cabin's audio focus for as long as this is open.
 */
class CarMic(private val carContext: CarContext) : MicFeed {
  /** One round of recording. Each has its own flag, so closing one can never revive another. */
  private class Take(val record: CarAudioRecord) {
    @Volatile var running = true
  }

  private var take: Take? = null

  override fun open(refused: () -> Unit): ParcelFileDescriptor? {
    close()
    if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
    return try {
      val record = CarAudioRecord.create(carContext)
      val (read, write) = ParcelFileDescriptor.createPipe()
      val mine = Take(record).also { take = it }
      thread(name = "car-mic") {
        var delivered = 0L
        try {
          ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            record.startRecording()
            while (mine.running) {
              val count = record.read(buffer, 0, buffer.size)
              if (count < 0) break
              out.write(buffer, 0, count)
              delivered += count
            }
          }
        } catch (closed: Exception) {
          // The recognizer hung up, or the car went away. Either way listening is over.
        } finally {
          try {
            record.stopRecording()
          } catch (alreadyStopped: Exception) {
            // Nothing left to release.
          }
        }
        // A host that will not lend its microphone answers with an empty stream, not an error.
        if (mine.running && delivered == 0L) refused()
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
    // Stop here rather than when the reader thread next wakes: it frees the cabin microphone
    // at once, wakes a reader blocked on the car, and keeps a late stop from landing on the
    // next take. (The library releases its lock before it blocks, so this cannot deadlock.)
    try {
      ending.record.stopRecording()
    } catch (alreadyStopped: Exception) {
      // Nothing left to release.
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
