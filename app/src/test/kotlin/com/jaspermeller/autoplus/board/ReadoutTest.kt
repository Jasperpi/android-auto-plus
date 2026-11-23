package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.talk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadoutTest {
  @Test
  fun oneMessageNamesTheSender() {
    assertEquals("Dad says, Call when you get there.", Readout.of(talk("a", "Dad", "Call when you get there")))
  }

  @Test
  fun severalMessagesAreCountedFirst() {
    assertEquals(
        "2 messages from Maya. are you coming? we saved you a seat.",
        Readout.of(talk("a", "Maya", "are you coming?", "we saved you a seat")))
  }

  @Test
  fun aGroupNamesEachSenderOnceUntilItChanges() {
    val group = talk(
        "g", "Mock trial captains", "Scrimmage moved to 4", "Room 12", "Got it",
        group = true, senders = listOf("Dev", "Dev", "Ana"))
    assertEquals(
        "In Mock trial captains. Dev says, Scrimmage moved to 4. Room 12. Ana says, Got it.",
        Readout.of(group))
  }

  @Test
  fun onlyTheNewestFiveAreRead() {
    val many = talk("a", "Maya", "one", "two", "three", "four", "five", "six", "seven")
    val spoken = Readout.of(many)
    assertTrue(spoken.startsWith("5 messages from Maya."))
    assertTrue("three." in spoken && "seven." in spoken)
    assertTrue("two." !in spoken)
  }

  @Test
  fun aVeryLongMessageIsCutShort() {
    val spoken = Readout.of(talk("a", "Maya", "word ".repeat(200)))
    assertTrue(spoken.endsWith(", and more."))
    assertTrue(spoken.length < 440)
  }

  @Test
  fun lineBreaksAreFlattened() {
    assertEquals("Maya says, see you soon.", Readout.of(talk("a", "Maya", "see\nyou\n\nsoon")))
  }
}
