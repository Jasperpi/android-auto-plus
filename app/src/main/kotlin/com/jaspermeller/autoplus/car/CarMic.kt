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
 * phone's screen.
 */
class CarMic(private val carContext: CarContext) : MicFeed {
  @Volatile private var running = false

  override fun open(): ParcelFileDescriptor? {
    close()
    if (carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
    return try {
      val record = CarAudioRecord.create(carContext)
      val (read, write) = ParcelFileDescriptor.createPipe()
      running = true
      thread(name = "car-mic") {
        try {
          ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
            val buffer = ByteArray(CarAudioRecord.AUDIO_CONTENT_BUFFER_SIZE)
            record.startRecording()
            while (running) {
              val count = record.read(buffer, 0, buffer.size)
              if (count < 0) break
              out.write(buffer, 0, count)
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
      }
      read
    } catch (unavailable: Exception) {
      // A host too old to lend its mic, or one that refused: the phone's mic is used.
      null
    }
  }

  override fun close() {
    running = false
  }
}

fun timeFormat(context: Context): (Long) -> String {
  val format = DateFormat.getTimeFormat(context)
  return { at -> format.format(Date(at)) }
}

/** False when the driver has turned animations off on the phone. */
fun motionAllowed(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f
