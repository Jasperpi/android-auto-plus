package com.jaspermeller.autoplus.phone

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import com.jaspermeller.autoplus.signal.PhoneListener

/**
 * The phone side: a live copy of the board, and the three things the driver has to switch on
 * once before the car screen can work.
 */
class MainActivity : Activity() {
  private lateinit var view: PhoneView

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    view = PhoneView(this, ::steps, ::open)
    setContentView(view)
  }

  override fun onResume() {
    super.onResume()
    view.refresh()
  }

  override fun onStart() {
    super.onStart()
    view.start()
  }

  override fun onStop() {
    view.stop()
    super.onStop()
  }

  override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
    view.refresh()
    // Refused twice, Android stops showing the dialog, so the only way left is the App info page.
    val refused = grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED
    if (refused && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) launch(appInfo(packageName))
  }

  private fun steps(): List<Step> {
    val listening = packageName in NotificationManagerCompat.getEnabledListenerPackages(this)
    val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    return listOf(
        Step(Step.Kind.NOTIFICATIONS, "Notification access", "So the board can see new messages and what is playing.", listening),
        Step(Step.Kind.MICROPHONE, "Microphone", "So you can speak a reply.", mic),
        Step(
            Step.Kind.ANDROID_AUTO,
            "Android Auto",
            "In its settings, tap Version ten times, then turn on Unknown sources under Developer settings.",
            done = null,
        ),
    )
  }

  private fun open(kind: Step.Kind) {
    when (kind) {
      Step.Kind.NOTIFICATIONS -> launch(
          Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
              Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
              ComponentName(this, PhoneListener::class.java).flattenToString()),
          Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
      )
      Step.Kind.MICROPHONE ->
          if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
          } else {
            launch(appInfo(packageName))
          }
      // Android Auto has no launcher icon; its App info page links to its settings.
      Step.Kind.ANDROID_AUTO -> launch(appInfo(ANDROID_AUTO))
    }
  }

  private fun appInfo(target: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$target"))

  /** Starts the first of [choices] this phone can open. */
  private fun launch(vararg choices: Intent) {
    for (choice in choices) {
      try {
        startActivity(choice)
        return
      } catch (missing: ActivityNotFoundException) {
        continue
      }
    }
  }

  private companion object {
    const val ANDROID_AUTO = "com.google.android.projection.gearhead"
  }
}

/** One line of the setup list. [done] is null when the phone cannot tell. */
class Step(val kind: Kind, val title: String, val why: String, val done: Boolean?) {
  enum class Kind { NOTIFICATIONS, MICROPHONE, ANDROID_AUTO }
}
