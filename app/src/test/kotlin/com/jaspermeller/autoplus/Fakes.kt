package com.jaspermeller.autoplus

import com.jaspermeller.autoplus.signal.Conversation
import com.jaspermeller.autoplus.signal.Message
import com.jaspermeller.autoplus.signal.NowPlaying
import com.jaspermeller.autoplus.signal.Signals
import com.jaspermeller.autoplus.voice.Dictation
import com.jaspermeller.autoplus.voice.Heard
import com.jaspermeller.autoplus.voice.Speaker
import com.jaspermeller.autoplus.voice.Timer

class FakeSignals : Signals {
  override var ready = true
  var talks = listOf<Conversation>()
  var track: NowPlaying? = null
  var accepts = true
  val sent = ArrayList<Pair<String, String>>()
  val heardKeys = ArrayList<String>()
  val presses = ArrayList<String>()

  override fun conversations() = talks

  override fun nowPlaying() = track

  override fun previous() {
    presses += "previous"
  }

  override fun toggle() {
    presses += "toggle"
  }

  override fun next() {
    presses += "next"
  }

  override fun reply(key: String, text: String): Boolean {
    if (!accepts) return false
    sent += key to text
    talks = talks.filter { it.key != key }
    return true
  }

  override fun heard(key: String) {
    heardKeys += key
    talks = talks.filter { it.key != key }
  }
}

class FakeSpeaker : Speaker {
  val said = ArrayList<String>()
  var stops = 0
  private var done: ((Boolean) -> Unit)? = null

  override fun say(text: String, done: (finished: Boolean) -> Unit) {
    said += text
    this.done = done
  }

  /** Mirrors the real speaker: cutting speech off reports an unfinished readout. */
  override fun stop() {
    stops++
    finish(false)
  }

  fun finish(finished: Boolean = true) {
    val report = done
    done = null
    report?.invoke(finished)
  }
}

class FakeDictation : Dictation {
  var listening = false
  var cancels = 0
  private var partial: ((String) -> Unit)? = null
  private var level: ((Float) -> Unit)? = null
  private var done: ((Heard) -> Unit)? = null

  override fun listen(partial: (String) -> Unit, level: (Float) -> Unit, done: (Heard) -> Unit) {
    this.partial = partial
    this.level = level
    this.done = done
    listening = true
  }

  override fun cancel() {
    cancels++
    listening = false
    done = null
  }

  fun hearing(words: String, loudness: Float = 0.6f) {
    partial?.invoke(words)
    level?.invoke(loudness)
  }

  fun hear(heard: Heard) {
    val report = done
    done = null
    listening = false
    report?.invoke(heard)
  }
}

class FakeTimer : Timer {
  private class Entry(val at: Long, val run: () -> Unit)

  private val waiting = ArrayList<Entry>()
  private var now = 0L

  override fun after(ms: Long, run: () -> Unit): () -> Unit {
    val entry = Entry(now + ms, run)
    waiting += entry
    return { waiting -= entry }
  }

  fun advance(ms: Long) {
    val end = now + ms
    while (true) {
      val next = waiting.filter { it.at <= end }.minByOrNull { it.at } ?: break
      waiting -= next
      now = next.at
      next.run()
    }
    now = end
  }
}

fun talk(
    key: String,
    title: String,
    vararg texts: String,
    app: String = "Messages",
    at: Long = 0L,
    group: Boolean = false,
    canReply: Boolean = true,
    senders: List<String> = emptyList(),
) = Conversation(
    key = key,
    title = title,
    app = app,
    isGroup = group,
    messages = texts.mapIndexed { i, text -> Message(senders.getOrElse(i) { title }, text, at + i) },
    canReply = canReply,
)
