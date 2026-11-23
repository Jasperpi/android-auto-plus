package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.signal.Conversation

/**
 * The sentence the car speaks for a conversation. Kept short: a driver cannot skim audio.
 * [complete] is false when something was left out (older messages, the tail of a very long
 * one); a thread that was only partly spoken must not be marked read.
 */
class Readout(val words: String, val complete: Boolean) {
  companion object {
    private const val NEWEST = 5
    private const val LONGEST = 400

    fun of(talk: Conversation): Readout {
      val count = talk.messages.size
      val recent = talk.messages.takeLast(NEWEST)
      val skipped = count > recent.size
      var clipped = false
      fun sentence(text: String): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        val short = if (flat.length > LONGEST) {
          clipped = true
          flat.take(LONGEST).trimEnd() + ", and more"
        } else {
          flat
        }
        return if (short.isNotEmpty() && short.last() in ".!?") short else "$short."
      }

      val spoken = StringBuilder()
      if (talk.isGroup) {
        spoken.append("In ").append(talk.title).append(". ")
        if (skipped) spoken.append(count).append(" messages. Here are the last ").append(recent.size).append(". ")
        var last: String? = null
        for (message in recent) {
          // Name the sender only when it changes, the way a person relays a group chat.
          if (message.sender != last && message.sender.isNotBlank()) spoken.append(message.sender).append(" says, ")
          spoken.append(sentence(message.text)).append(' ')
          last = message.sender
        }
      } else {
        when {
          count == 1 -> spoken.append(talk.title).append(" says, ")
          skipped -> spoken.append(count).append(" messages from ").append(talk.title)
              .append(". Here are the last ").append(recent.size).append(". ")
          else -> spoken.append(count).append(" messages from ").append(talk.title).append(". ")
        }
        for (message in recent) spoken.append(sentence(message.text)).append(' ')
      }
      return Readout(spoken.toString().trim(), complete = !skipped && !clipped)
    }
  }
}
