package com.jaspermeller.autoplus.car

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator

/** Android Auto binds this when the driver opens Auto++ on the car screen. */
class DriveService : CarAppService() {

  /**
   * Only the real Android Auto apps may drive this board, in debug builds too: the debug build
   * is the one on the phone, and a host can press Reply. The Desktop Head Unit still works,
   * because its host is Android Auto itself. The library ships this allowlist for apps to use;
   * lint only objects because the resource is not formally public.
   */
  @SuppressLint("PrivateResource")
  override fun createHostValidator(): HostValidator =
      HostValidator.Builder(applicationContext)
          .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
          .build()

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
