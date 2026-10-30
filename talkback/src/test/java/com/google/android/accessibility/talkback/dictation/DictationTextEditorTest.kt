/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.talkback.dictation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTextEditorTest {

  private val text = "Hi there. How are you? Fine"

  @Test
  fun deleteLastSentence_removesTrailingFragment() {
    val r = DictationTextEditor.deleteLastSentence(text, text.length)
    assertEquals("Hi there. How are you? ", r.text)
    assertEquals(r.text.length, r.cursor)
  }

  @Test
  fun deleteLastSentence_cursorAfterFirstSentence() {
    val r = DictationTextEditor.deleteLastSentence(text, 9)
    assertEquals(" How are you? Fine", r.text)
    assertEquals(0, r.cursor)
  }

  @Test
  fun deleteLastSentence_emptyIsNothingToDo() {
    val r = DictationTextEditor.deleteLastSentence("", 0)
    assertEquals(EditFeedback.NothingToDo, r.feedback)
    assertEquals("", r.text)
  }

  @Test
  fun deletePhrase_removesLastMatchAndTidiesSpaces() {
    val r = DictationTextEditor.deletePhrase("one two three", 13, "two")
    assertEquals("one three", r.text)
    assertEquals(4, r.cursor)
  }

  @Test
  fun deletePhrase_absentIsNotFound() {
    val r = DictationTextEditor.deletePhrase("one two", 7, "zebra")
    assertEquals(EditFeedback.NotFound("zebra"), r.feedback)
    assertEquals("one two", r.text)
  }

  @Test
  fun insertAround_beforeAndAfter() {
    val b = DictationTextEditor.insertAround("dear sam", 8, "hello", "sam", true)
    assertEquals("dear hello sam", b.text)
    val a = DictationTextEditor.insertAround("dear sam", 8, "good", "dear", false)
    assertEquals("dear good sam", a.text)
    assertEquals(9, a.cursor)
  }

  @Test
  fun insertAround_missingPhraseNotFound() {
    val r = DictationTextEditor.insertAround("dear sam", 8, "hello", "bob", true)
    assertEquals(EditFeedback.NotFound("bob"), r.feedback)
  }

  @Test
  fun moveWords_forwardBackAndClamp() {
    val t = "one two three"
    assertEquals(8, DictationTextEditor.moveWords(t, 13, -1).cursor)
    assertEquals(0, DictationTextEditor.moveWords(t, 4, -5).cursor)
    assertEquals(13, DictationTextEditor.moveWords(t, 4, 9).cursor)
    assertEquals(3, DictationTextEditor.moveWords(t, 0, 1).cursor)
  }

  @Test
  fun moveWords_emojiDoesNotSplitSurrogates() {
    val t = "hi 😀 there"
    val r = DictationTextEditor.moveWords(t, t.length, -1)
    assertEquals(6, r.cursor)
    assertTrue(!Character.isLowSurrogate(t[r.cursor]))
  }

  @Test
  fun goToStartAndEnd() {
    assertEquals(0, DictationTextEditor.goToStart("abc", 2).cursor)
    assertEquals(3, DictationTextEditor.goToEnd("abc", 1).cursor)
  }

  @Test
  fun caseCommands_actOnLastWordBeforeCursor() {
    assertEquals("see you Monday", DictationTextEditor.capitalizeThat("see you monday", 14).text)
    assertEquals("see you MONDAY", DictationTextEditor.upperCaseThat("see you monday", 14).text)
    assertEquals("see you monday", DictationTextEditor.lowerCaseThat("see you MONDAY", 14).text)
  }

  @Test
  fun caseCommands_midText() {
    val r = DictationTextEditor.upperCaseThat("see you monday ok", 14)
    assertEquals("see you MONDAY ok", r.text)
    assertEquals(14, r.cursor)
  }

  @Test
  fun caseCommands_emptyIsNothingToDo() {
    assertEquals(EditFeedback.NothingToDo, DictationTextEditor.capitalizeThat("", 0).feedback)
  }

  @Test
  fun capitalizeWord_findsLastOccurrence() {
    assertEquals("mon Monday", DictationTextEditor.capitalizeWord("mon monday", 10, "monday").text)
    assertEquals(EditFeedback.NotFound("zzz"), DictationTextEditor.capitalizeWord("a b", 3, "zzz").feedback)
  }

  @Test
  fun bullets_startAndContinueAndEnd() {
    val s = DictationTextEditor.startBullet("", 0)
    assertEquals("• ", s.text)
    val s2 = DictationTextEditor.startBullet("Shopping", 8)
    assertEquals("Shopping\n• ", s2.text)

    val c = DictationTextEditor.continueBullet("• milk", 6)
    assertEquals("• milk\n• ", c.text)

    val e = DictationTextEditor.continueBullet("• milk\n• ", 9)
    assertEquals("• milk\n", e.text)
    assertEquals(EditFeedback.BulletEnded, e.feedback)
  }

  @Test
  fun readHelpers() {
    assertEquals("Fine", DictationTextEditor.lastSentence(text, text.length))
    assertEquals("How are you?", DictationTextEditor.lastSentence(text, 22))
    assertEquals("Fine", DictationTextEditor.lastWord(text, text.length))
    assertNull(DictationTextEditor.lastSentence("", 0))
    assertNull(DictationTextEditor.lastWord("   ", 3))
  }

  @Test
  fun outOfRangeCursorIsClamped() {
    val r = DictationTextEditor.deleteLastSentence("Hello", 99)
    assertEquals("", r.text)
  }
}
