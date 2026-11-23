package com.jaspermeller.autoplus.signal

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NotificationReaderTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val me = Person.Builder().setName("Me").build()
  private val maya = Person.Builder().setName("Maya Okafor").build()

  private fun pending(action: String, mutable: Boolean = false): PendingIntent = PendingIntent.getBroadcast(
      context, 0, Intent(action).setPackage(context.packageName),
      if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

  private fun replyAction(): NotificationCompat.Action =
      NotificationCompat.Action.Builder(0, "Reply", pending("reply", mutable = true))
          .addRemoteInput(RemoteInput.Builder("text").build())
          .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
          .build()

  private fun readAction(): NotificationCompat.Action =
      NotificationCompat.Action.Builder(0, "Mark as read", pending("read"))
          .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
          .build()

  private fun builder() = NotificationCompat.Builder(context, "messages").setSmallIcon(android.R.drawable.ic_dialog_email)

  private fun read(notification: Notification) = NotificationReader.read("key", "Messages", 500L, notification)

  @Test
  fun aMessagingNotificationBecomesAConversation() {
    val style = NotificationCompat.MessagingStyle(me)
        .addMessage("are you still coming tonight", 100L, maya)
        .addMessage("we saved you a seat", 200L, maya)
    val reading = read(builder().setStyle(style).addAction(replyAction()).addAction(readAction()).build())!!

    val talk = reading.conversation
    assertEquals("key", talk.key)
    assertEquals("Maya Okafor", talk.title)
    assertEquals("Messages", talk.app)
    assertFalse(talk.isGroup)
    assertEquals(listOf("are you still coming tonight", "we saved you a seat"), talk.messages.map { it.text })
    assertEquals(200L, talk.latestAt)
    assertTrue(talk.canReply)
    assertNotNull(reading.reply)
    assertNotNull(reading.markRead)
  }

  @Test
  fun aGroupUsesItsConversationTitleAndKeepsEachSender() {
    val dev = Person.Builder().setName("Dev").build()
    val style = NotificationCompat.MessagingStyle(me)
        .setConversationTitle("Mock trial captains")
        .setGroupConversation(true)
        .addMessage("Scrimmage moved to 4", 100L, dev)
        .addMessage("ok", 150L, maya)
    val talk = read(builder().setStyle(style).build())!!.conversation

    assertEquals("Mock trial captains", talk.title)
    assertTrue(talk.isGroup)
    assertEquals(listOf("Dev", "Maya Okafor"), talk.messages.map { it.sender })
    assertFalse("no reply action was offered", talk.canReply)
  }

  @Test
  fun actionsHiddenFromTheShadeStillCount() {
    // Apps built for Android Auto attach reply and mark-as-read as invisible actions.
    val style = NotificationCompat.MessagingStyle(me).addMessage("hello", 100L, maya)
    val reading = read(builder().setStyle(style).addInvisibleAction(replyAction()).addInvisibleAction(readAction()).build())!!

    assertTrue(reading.conversation.canReply)
    assertNotNull(reading.markRead)
  }

  @Test
  fun aThreadTheDriverHasAnsweredIsNotUnread() {
    // What a messaging app posts right after a reply: the old message, then the driver's own.
    val self: Person? = null
    val answered = NotificationCompat.MessagingStyle(me)
        .addMessage("hello", 100L, maya)
        .addMessage("On my way", 200L, self)

    assertNull(read(builder().setStyle(answered).build()))
  }

  @Test
  fun onlyWhatArrivedAfterTheDriversLastMessageIsUnread() {
    val self: Person? = null
    val style = NotificationCompat.MessagingStyle(me)
        .addMessage("hello", 100L, maya)
        .addMessage("On my way", 200L, self)
        .addMessage("ok see you", 300L, maya)
        .addMessage("bring the binder", 400L, maya)
    val talk = read(builder().setStyle(style).build())!!.conversation

    assertEquals(listOf("ok see you", "bring the binder"), talk.messages.map { it.text })
  }

  @Test
  fun anAppThatNamesTheDriverAsSenderIsStillTheDriver() {
    // Some apps pass the style's own user as the sender instead of leaving it empty.
    val style = NotificationCompat.MessagingStyle(me)
        .addMessage("hello", 100L, maya)
        .addMessage("On my way", 200L, me)

    assertNull("the driver's own reply must never be read back as new", read(builder().setStyle(style).build()))
  }

  @Test
  fun aMarkReadActionThatOpensAScreenIsNotUsed() {
    val opensScreen = PendingIntent.getActivity(
        context, 0, Intent("read").setPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
    val action = NotificationCompat.Action.Builder(0, "Mark as read", opensScreen)
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
        .build()
    val style = NotificationCompat.MessagingStyle(me).addMessage("hello", 100L, maya)

    assertNull(read(builder().setStyle(style).addAction(action).build())!!.markRead)
  }

  @Test
  fun aReplyThatOpensAScreenIsNotOffered() {
    val opensScreen = PendingIntent.getActivity(
        context, 0, Intent("reply").setPackage(context.packageName), PendingIntent.FLAG_MUTABLE)
    val action = NotificationCompat.Action.Builder(0, "Reply", opensScreen)
        .addRemoteInput(RemoteInput.Builder("text").build())
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
        .build()
    val style = NotificationCompat.MessagingStyle(me).addMessage("hello", 100L, maya)

    assertFalse(read(builder().setStyle(style).addAction(action).build())!!.conversation.canReply)
  }

  @Test
  fun aPlainMessageNotificationFallsBackToTitleAndText() {
    val notification = builder()
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .setContentTitle("Dad")
        .setContentText("Call when you get there")
        .build()
    val talk = read(notification)!!.conversation

    assertEquals("Dad", talk.title)
    assertEquals(listOf(Message("Dad", "Call when you get there", 500L)), talk.messages)
  }

  @Test
  fun groupSummariesAreIgnored() {
    val style = NotificationCompat.MessagingStyle(me).addMessage("hello", 100L, maya)
    assertNull(read(builder().setStyle(style).setGroup("chats").setGroupSummary(true).build()))
  }

  @Test
  fun anythingThatIsNotAMessageIsIgnored() {
    assertNull(read(builder().setContentTitle("Download complete").setContentText("report.pdf").build()))
    assertNull(read(builder().setCategory(NotificationCompat.CATEGORY_TRANSPORT).setContentTitle("Pink + White").build()))
  }

  @Test
  fun aReplyActionThatOnlyOffersChoicesIsNotAVoiceReply() {
    val choicesOnly = NotificationCompat.Action.Builder(0, "Reply", pending("reply", mutable = true))
        .addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(false).setChoices(arrayOf("Yes", "No")).build())
        .build()
    val style = NotificationCompat.MessagingStyle(me).addMessage("hello", 100L, maya)
    assertFalse(read(builder().setStyle(style).addAction(choicesOnly).build())!!.conversation.canReply)
  }
}
