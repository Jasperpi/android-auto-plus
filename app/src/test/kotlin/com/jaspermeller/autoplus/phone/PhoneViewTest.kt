package com.jaspermeller.autoplus.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.jaspermeller.autoplus.app
import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.Message
import com.jaspermeller.autoplus.signal.PhoneListener
import com.jaspermeller.autoplus.signal.Reading
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The phone screen at a Pixel 10a's size, saved to app/build/renders alongside the car board. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h923dp-420dpi")
class PhoneViewTest {
  private val context = ApplicationProvider.getApplicationContext<Context>()
  private val opened = ArrayList<Step.Kind>()
  private var listening = false
  private var mic = false

  private fun steps() = listOf(
      Step(Step.Kind.NOTIFICATIONS, "Notification access", "So the board can see new messages and what is playing.", listening),
      Step(Step.Kind.MICROPHONE, "Microphone", "So you can speak a reply.", mic),
      Step(Step.Kind.ANDROID_AUTO, "Android Auto", "In its settings, tap Version ten times, then turn on Unknown sources under Developer settings.", null),
  )

  private fun show(name: String): PhoneView {
    val view = PhoneView(context, ::steps) { opened += it }
    view.start()
    view.measure(View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY))
    view.layout(0, 0, WIDTH, HEIGHT)
    val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    view.draw(Canvas(bitmap))
    val dir = File(System.getProperty("autoplus.renderDir") ?: "build/renders").apply { mkdirs() }
    File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    return view
  }

  private fun press(view: PhoneView, kind: Step.Kind) {
    val box = view.boxOf(kind) ?: throw AssertionError("$kind has no sign on the screen")
    val at = SystemClock.uptimeMillis()
    for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
      val event = MotionEvent.obtain(at, at, action, box.centerX(), box.centerY(), 0)
      view.dispatchTouchEvent(event)
      event.recycle()
    }
  }

  @Test
  fun beforeSetupTheListLeadsAndEachSignOpensItsSetting() {
    val view = show("phone-setup")

    Step.Kind.entries.forEach { press(view, it) }

    assertEquals(listOf(Step.Kind.NOTIFICATIONS, Step.Kind.MICROPHONE, Step.Kind.ANDROID_AUTO), opened)
    // Until the board has something to show, the list gets most of the screen.
    assertTrue(view.boxOf(Step.Kind.NOTIFICATIONS)!!.top < HEIGHT * 0.4f)
  }

  @Test
  fun onceSetUpTheBoardLeads() {
    listening = true
    mic = true
    val phone = context.app.phone
    phone.listenerConnected(Robolectric.buildService(PhoneListener::class.java).get())
    phone.inbox.put(Reading(
        Conversation("a", "Maya Okafor", "Messages", false, listOf(Message("Maya Okafor", "are you coming", 100L)), canReply = true),
        reply = null,
        markRead = null,
    ))

    val view = show("phone-ready")

    press(view, Step.Kind.ANDROID_AUTO)
    assertEquals(listOf(Step.Kind.ANDROID_AUTO), opened)
    // Now the board leads and the list sits under it.
    assertTrue(view.boxOf(Step.Kind.NOTIFICATIONS)!!.top > HEIGHT * 0.6f)
    // A finished step is a short sign; the open one keeps the room for its explanation.
    assertTrue(view.boxOf(Step.Kind.ANDROID_AUTO)!!.height() > view.boxOf(Step.Kind.MICROPHONE)!!.height() * 1.5f)
  }

  private companion object {
    const val WIDTH = 1080
    const val HEIGHT = 2424
  }
}
