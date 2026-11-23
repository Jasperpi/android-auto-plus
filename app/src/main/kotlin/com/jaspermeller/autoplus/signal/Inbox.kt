package com.jaspermeller.autoplus.signal

/** The unread conversations the phone is showing right now. Main thread only. */
class Inbox(private val changed: () -> Unit = {}) {
  private val live = LinkedHashMap<String, Reading>()

  /**
   * Readings whose notification has gone. Marking a conversation as heard removes its
   * notification, but the driver may still reply a moment later, so the reply route is kept.
   */
  private val departed = LinkedHashMap<String, Reading>()

  val conversations: List<Conversation>
    get() = live.values.map { it.conversation }.sortedByDescending { it.latestAt }

  fun put(reading: Reading) {
    departed.remove(reading.conversation.key)
    live[reading.conversation.key] = reading
    changed()
  }

  fun remove(key: String) {
    val gone = live.remove(key) ?: return
    departed[key] = gone
    while (departed.size > KEPT) departed.remove(departed.keys.first())
    changed()
  }

  fun clear() {
    departed.clear()
    if (live.isEmpty()) return
    live.clear()
    changed()
  }

  /** The reading for [key], whether its notification is still showing or just left. */
  fun find(key: String): Reading? = live[key] ?: departed[key]

  /** The reading for [key] only while its notification is still showing. */
  fun showing(key: String): Reading? = live[key]

  private companion object {
    const val KEPT = 8
  }
}
