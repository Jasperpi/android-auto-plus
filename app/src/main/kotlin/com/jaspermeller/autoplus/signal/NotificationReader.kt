package com.jaspermeller.autoplus.signal

import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput

/** Where a spoken reply goes: the messaging app's own reply action. */
class ReplyRoute(val intent: PendingIntent, val inputs: Array<RemoteInput>)

/** A conversation plus the actions its notification offers. */
class Reading(val conversation: Conversation, val reply: ReplyRoute?, val markRead: PendingIntent?)

/**
 * Turns a messaging notification into a [Reading]. Returns null for anything that is not an
 * unread message from someone else: summaries, media, downloads, the user's own sent messages.
 */
object NotificationReader {

  fun read(key: String, app: String, postedAt: Long, notification: Notification): Reading? {
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null

    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    if (style == null && notification.category != Notification.CATEGORY_MESSAGE) return null

    val messages: List<Message>
    val title: String
    val isGroup: Boolean
    if (style != null) {
      // A null person means the user wrote it (apps append the user's reply to the thread).
      messages = style.messages
          .filter { it.person != null && !it.text.isNullOrBlank() }
          .map { Message(it.person?.name?.toString().orEmpty(), it.text.toString(), it.timestamp) }
      isGroup = style.isGroupConversation
      title = style.conversationTitle?.toString()?.takeIf { it.isNotBlank() }
          ?: messages.lastOrNull()?.sender?.takeIf { it.isNotBlank() }
          ?: notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    } else {
      val extras = notification.extras
      title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
      val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
          ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
      messages = if (text.isBlank()) emptyList() else listOf(Message(title, text, postedAt))
      isGroup = false
    }
    if (messages.isEmpty() || title.isBlank()) return null

    // Apps built for cars often hide reply and mark-as-read from the shade, so look at both lists.
    val actions = buildList {
      for (i in 0 until NotificationCompat.getActionCount(notification)) {
        NotificationCompat.getAction(notification, i)?.let(::add)
      }
      addAll(NotificationCompat.getInvisibleActions(notification))
    }
    val replyAction = actions
        .filter { action -> action.actionIntent != null && action.remoteInputs?.any { it.allowFreeFormInput } == true }
        .maxByOrNull { if (it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY) 1 else 0 }
    val reply = replyAction?.let { ReplyRoute(it.actionIntent!!, it.remoteInputs!!) }
    val markRead = actions
        .firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ }
        ?.actionIntent

    return Reading(
        Conversation(key, title, app, isGroup, messages, canReply = reply != null),
        reply,
        markRead,
    )
  }
}
