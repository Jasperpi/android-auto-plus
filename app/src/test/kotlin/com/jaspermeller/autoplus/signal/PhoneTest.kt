package com.jaspermeller.autoplus.signal

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PhoneTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val phone = Phone(context)
  private val replies = ArrayList<String?>()
  private var reads = 0

  private fun pending(action: String, mutable: Boolean): PendingIntent = PendingIntent.getBroadcast(
      context, 0, Intent(action).setPackage(context.packageName),
      if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)

  /** A stand-in messaging app: its reply and mark-read actions come back to this test. */
  @Before
  fun standInMessagingApp() {
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        replies += RemoteInput.getResultsFromIntent(intent)?.getCharSequence("text")?.toString()
      }
    }, IntentFilter("test.REPLY"), Context.RECEIVER_NOT_EXPORTED)
    context.registerReceiver(object : BroadcastReceiver() {
      override fun onReceive(c: Context, intent: Intent) {
        reads++
      }
    }, IntentFilter("test.READ"), Context.RECEIVER_NOT_EXPORTED)
  }

  private fun post(key: String, withReply: Boolean = true) {
    val style = NotificationCompat.MessagingStyle(Person.Builder().setName("Me").build())
        .addMessage("are you coming", 100L, Person.Builder().setName("Maya").build())
    val builder = NotificationCompat.Builder(context, "messages")
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setStyle(style)
        .addAction(NotificationCompat.Action.Builder(0, "Read", pending("test.READ", mutable = false))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ).build())
    if (withReply) {
      builder.addAction(NotificationCompat.Action.Builder(0, "Reply", pending("test.REPLY", mutable = true))
          .addRemoteInput(RemoteInput.Builder("text").build())
          .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).build())
    }
    phone.inbox.put(NotificationReader.read(key, "Messages", 100L, builder.build())!!)
  }

  private fun deliver() = shadowOf(Looper.getMainLooper()).idle()

  @Test
  fun aReplyReachesTheMessagingAppThroughItsOwnAction() {
    post("a")

    assertTrue(phone.reply("a", "On my way"))
    deliver()

    assertEquals(listOf<String?>("On my way"), replies)
    assertTrue("an answered conversation leaves the board", phone.conversations().isEmpty())
  }

  @Test
  fun hearingAConversationMarksItReadOnce() {
    post("a")

    val read = phone.conversations().single()
    phone.heard(read)
    phone.heard(read)
    deliver()

    assertEquals(1, reads)
    assertTrue(phone.conversations().isEmpty())
  }

  @Test
  fun aMessageThatArrivedDuringTheReadoutKeepsTheThreadUnread() {
    post("a")
    // What the car read aloud: the thread before its latest message arrived. The newcomer
    // carries an older clock time than the one read, which a timestamp check would miss.
    val read = phone.conversations().single().let { it.copy(messages = listOf(Message("Maya", "earlier", 900L))) }

    phone.heard(read)
    deliver()

    assertEquals(0, reads)
    assertEquals(listOf("Maya"), phone.conversations().map { it.title })
  }

  @Test
  fun aRepostThatOnlyChangesTheTimesStillCountsAsHeard() {
    post("a")
    val read = phone.conversations().single().let { talk -> talk.copy(messages = talk.messages.map { it.copy(at = 1L) }) }

    phone.heard(read)
    deliver()

    assertEquals(1, reads)
  }

  @Test
  fun aReplyStillWorksAfterTheConversationWasHeard() {
    post("a")
    phone.heard(phone.conversations().single())

    assertTrue(phone.reply("a", "Yes"))
    deliver()

    assertEquals(listOf<String?>("Yes"), replies)
  }

  @Test
  fun aReplyWithNowhereToGoIsRefused() {
    post("a", withReply = false)

    assertFalse(phone.reply("a", "Yes"))
    assertFalse(phone.reply("never-seen", "Yes"))
    deliver()

    assertTrue(replies.isEmpty())
  }

  @Test
  fun aCancelledReplyActionIsReportedAsFailure() {
    post("a")
    pending("test.REPLY", mutable = true).cancel()

    assertFalse(phone.reply("a", "Yes"))
  }

  @Test
  fun watchersHearAboutInboxChanges() {
    var changes = 0
    val watcher: () -> Unit = { changes++ }
    phone.watch(watcher)
    post("a")
    phone.inbox.remove("a")
    phone.unwatch(watcher)
    post("b")

    assertEquals(2, changes)
  }

  @Test
  fun conversationsAreNewestFirst() {
    val older = Conversation("old", "Dad", "Messages", false, listOf(Message("Dad", "hi", 100L)), true)
    val newer = Conversation("new", "Maya", "Messages", false, listOf(Message("Maya", "hi", 900L)), true)
    phone.inbox.put(Reading(older, null, null))
    phone.inbox.put(Reading(newer, null, null))

    assertEquals(listOf("Maya", "Dad"), phone.conversations().map { it.title })
  }

  @Test
  fun aMediaSessionMapsToNowPlaying() {
    val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, "Pink + White")
        .putString(MediaMetadata.METADATA_KEY_ARTIST, "Frank Ocean")
        .build()
    val state = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f)
        .build()

    val track = MediaWatcher.nowPlayingOf(metadata, state, "Spotify")!!

    assertEquals("Pink + White", track.title)
    assertEquals("Frank Ocean", track.artist)
    assertEquals("Spotify", track.source)
    assertTrue(track.playing)
    assertTrue(track.canNext)
    assertFalse(track.canPrevious)
  }

  @Test
  fun bufferingStillShowsPause() {
    val metadata = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, "Pink + White").build()
    val state = PlaybackState.Builder().setState(PlaybackState.STATE_BUFFERING, 0L, 1f).build()

    assertTrue(MediaWatcher.nowPlayingOf(metadata, state, "Spotify")!!.playing)
  }

  @Test
  fun aSessionWithNoTitleIsNotShown() {
    assertNull(MediaWatcher.nowPlayingOf(null, null, "Spotify"))
    assertNull(MediaWatcher.nowPlayingOf(MediaMetadata.Builder().build(), null, "Spotify"))
  }

  @Test
  fun displayTitleWinsAndPausedIsNotPlaying() {
    val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, "Episode 12")
        .putString(MediaMetadata.METADATA_KEY_TITLE, "raw-title")
        .putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, "The Daily")
        .build()
    val state = PlaybackState.Builder().setState(PlaybackState.STATE_PAUSED, 0L, 1f).build()

    val track = MediaWatcher.nowPlayingOf(metadata, state, "Pocket Casts")!!

    assertEquals("Episode 12", track.title)
    assertEquals("The Daily", track.artist)
    assertFalse(track.playing)
  }
}
