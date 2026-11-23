package com.jaspermeller.autoplus.board

import com.jaspermeller.autoplus.talk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadoutTest {
  @Test
  fun oneMessageNamesTheSender() {
    val readout = Readout.of(talk("a", "Dad", "Call when you get there"))
    assertEquals("Dad says, Call when you get there.", readout.words)
    assertTrue(readout.complete)
  }

  @Test
  fun severalMessagesAreCountedFirst() {
    assertEquals(
        "2 messages from Maya. are you coming? we saved you a seat.",
        Readout.of(talk("a", "Maya", "are you coming?", "we saved you a seat")).words)
  }

  @Test
  fun aGroupNamesEachSenderOnceUntilItChanges() {
    val group = talk(
        "g", "Mock trial captains", "Scrimmage moved to 4", "Room 12", "Got it",
        group = true, senders = listOf("Dev", "Dev", "Ana"))
    assertEquals(
        "In Mock trial captains. Dev says, Scrimmage moved to 4. Room 12. Ana says, Got it.",
        Readout.of(group).words)
  }

  @Test
  fun aLongThreadSaysHowManyThereAreAndAdmitsItSkippedSome() {
    val readout = Readout.of(talk("a", "Maya", "one", "two", "three", "four", "five", "six", "seven"))

    assertTrue(readout.words.startsWith("7 messages from Maya. Here are the last 5. three."))
    assertTrue("seven." in readout.words)
    assertFalse("two." in readout.words)
    assertFalse("older messages were left out", readout.complete)
  }

  @Test
  fun aLongGroupThreadDoesTheSame() {
    val group = talk("g", "ASB officers", "1", "2", "3", "4", "5", "6", group = true, senders = List(6) { "Ana" })
    val readout = Readout.of(group)

    assertTrue(readout.words.startsWith("In ASB officers. 6 messages. Here are the last 5. Ana says, 2."))
    assertFalse(readout.complete)
  }

  @Test
  fun aVeryLongMessageIsCutShortAndCountsAsUnfinished() {
    val readout = Readout.of(talk("a", "Maya", "word ".repeat(200)))

    assertTrue(readout.words.endsWith(", and more."))
    assertTrue(readout.words.length < 440)
    assertFalse(readout.complete)
  }

  @Test
  fun lineBreaksAreFlattened() {
    assertEquals("Maya says, see you soon.", Readout.of(talk("a", "Maya", "see\nyou\n\nsoon")).words)
  }
}
