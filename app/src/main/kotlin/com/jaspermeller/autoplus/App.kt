package com.jaspermeller.autoplus

import android.app.Application
import android.content.Context
import com.jaspermeller.autoplus.signal.Phone
import com.jaspermeller.autoplus.voice.TtsSpeaker

class App : Application() {
  lateinit var phone: Phone
    private set

  lateinit var speaker: TtsSpeaker
    private set

  override fun onCreate() {
    super.onCreate()
    phone = Phone(this)
    speaker = TtsSpeaker(this)
  }
}

val Context.app: App get() = applicationContext as App
