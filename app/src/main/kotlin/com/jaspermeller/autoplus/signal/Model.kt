package com.jaspermeller.autoplus.signal

import android.graphics.Bitmap

/** One incoming message as the phone's notification carries it. */
data class Message(val sender: String, val text: String, val at: Long)

/**
 * A person or group with messages the phone still counts as unread.
 * [key] is the notification's key, so it is stable for as long as the notification lives.
 */
data class Conversation(
    val key: String,
    val title: String,
    val app: String,
    val isGroup: Boolean,
    val messages: List<Message>,
    val canReply: Boolean,
) {
  val latestAt: Long get() = messages.lastOrNull()?.at ?: 0L

  /**
   * Whether [other] is the same thread saying the same things. Times are left out: an app that
   * sends no times gets the time of each repost instead, so they change without anything new.
   */
  fun sameWords(other: Conversation): Boolean =
      title == other.title && messages.map { it.sender to it.text } == other.messages.map { it.sender to it.text }
}

/** What the phone's active media session reports. */
data class NowPlaying(
    val title: String,
    val artist: String,
    val source: String,
    val playing: Boolean,
    val canPrevious: Boolean,
    val canNext: Boolean,
    val art: Bitmap?,
)

/** Everything the board needs from the phone. The board never touches Android services directly. */
interface Signals {
  /** False until notification access is granted and the listener is bound. */
  val ready: Boolean

  /** Newest first. */
  fun conversations(): List<Conversation>

  fun nowPlaying(): NowPlaying?

  fun previous()

  fun toggle()

  fun next()

  /** Sends [text] through the notification's own reply action. False when the app no longer accepts it. */
  fun reply(key: String, text: String): Boolean

  /**
   * The driver has heard [talk] read out in full, so the phone can stop showing it as unread.
   * If the conversation now holds anything else (a message arrived meanwhile), it was not all
   * heard and nothing is marked.
   */
  fun heard(talk: Conversation)
}
