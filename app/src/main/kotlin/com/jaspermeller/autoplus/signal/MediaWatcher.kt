package com.jaspermeller.autoplus.signal

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

/**
 * Follows whichever app is playing (Spotify, a podcast app) through the phone's media sessions.
 * Reading other apps' sessions is allowed only for an enabled notification listener, which is
 * why this takes the listener's component name.
 */
class MediaWatcher(
    private val context: Context,
    private val listener: ComponentName,
    private val changed: () -> Unit,
) {
  private val manager = context.getSystemService(MediaSessionManager::class.java)
  private val handler = Handler(Looper.getMainLooper())
  private val labels = HashMap<String, String>()
  private var watched: List<MediaController> = emptyList()
  private var current: MediaController? = null
  private var started = false

  /**
   * The current session's metadata, fetched when it changes rather than on every tick. Players
   * report their position every second or so, and fetching the metadata each time would hand
   * back a fresh copy of the same sleeve and repaint the whole board for nothing.
   */
  private var metadata: MediaMetadata? = null
  private var metadataOf: MediaSession.Token? = null

  var nowPlaying: NowPlaying? = null
    private set

  private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { adopt(it.orEmpty()) }

  private val callback = object : MediaController.Callback() {
    override fun onPlaybackStateChanged(state: PlaybackState?) = refresh(metadataChanged = false)

    override fun onMetadataChanged(metadata: MediaMetadata?) = refresh(metadataChanged = true)

    // Ask again rather than wait for the system's list to catch up, so the sign never stays
    // pinned to a player that has gone.
    override fun onSessionDestroyed() = reload()
  }

  fun start() {
    if (started) return
    try {
      manager.addOnActiveSessionsChangedListener(sessionsChanged, listener, handler)
      started = true
      adopt(manager.getActiveSessions(listener))
    } catch (denied: SecurityException) {
      // Notification access was switched off between the bind and this call.
      manager.removeOnActiveSessionsChangedListener(sessionsChanged)
      started = false
    }
  }

  fun stop() {
    if (!started) return
    started = false
    manager.removeOnActiveSessionsChangedListener(sessionsChanged)
    adopt(emptyList())
  }

  fun previous() {
    current?.transportControls?.skipToPrevious()
  }

  fun next() {
    current?.transportControls?.skipToNext()
  }

  fun toggle() {
    val controller = current ?: return
    if (isPlaying(controller.playbackState)) {
      controller.transportControls.pause()
    } else {
      controller.transportControls.play()
    }
  }

  private fun reload() {
    if (!started) return
    try {
      adopt(manager.getActiveSessions(listener))
    } catch (denied: SecurityException) {
      adopt(emptyList())
    }
  }

  private fun adopt(controllers: List<MediaController>) {
    watched.forEach { it.unregisterCallback(callback) }
    watched = controllers
    watched.forEach { it.registerCallback(callback, handler) }
    refresh(metadataChanged = true)
  }

  private fun refresh(metadataChanged: Boolean) {
    // Whoever is playing wins. Otherwise stay with the session already shown, so pausing
    // Spotify does not hand the sign to some other app that happens to hold a session.
    current = watched.firstOrNull { isPlaying(it.playbackState) }
        ?: watched.firstOrNull { it.sessionToken == current?.sessionToken }
        ?: watched.firstOrNull { it.metadata != null }
    val controller = current
    if (controller == null) {
      metadata = null
      metadataOf = null
    } else if (metadataChanged || controller.sessionToken != metadataOf) {
      metadata = controller.metadata
      metadataOf = controller.sessionToken
    }
    val next = controller?.let { nowPlayingOf(metadata, it.playbackState, labelOf(it.packageName)) }
    if (next != nowPlaying) {
      nowPlaying = next
      changed()
    }
  }

  private fun labelOf(packageName: String): String = labels.getOrPut(packageName) { appLabel(context, packageName) }

  companion object {
    fun nowPlayingOf(metadata: MediaMetadata?, state: PlaybackState?, source: String): NowPlaying? {
      val title = metadata?.text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaMetadata.METADATA_KEY_TITLE)
      if (title.isNullOrBlank()) return null
      val actions = state?.actions ?: 0L
      return NowPlaying(
          title = title,
          artist = metadata.text(
              MediaMetadata.METADATA_KEY_ARTIST,
              MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
              MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
          ).orEmpty(),
          source = source,
          playing = isPlaying(state),
          canPrevious = actions and PlaybackState.ACTION_SKIP_TO_PREVIOUS != 0L,
          canNext = actions and PlaybackState.ACTION_SKIP_TO_NEXT != 0L,
          art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
              ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
              ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
      )
    }

    /** Buffering is a track on its way, so the control shown should still be pause. */
    private fun isPlaying(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING || state?.state == PlaybackState.STATE_BUFFERING

    private fun MediaMetadata.text(vararg keys: String): String? =
        keys.firstNotNullOfOrNull { key -> getText(key)?.toString()?.takeIf { it.isNotBlank() } }
  }
}

/** The name a person knows an app by ("Spotify"), or nothing when the system will not say. */
fun appLabel(context: Context, packageName: String): String =
    try {
      val pm = context.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (hidden: Exception) {
      ""
    }
