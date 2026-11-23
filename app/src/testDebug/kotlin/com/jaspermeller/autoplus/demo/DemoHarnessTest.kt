package com.jaspermeller.autoplus.demo

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.jaspermeller.autoplus.signal.NotificationReader
import com.jaspermeller.autoplus.signal.Phone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The debug build's stand-in messaging app, end to end: the notification it posts is one the
 * board can read and answer, and the answer comes back to it through its own reply action.
 * This is the same path the adb commands in the README exercise on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DemoHarnessTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val manager = context.getSystemService(NotificationManager::class.java)

  private fun demoMessage(from: String, text: String) {
    shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    DemoReceiver().onReceive(context, Intent(DemoReceiver.MESSAGE).putExtra("from", from).putExtra("text", text))
  }

  private fun logged(): List<String> = ShadowLog.getLogsForTag(DemoReceiver.TAG).map { it.msg }

  @Test
  fun aReplyFromTheBoardComesBackToTheStandInApp() {
    demoMessage("Maya Okafor", "are you coming")
    val posted = shadowOf(manager).allNotifications.single()
    val reading = NotificationReader.read("demo", "Demo", 0L, posted)!!
    assertEquals("Maya Okafor", reading.conversation.title)
    assertTrue(reading.conversation.canReply)
    assertNotNull(reading.markRead)

    val phone = Phone(context)
    phone.inbox.put(reading)
    assertTrue(phone.reply(reading.conversation, "On my way"))
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(logged().toString(), "replied to=Maya Okafor text=On my way" in logged())
    assertTrue("the stand-in app clears its notification once answered", shadowOf(manager).allNotifications.isEmpty())
  }

  @Test
  fun hearingAMessageMarksItReadInTheStandInApp() {
    demoMessage("Dad", "Call when you get there")
    val reading = NotificationReader.read("demo", "Demo", 0L, shadowOf(manager).allNotifications.single())!!
    val phone = Phone(context)
    phone.inbox.put(reading)

    phone.heard(reading.conversation)
    shadowOf(Looper.getMainLooper()).idle()

    assertTrue(logged().toString(), "read from=Dad" in logged())
  }
}
