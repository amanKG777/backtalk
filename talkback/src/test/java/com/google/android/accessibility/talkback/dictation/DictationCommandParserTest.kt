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
import org.junit.Test

class DictationCommandParserTest {

  @Test
  fun parse_deleteLastSentence() {
    assertEquals(DictationCommand.DeleteLastSentence, DictationCommandParser.parse("delete last sentence"))
  }

  @Test
  fun parse_moveWithDigitsAndWords() {
    assertEquals(DictationCommand.Move(-2), DictationCommandParser.parse("back 2 words"))
    assertEquals(DictationCommand.Move(-2), DictationCommandParser.parse("back two words"))
    assertEquals(DictationCommand.Move(1), DictationCommandParser.parse("forward one word"))
  }

  @Test
  fun parse_insertAround() {
    assertEquals(
      DictationCommand.InsertAround("hello", "dear", false),
      DictationCommandParser.parse("insert hello after dear"))
    assertEquals(
      DictationCommand.InsertAround("hello", "sam", true),
      DictationCommandParser.parse("insert hello before sam"))
  }

  @Test
  fun parse_replaceVariants() {
    assertEquals(
      DictationCommand.Replace("cat", "dog", true),
      DictationCommandParser.parse("replace all cat with dog"))
    assertEquals(
      DictationCommand.Replace("cat", "dog", false),
      DictationCommandParser.parse("change cat to dog"))
  }

  @Test
  fun parse_deleteAndCapitalizePhrase() {
    assertEquals(DictationCommand.DeletePhrase("good morning"), DictationCommandParser.parse("delete good morning"))
    assertEquals(DictationCommand.CapitalizeWord("monday"), DictationCommandParser.parse("capitalize monday"))
  }

  @Test
  fun parse_caseAndFormatCommands() {
    assertEquals(DictationCommand.CapitalizeThat, DictationCommandParser.parse("capitalize that"))
    assertEquals(DictationCommand.UpperCaseThat, DictationCommandParser.parse("all caps"))
    assertEquals(DictationCommand.LowerCaseThat, DictationCommandParser.parse("lowercase that"))
    assertEquals(DictationCommand.BulletList, DictationCommandParser.parse("bullet list"))
    assertEquals(DictationCommand.FixPunctuation, DictationCommandParser.parse("fix punctuation"))
  }

  @Test
  fun parse_autoPunctuationToggle() {
    assertEquals(DictationCommand.AutoPunctuation(true), DictationCommandParser.parse("auto punctuation on"))
    assertEquals(DictationCommand.AutoPunctuation(false), DictationCommandParser.parse("auto punctuation off"))
  }

  @Test
  fun parse_cursorJumps() {
    assertEquals(DictationCommand.GoToStart, DictationCommandParser.parse("go to start"))
    assertEquals(DictationCommand.GoToEnd, DictationCommandParser.parse("go to end"))
  }

  @Test
  fun parse_clearIsACommand() {
    assertEquals(DictationCommand.Clear, DictationCommandParser.parse("clear"))
    assertEquals(DictationCommand.Clear, DictationCommandParser.parse("delete everything"))
  }

  @Test
  fun parse_harmlessWordsNeedCommandPrefix() {
    assertNull(DictationCommandParser.parse("return"))
    assertNull(DictationCommandParser.parse("enter"))
    assertEquals(DictationCommand.NewLine, DictationCommandParser.parse("command return"))
    assertEquals(DictationCommand.NewLine, DictationCommandParser.parse("new line"))
  }

  @Test
  fun parse_sentenceContainingCommandWordIsText() {
    assertNull(DictationCommandParser.parse("please clear the table"))
    assertNull(DictationCommandParser.parse("i will read the book tomorrow"))
  }

  @Test
  fun parse_stopAndUndo() {
    assertEquals(DictationCommand.Stop, DictationCommandParser.parse("stop"))
    assertEquals(DictationCommand.Undo, DictationCommandParser.parse("undo that"))
  }

  @Test
  fun appliesTo_phraseCommandsNeedTheirPhraseInTheText() {
    val text = "Please send the report tomorrow"
    assertEquals(false, DictationCommandParser.appliesTo(DictationCommand.DeletePhrase("the file"), text))
    assertEquals(true, DictationCommandParser.appliesTo(DictationCommand.DeletePhrase("the report"), text))
    assertEquals(false, DictationCommandParser.appliesTo(DictationCommand.Replace("plan", "idea", false), text))
    assertEquals(true, DictationCommandParser.appliesTo(DictationCommand.Replace("report", "memo", false), text))
    assertEquals(false, DictationCommandParser.appliesTo(DictationCommand.InsertAround("x", "nope", true), text))
    assertEquals(false, DictationCommandParser.appliesTo(DictationCommand.CapitalizeWord("monday"), text))
    assertEquals(true, DictationCommandParser.appliesTo(DictationCommand.Undo, text))
  }
}
