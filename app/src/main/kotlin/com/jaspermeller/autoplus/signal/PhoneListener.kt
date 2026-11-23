package com.jaspermeller.autoplus.signal

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jaspermeller.autoplus.app

/**
 * The system binds this once the driver grants notification access. It is the only way an
 * ordinary app can see incoming messages and the playing track, so the whole board hangs off it.
 * The system delivers every callback here on the main thread.
 */
class PhoneListener : NotificationListenerService() {

  override fun onListenerConnected() {
    val phone = app.phone
    phone.listenerConnected(this)
    try {
      activeNotifications?.forEach(::take)
    } catch (denied: SecurityException) {
      // Access was revoked while binding; onListenerDisconnected follows.
    }
  }

  override fun onListenerDisconnected() {
    app.phone.listenerDisconnected(this)
  }

  /** The system can tear the service down without the disconnect callback. */
  override fun onDestroy() {
    app.phone.listenerDisconnected(this)
    super.onDestroy()
  }

  override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

  override fun onNotificationRemoved(sbn: StatusBarNotification) {
    app.phone.inbox.remove(sbn.key)
  }

  private fun take(sbn: StatusBarNotification) {
    val inbox = app.phone.inbox
    val reading = NotificationReader.read(
        key = sbn.key,
        app = appLabel(this, sbn.packageName),
        postedAt = sbn.postTime,
        notification = sbn.notification,
    )
    if (reading != null) inbox.put(reading) else inbox.remove(sbn.key)
  }
}
