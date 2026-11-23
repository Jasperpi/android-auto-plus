package com.jaspermeller.autoplus.demo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import com.jaspermeller.autoplus.R

/**
 * Pretends to be a messaging app and a music player so the board can be tried without either.
 *
 *   adb shell am broadcast -n com.jaspermeller.autoplus/.demo.DemoReceiver \
 *       -a com.jaspermeller.autoplus.demo.MESSAGE --es from "Maya Okafor" --es text "are you coming"
 *   adb shell am broadcast -n com.jaspermeller.autoplus/.demo.DemoReceiver \
 *       -a com.jaspermeller.autoplus.demo.PLAY --es title "Pink + White" --es artist "Frank Ocean"
 *
 * Replies, mark-as-read and transport presses are written to logcat under the tag AutoPlusDemo,
 * which is how the device tests confirm the board really reached the "app".
 */
class DemoReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val from = intent.getStringExtra(FROM) ?: "Maya Okafor"
    when (intent.action) {
      MESSAGE -> message(context, from, intent.getStringExtra("text") ?: "are you still coming tonight")
      REPLIED -> {
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)
        Log.i(TAG, "replied to=$from text=$text")
        clear(context, from)
      }
      READ -> {
        Log.i(TAG, "read from=$from")
        clear(context, from)
      }
      PLAY -> play(context, intent.getStringExtra("title") ?: TRACKS[0].first, intent.getStringExtra("artist") ?: TRACKS[0].second)
      STOP -> {
        session?.release()
        session = null
      }
    }
  }

  private fun message(context: Context, from: String, text: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Demo messages", NotificationManager.IMPORTANCE_DEFAULT))
    val texts = threads.getOrPut(from) { ArrayList() }.apply { add(text) }

    val sender = Person.Builder().setName(from).build()
    val style = Notification.MessagingStyle(Person.Builder().setName("Me").build())
    val now = System.currentTimeMillis()
    texts.forEachIndexed { i, line -> style.addMessage(line, now - (texts.size - 1 - i) * 1000L, sender) }

    val id = from.hashCode()
    val reply = Notification.Action.Builder(
        null,
        "Reply",
        PendingIntent.getBroadcast(context, id, own(context, REPLIED, from), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
        .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel("Reply").build())
        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY)
        .build()
    val read = Notification.Action.Builder(
        null,
        "Mark as read",
        PendingIntent.getBroadcast(context, id, own(context, READ, from), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .build()

    manager.notify(from, 1, Notification.Builder(context, CHANNEL)
        .setSmallIcon(R.drawable.ic_read)
        .setStyle(style)
        .setCategory(Notification.CATEGORY_MESSAGE)
        .addAction(reply)
        .addAction(read)
        .build())
  }

  private fun clear(context: Context, from: String) {
    threads.remove(from)
    context.getSystemService(NotificationManager::class.java).cancel(from, 1)
  }

  private fun own(context: Context, action: String, from: String) =
      Intent(context, DemoReceiver::class.java).setAction(action).putExtra(FROM, from)

  private fun play(context: Context, title: String, artist: String) {
    val live = session ?: MediaSession(context.applicationContext, TAG).also { created ->
      session = created
      created.setCallback(object : MediaSession.Callback() {
        override fun onPlay() = press("play", playing = true)

        override fun onPause() = press("pause", playing = false)

        override fun onSkipToNext() {
          track = (track + 1) % TRACKS.size
          show(TRACKS[track].first, TRACKS[track].second)
          press("next", playing = true)
        }

        override fun onSkipToPrevious() {
          track = (track + TRACKS.size - 1) % TRACKS.size
          show(TRACKS[track].first, TRACKS[track].second)
          press("previous", playing = true)
        }
      })
      created.isActive = true
    }
    show(title, artist)
    live.setPlaybackState(state(playing = true))
  }

  companion object {
    const val TAG = "AutoPlusDemo"
    const val MESSAGE = "com.jaspermeller.autoplus.demo.MESSAGE"
    const val REPLIED = "com.jaspermeller.autoplus.demo.REPLIED"
    const val READ = "com.jaspermeller.autoplus.demo.READ"
    const val PLAY = "com.jaspermeller.autoplus.demo.PLAY"
    const val STOP = "com.jaspermeller.autoplus.demo.STOP"
    private const val FROM = "from"
    private const val CHANNEL = "demo-messages"
    private const val REPLY_KEY = "reply"

    private val TRACKS = listOf(
        "Pink + White" to "Frank Ocean",
        "Motion Sickness" to "Phoebe Bridgers",
        "Electric Feel" to "MGMT",
    )
    private val threads = HashMap<String, ArrayList<String>>()
    private var session: MediaSession? = null
    private var track = 0

    private fun press(what: String, playing: Boolean) {
      Log.i(TAG, "pressed $what")
      session?.setPlaybackState(state(playing))
    }

    private fun show(title: String, artist: String) {
      session?.setMetadata(MediaMetadata.Builder()
          .putString(MediaMetadata.METADATA_KEY_TITLE, title)
          .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
          .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, sleeve())
          .build())
    }

    private fun state(playing: Boolean): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
        .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0L, 1f)
        .build()

    private fun sleeve(): Bitmap {
      val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
      val paint = Paint().apply {
        shader = LinearGradient(0f, 0f, 240f, 240f, Color.rgb(238, 170, 190), Color.rgb(250, 250, 244), Shader.TileMode.CLAMP)
      }
      Canvas(bitmap).drawRect(0f, 0f, 240f, 240f, paint)
      return bitmap
    }
  }
}
