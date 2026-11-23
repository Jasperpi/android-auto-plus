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

  /** [service] has stopped listening. A stale service going away must not unhook the live one. */
  fun listenerDisconnected(service: PhoneListener) {
    if (listener !== service) return
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
    // An action can carry other inputs beside the text box (a choice list); only the box gets the words.
    route.inputs.filter { it.allowFreeFormInput }.forEach { results.putCharSequence(it.resultKey, text) }
    val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
    RemoteInput.addResultsToIntent(route.inputs, fill, results)
    return try {
      route.intent.send(context, 0, fill)
      inbox.remove(key)
      true
    } catch (cancelled: PendingIntent.CanceledException) {
      false
    } catch (refused: RuntimeException) {
      // The system would not deliver it (the app was uninstalled or restricted mid-drive).
      false
    }
  }

  override fun heard(talk: Conversation) {
    val key = talk.key
    // Hearing it a second time must not fire the app's action again.
    val reading = inbox.showing(key) ?: return
    // Something arrived while the car was talking. Marking the thread read now would bury a
    // message nobody heard, so it stays on the board with the new one counted. The messages
    // themselves are compared, since senders' clocks cannot be trusted to run forwards.
    if (!reading.conversation.sameWords(talk)) return
    try {
      // Prefer the app's own action so its unread badge clears too.
      reading.markRead?.send() ?: dismiss(key)
    } catch (cancelled: PendingIntent.CanceledException) {
      dismiss(key)
    } catch (refused: RuntimeException) {
      dismiss(key)
    }
    inbox.remove(key)
  }

  private fun dismiss(key: String) {
    try {
      listener?.cancelNotification(key)
    } catch (revoked: SecurityException) {
      // Notification access was switched off mid-readout; there is nothing left to clear.
    }
  }

  private fun announce() {
    watchers.forEach { it() }
  }
}
