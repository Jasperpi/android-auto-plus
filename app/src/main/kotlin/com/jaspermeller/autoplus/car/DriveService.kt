package com.jaspermeller.autoplus.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/** Android Auto binds this when the driver opens Auto++ on the car screen. */
class DriveService : CarAppService() {

  // The library ships this allowlist of real Android Auto hosts for apps to use; lint only
  // objects because the resource is not formally public.
  @SuppressLint("PrivateResource")
  override fun createHostValidator(): HostValidator =
      if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
        // Debug builds also need to answer the Desktop Head Unit and test hosts.
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
      } else {
        HostValidator.Builder(applicationContext)
            .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
            .build()
      }

  override fun onCreateSession(sessionInfo: SessionInfo): Session = DriveSession()
}

class DriveSession : Session() {
  private var screen: BoardScreen? = null

  override fun onCreateScreen(intent: Intent): Screen = BoardScreen(carContext).also { screen = it }

  /** The car switched between its day and night looks. */
  override fun onCarConfigurationChanged(newConfiguration: Configuration) {
    screen?.redraw()
  }
}
