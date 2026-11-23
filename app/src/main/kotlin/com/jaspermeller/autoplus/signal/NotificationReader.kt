package com.jaspermeller.autoplus.signal

import android.app.Notification
import android.app.PendingIntent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/** Where a spoken reply goes: the messaging app's own reply action. */
class ReplyRoute(val intent: PendingIntent, val inputs: Array<RemoteInput>)

/** A conversation plus the actions its notification offers. */
class Reading(val conversation: Conversation, val reply: ReplyRoute?, val markRead: PendingIntent?)

/**
 * Turns a messaging notification into a [Reading]. Returns null for anything that is not an
 * unread message from someone else: summaries, media, downloads, a thread the driver has
 * already answered.
 */
object NotificationReader {

  fun read(key: String, app: String, postedAt: Long, notification: Notification): Reading? {
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    // "Checking for new messages" and the like: a standing notice, not something anyone wrote.
    if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null

    val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(notification)
    if (style == null && notification.category != Notification.CATEGORY_MESSAGE) return null

    val messages: List<Message>
    val title: String
    val isGroup: Boolean
    if (style != null) {
      // After a reply, apps repost the thread with the driver's own line appended (as a null
      // person, or as the style's user). Everything up to that line has been dealt with, so
      // only what arrived after it is unread.
      val user = style.user
      messages = style.messages
          .takeLastWhile { !isSelf(it.person, user) }
          .filter { !it.text.isNullOrBlank() }
          // Some apps leave the time off; the notification's own time is the best stand-in.
          .map { Message(it.person?.name?.toString().orEmpty(), it.text.toString(), it.timestamp.takeIf { at -> at > 0L } ?: postedAt) }
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
        // A reply that opens a screen cannot be trusted from the car: Android may quietly refuse
        // to start it from the background, and the board would say "Sent" when nothing was.
        .filter { action -> action.actionIntent?.isActivity == false && action.remoteInputs?.any { it.allowFreeFormInput } == true }
        .maxByOrNull { if (it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_REPLY) 1 else 0 }
    val reply = replyAction?.let { ReplyRoute(it.actionIntent!!, it.remoteInputs!!) }
    val markRead = actions
        // The same rule as for replies: an action that opens a screen is no use from the car.
        .firstOrNull { it.semanticAction == NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ && it.actionIntent?.isActivity == false }
        ?.actionIntent

    return Reading(
        Conversation(key, title, app, isGroup, messages, canReply = reply != null),
        reply,
        markRead,
    )
  }

  /** Whether [sender] is the phone's owner: no person at all, or the same person as the style's user. */
  private fun isSelf(sender: Person?, user: Person): Boolean {
    if (sender == null) return true
    return when {
      sender.key != null || user.key != null -> sender.key == user.key
      sender.uri != null || user.uri != null -> sender.uri == user.uri
      else -> sender.name?.toString() == user.name?.toString()
    }
  }
}
