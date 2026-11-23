package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.NowPlaying
import com.jaspermeller.autoplus.signal.Signals
import com.jaspermeller.autoplus.voice.Dictation
import com.jaspermeller.autoplus.voice.Heard
import com.jaspermeller.autoplus.voice.Speaker
import com.jaspermeller.autoplus.voice.Spoken
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
   * True while the open thread has a new message that must be heard before any reply. The
   * board shows that in place of the reply signs, which would otherwise look live and do nothing.
   */
  val replyHeld: Boolean get() = mustHear != null && (scene as? Scene.Thread)?.talk?.key == mustHear

  /**
   * Every transition bumps this. Speech, dictation and timers answer later, and an answer
   * carrying an old turn belongs to a scene the driver has already left.
   */
  private var turn = 0
  private var cancelTimer: () -> Unit = {}

  /**
   * True while a readout this board started is still going. The speaker is shared with the
   * other board (car and phone), and a board must only ever cut off its own speech.
   */
  private var speaking = false

  /**
   * The thread whose new message has to be heard out before any reply goes to it. Set when a
   * reply was held back because something new had arrived; a second tap on the same reply
   * must not cut that message off and answer it unheard.
   */
  private var mustHear: String? = null

  /** The phone's messages or music changed. */
  fun signalsChanged() {
    if (firstShown >= conversations.size) firstShown = 0
    onChange()
  }

  fun open(talk: Conversation) = read(talk, lead = "")

  /** Reads [talk] aloud, after [lead] when there is something the driver must be told first. */
  private fun read(talk: Conversation, lead: String) {
    val mine = leave()
    if (lead.isNotEmpty()) mustHear = talk.key
    scene = Scene.Thread(talk, Phase.Reading)
    onChange()
    val readout = Readout.of(talk)
    speaking = true
    speaker.say(lead + readout.words) { how ->
      speaking = false
      if (mine != turn) return@say
      when (how) {
        Spoken.FINISHED -> {
          if (mustHear == talk.key) mustHear = null
          // Only a thread spoken in full counts as heard. One that was cut down to its last
          // few messages stays unread on the phone.
          if (readout.complete) signals.heard(talk)
          phase(Phase.Ready)
        }
        Spoken.INTERRUPTED -> phase(Phase.Ready)
        // After a held-back reply, speech was the only notice that it did not go; when the
        // speech itself fails, the board has to carry it.
        Spoken.FAILED -> phase(Phase.Trouble(
            if (lead.isEmpty()) "Could not read this aloud" else "Reply not sent. Could not read the new message",
            canRetry = false))
      }
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
    mustHear = null
    scene = Scene.Gantry
    onChange()
  }

  fun reply() {
    val thread = scene as? Scene.Thread ?: return
    if (!thread.talk.canReply) return
    // A second tap landing on the old signs must not restart listening or resend.
    if (thread.phase is Phase.Listening || thread.phase == Phase.Sent) return
    if (mustHear == thread.talk.key) return
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
    // Ready-made lines are only on the board while reading or just after; a tap that arrives
    // later is a double tap on signs that have already gone, and must not send again.
    if (thread.phase != Phase.Reading && thread.phase != Phase.Ready) return
    if (mustHear == thread.talk.key) return
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
    val thread = scene as? Scene.Thread ?: return
    // Only from the phases that show a way out. A tap on a Cancel that has not been repainted
    // yet must not turn "Sent" back into choices, nor cut off a readout that has just begun.
    if (thread.phase !is Phase.Listening && thread.phase !is Phase.Confirm && thread.phase !is Phase.Trouble) return
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

  /**
   * The board is no longer in front of the driver (another app took the car screen). A reply
   * must never send unseen and the microphone must not stay open, so a reply in progress is
   * dropped. A readout carries on: it needs no screen.
   */
  fun pause() {
    val phase = (scene as? Scene.Thread)?.phase ?: return
    if (phase !is Phase.Listening && phase !is Phase.Confirm) return
    cancelReply()
    // A driver who left during "Sending in 3" trusts the countdown. The words were complete and
    // did not go, so the car says so; the board they would have read it on is not there.
    if (phase is Phase.Confirm) {
      val mine = turn
      speaking = true
      speaker.say(NOT_SENT.trim()) { how ->
        speaking = false
        // What usually takes the screen mid-countdown is a call, and a call also takes the
        // sound. If the notice could not be spoken, the board carries it for when they return.
        // Not after a tap or an unplug: that is the driver moving on, and a new turn.
        if (how != Spoken.FINISHED && mine == turn) phase(Phase.Trouble("Your reply was not sent", canRetry = false))
      }
    }
  }

  /** The surface is gone for good (car unplugged, phone screen closed): go quiet. */
  fun close() {
    leave()
    mustHear = null
    scene = Scene.Gantry
    firstShown = 0
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
    // Something arrived while the reply was being chosen or spoken ("actually, don't come").
    // Sending now would answer a message the driver has not heard, and the phone would then
    // count it as dealt with. So the reply is held back and the thread is read again, new
    // message included. It also covers an app that reuses one notification for another chat.
    val live = signals.conversations().firstOrNull { it.key == talk.key }
    if (live != null && !live.sameWords(talk)) {
      // Said first, or the readout that follows would sound like an answer to a reply that never went.
      read(live, lead = NOT_SENT)
      return
    }
    val mine = turn
    if (!signals.reply(talk, text)) {
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
    if (speaking) {
      speaking = false
      speaker.stop()
    }
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
    const val NOT_SENT = "Your reply was not sent. "

    val QUICK_REPLIES = listOf("On my way", "Driving, talk soon", "Yes", "No")
  }
}
