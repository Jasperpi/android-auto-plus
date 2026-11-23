package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.signal.Conversation

/** The sentence the car speaks for a conversation. Kept short: a driver cannot skim audio. */
object Readout {
  private const val NEWEST = 5
  private const val LONGEST = 400

  fun of(talk: Conversation): String {
    val recent = talk.messages.takeLast(NEWEST)
    val spoken = StringBuilder()
    if (talk.isGroup) {
      spoken.append("In ").append(talk.title).append(". ")
      var last: String? = null
      for (message in recent) {
        // Name the sender only when it changes, the way a person relays a group chat.
        if (message.sender != last && message.sender.isNotBlank()) spoken.append(message.sender).append(" says, ")
        spoken.append(sentence(message.text)).append(' ')
        last = message.sender
      }
    } else {
      if (recent.size == 1) {
        spoken.append(talk.title).append(" says, ")
      } else {
        spoken.append(recent.size).append(" messages from ").append(talk.title).append(". ")
      }
      for (message in recent) spoken.append(sentence(message.text)).append(' ')
    }
    return spoken.toString().trim()
  }

  private fun sentence(text: String): String {
    val flat = text.trim().replace(Regex("\\s+"), " ")
    val clipped = if (flat.length > LONGEST) flat.take(LONGEST).trimEnd() + ", and more" else flat
    return if (clipped.isNotEmpty() && clipped.last() in ".!?") clipped else "$clipped."
  }
}
