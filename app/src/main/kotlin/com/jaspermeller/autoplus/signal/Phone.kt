package com.jaspermeller.autoplus.signal

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.RemoteInput
import java.util.concurrent.CopyOnWriteArraySet

/** The phone's messages and music, gathered in one place for every board (car screen or phone). */
class Phone(private val context: Context) : Signals {
  private val watchers = CopyOnWriteArraySet<() -> Unit>()
  private var listener: PhoneListener? = null

  val inbox = Inbox(::announce)
  private val media = MediaWatcher(context, ComponentName(context, PhoneListener::class.java), ::announce)

  override val ready: Boolean get() = listener != null

  fun watch(watcher: () -> Unit) {
    watchers += watcher
  }

  fun unwatch(watcher: () -> Unit) {
    watchers -= watcher
  }

  fun listenerConnected(service: PhoneListener) {
    listener = service
    media.start()
    announce()
  }

  fun listenerDisconnected() {
    listener = null
    media.stop()
    inbox.clear()
    announce()
  }

  override fun conversations(): List<Conversation> = inbox.conversations

  override fun nowPlaying(): NowPlaying? = media.nowPlaying

  override fun previous() = media.previous()

  override fun toggle() = media.toggle()

  override fun next() = media.next()

  override fun reply(key: String, text: String): Boolean {
    val route = inbox.find(key)?.reply ?: return false
    val results = Bundle()
    route.inputs.forEach { results.putCharSequence(it.resultKey, text) }
    val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    RemoteInput.addResultsToIntent(route.inputs, fill, results)
    return try {
      route.intent.send(context, 0, fill)
      inbox.remove(key)
      true
    } catch (cancelled: PendingIntent.CanceledException) {
      false
    }
  }

  override fun heard(key: String) {
    // Hearing it a second time must not fire the app's action again.
    val reading = inbox.showing(key) ?: return
    try {
      // Prefer the app's own action so its unread badge clears too.
      reading.markRead?.send() ?: listener?.cancelNotification(key)
    } catch (cancelled: PendingIntent.CanceledException) {
      listener?.cancelNotification(key)
    }
    inbox.remove(key)
  }

  private fun announce() {
    watchers.forEach { it() }
  }
}
