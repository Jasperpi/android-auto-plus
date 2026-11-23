package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.NowPlaying
import com.jaspermeller.autoplus.signal.Signals
import com.jaspermeller.autoplus.voice.Dictation
import com.jaspermeller.autoplus.voice.Heard
import com.jaspermeller.autoplus.voice.Speaker
import com.jaspermeller.autoplus.voice.Timer

/** Which signs are up. */
sealed interface Scene {
  /** Home: what is playing and who has written. */
  data object Gantry : Scene

  /** One conversation, being read out or answered. [talk] is a snapshot: it outlives its notification. */
  data class Thread(val talk: Conversation, val phase: Phase) : Scene
}

sealed interface Phase {
  data object Reading : Phase

  data object Ready : Phase

  data class Listening(val words: String = "", val level: Float = 0f) : Phase

  /** The reply is on the board and sends itself when [ticksLeft] reaches zero. */
  data class Confirm(val words: String, val ticksLeft: Int) : Phase

  data object Sent : Phase

  data class Trouble(val what: String, val canRetry: Boolean) : Phase
}

/**
 * Everything the board does, with no drawing and no Android services in it. Main thread only.
 *
 * Reading aloud is the default and message text never goes on the screen: the driver's eyes
 * stay up, and a conversation that has been heard leaves the board on its own.
 */
class BoardModel(
    private val signals: Signals,
    private val speaker: Speaker,
    private val dictation: Dictation,
    private val timer: Timer,
    val quickReplies: List<String> = QUICK_REPLIES,
) {
  var scene: Scene = Scene.Gantry
    private set

  /** First conversation shown on the gantry when there are more than fit. */
  var firstShown = 0
    private set

  var onChange: () -> Unit = {}

  val ready: Boolean get() = signals.ready
  val conversations: List<Conversation> get() = signals.conversations()
  val nowPlaying: NowPlaying? get() = signals.nowPlaying()

  /**
   * Every transition bumps this. Speech, dictation and timers answer later, and an answer
   * carrying an old turn belongs to a scene the driver has already left.
   */
  private var turn = 0
  private var cancelTimer: () -> Unit = {}

  /** The phone's messages or music changed. */
  fun signalsChanged() {
    if (firstShown >= conversations.size) firstShown = 0
    onChange()
  }

  fun open(talk: Conversation) {
    val mine = leave()
    scene = Scene.Thread(talk, Phase.Reading)
    onChange()
    speaker.say(Readout.of(talk)) { finished ->
      if (mine != turn) return@say
      if (finished) signals.heard(talk.key)
      phase(Phase.Ready)
    }
  }

  /** The host's own button: read whoever wrote most recently. */
  fun readNewest() {
    conversations.firstOrNull()?.let(::open)
  }

  fun hearAgain() {
    (scene as? Scene.Thread)?.let { open(it.talk) }
  }

  fun back() {
    leave()
    scene = Scene.Gantry
    onChange()
  }

  fun reply() {
    val thread = scene as? Scene.Thread ?: return
    if (!thread.talk.canReply) return
    val mine = leave()
    phase(Phase.Listening())
    dictation.listen(
        partial = { words -> if (mine == turn) phase(Phase.Listening(words, levelNow())) },
        level = { level -> if (mine == turn) phase(Phase.Listening(wordsNow(), level)) },
        done = { heard ->
          if (mine == turn) {
            when (heard) {
              is Heard.Words -> confirm(heard.text)
              Heard.Silence -> phase(Phase.Trouble("Did not catch that", canRetry = true))
              is Heard.Blocked -> phase(Phase.Trouble(heard.why, canRetry = false))
            }
          }
        },
    )
  }

  fun quick(text: String) {
    val thread = scene as? Scene.Thread ?: return
    if (!thread.talk.canReply) return
    leave()
    send(thread.talk, text)
  }

  fun sendNow() {
    val thread = scene as? Scene.Thread ?: return
    val confirm = thread.phase as? Phase.Confirm ?: return
    leave()
    send(thread.talk, confirm.words)
  }

  /** Drops the reply in progress and returns to the conversation. */
  fun cancelReply() {
    if (scene !is Scene.Thread) return
    leave()
    phase(Phase.Ready)
  }

  fun nextPage(perPage: Int) {
    val count = conversations.size
    firstShown = if (firstShown + perPage >= count) 0 else firstShown + perPage
    onChange()
  }

  fun previous() = signals.previous()

  fun toggle() = signals.toggle()

  fun next() = signals.next()

  /** The surface is going away (car unplugged, activity stopped): go quiet. */
  fun close() {
    leave()
    scene = Scene.Gantry
  }

  private fun confirm(words: String) {
    val mine = turn
    phase(Phase.Confirm(words, COUNTDOWN))
    fun tick() {
      cancelTimer = timer.after(TICK_MS) {
        if (mine != turn) return@after
        val thread = scene as? Scene.Thread ?: return@after
        val left = (thread.phase as? Phase.Confirm)?.ticksLeft ?: return@after
        if (left <= 1) {
          leave()
          send(thread.talk, words)
        } else {
          phase(Phase.Confirm(words, left - 1))
          tick()
        }
      }
    }
    tick()
  }

  private fun send(talk: Conversation, text: String) {
    val mine = turn
    if (!signals.reply(talk.key, text)) {
      phase(Phase.Trouble("Could not send. Reply from your phone when you stop", canRetry = false))
      return
    }
    phase(Phase.Sent)
    cancelTimer = timer.after(SENT_MS) { if (mine == turn) back() }
  }

  /** Stops whatever the current scene had running and starts a new turn. */
  private fun leave(): Int {
    turn++
    cancelTimer()
    cancelTimer = {}
    speaker.stop()
    dictation.cancel()
    return turn
  }

  private fun phase(next: Phase) {
    val thread = scene as? Scene.Thread ?: return
    scene = thread.copy(phase = next)
    onChange()
  }

  private fun wordsNow(): String = ((scene as? Scene.Thread)?.phase as? Phase.Listening)?.words.orEmpty()

  private fun levelNow(): Float = ((scene as? Scene.Thread)?.phase as? Phase.Listening)?.level ?: 0f

  companion object {
    /** One second per pixel on the countdown. */
    const val COUNTDOWN = 4
    const val TICK_MS = 1000L
    const val SENT_MS = 1400L

    val QUICK_REPLIES = listOf("On my way", "Driving, talk soon", "Yes", "No")
  }
}
