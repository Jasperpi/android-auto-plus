package com.jaspermeller.autoplus.signal

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jaspermeller.autoplus.app

/**
 * The system binds this once the driver grants notification access. It is the only way an
 * ordinary app can see incoming messages and the playing track, so the whole board hangs off it.
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
    app.phone.listenerDisconnected()
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
