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
import org.junit.Test

class DictationPunctuationFormatterTest {

  @Test
  fun format_replacesBasicPunctuationAndCapitalizes() {
    val input = "hello comma how are you question mark"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("Hello, how are you?", result)
  }

  @Test
  fun format_handlesFullStopAndPeriod() {
    val input1 = "this is a sentence period this is another sentence full stop"
    val result1 = DictationPunctuationFormatter.format(input1)
    assertEquals("This is a sentence. This is another sentence.", result1)
  }

  @Test
  fun format_handlesExclamationMarks() {
    val input = "congratulations exclamation mark good job exclamation point"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("Congratulations! Good job!", result)
  }

  @Test
  fun format_handlesNewLinesAndParagraphs() {
    val input = "first line new line second line new paragraph third paragraph"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("First line\nSecond line\n\nThird paragraph", result)
  }

  @Test
  fun format_handlesQuotesAndParentheses() {
    val input = "he said open quote hello close quote open parenthesis laughing close paren"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("He said \"hello\" (laughing)", result)
  }

  @Test
  fun format_handlesColonsAndSemicolons() {
    val input = "note colon buy apples semicolon oranges comma and bananas period"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("Note: buy apples; oranges, and bananas.", result)
  }

  @Test
  fun format_handlesSymbols() {
    val input = "price is dollar sign 100 at the rate 10 percent sign discount hyphen today"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("Price is $100 @ 10% discount - today", result)
  }

  @Test
  fun format_handlesPronounIAndContractions() {
    val input = "i think i'm ready and i'll be there soon because i've finished"
    val result = DictationPunctuationFormatter.format(input)
    assertEquals("I think I'm ready and I'll be there soon because I've finished", result)
  }

  @Test
  fun format_respectsPreviousTextContext_noCapitalizationAfterComma() {
    val previousText = "Hello,"
    val input = "my friend"
    val result = DictationPunctuationFormatter.format(input, previousText)
    assertEquals("my friend", result)
  }

  @Test
  fun format_respectsPreviousTextContext_capitalizesAfterSentenceEnd() {
    val previousText = "Hello."
    val input = "my friend"
    val result = DictationPunctuationFormatter.format(input, previousText)
    assertEquals("My friend", result)
  }

  @Test
  fun format_respectsPreviousTextContext_capitalizesAfterNewline() {
    val previousText = "Line 1\n"
    val input = "line 2"
    val result = DictationPunctuationFormatter.format(input, previousText)
    assertEquals("Line 2", result)
  }

  @Test
  fun getPrefixSpacing_cases() {
    // When previous text ends with character and toInsert starts with word
    assertEquals(" ", DictationPunctuationFormatter.getPrefixSpacing("Hello", "world"))

    // When previous text ends with comma and toInsert starts with word
    assertEquals(" ", DictationPunctuationFormatter.getPrefixSpacing("Hello,", "world"))

    // When toInsert starts with punctuation
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing("Hello", ", world"))
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing("Hello", "."))

    // When previous text already ends with whitespace
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing("Hello ", "world"))
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing("Hello\n", "world"))

    // When previous text is null or empty
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing(null, "world"))
    assertEquals("", DictationPunctuationFormatter.getPrefixSpacing("", "world"))
  }

  @Test
  fun replacePhrase_basicReplacement() {
    val result = DictationPunctuationFormatter.replacePhrase("I have a pear", "pear", "peer")
    org.junit.Assert.assertNotNull(result)
    assertEquals("I have a peer", result!!.newText)
    assertEquals("pear", result.matchedTarget)
    assertEquals("peer", result.finalReplacement)
    assertEquals("I have a peer".length, result.newCursor)
  }

  @Test
  fun replacePhrase_replacesLastOccurrenceByDefault() {
    val result = DictationPunctuationFormatter.replacePhrase("apple and apple", "apple", "orange")
    org.junit.Assert.assertNotNull(result)
    assertEquals("apple and orange", result!!.newText)
  }

  @Test
  fun replacePhrase_replaceAll() {
    val result = DictationPunctuationFormatter.replacePhrase("apple and apple", "apple", "orange", replaceAll = true)
    org.junit.Assert.assertNotNull(result)
    assertEquals("orange and orange", result!!.newText)
  }

  @Test
  fun replacePhrase_preservesCasing() {
    // Capitalized
    val capResult = DictationPunctuationFormatter.replacePhrase("Pear is tasty", "pear", "apple")
    org.junit.Assert.assertNotNull(capResult)
    assertEquals("Apple is tasty", capResult!!.newText)

    // ALL CAPS
    val upperResult = DictationPunctuationFormatter.replacePhrase("BUY PEAR NOW", "pear", "apple")
    org.junit.Assert.assertNotNull(upperResult)
    assertEquals("BUY APPLE NOW", upperResult!!.newText)

    // Lowercase
    val lowerResult = DictationPunctuationFormatter.replacePhrase("pear is tasty", "PEAR", "apple")
    org.junit.Assert.assertNotNull(lowerResult)
    assertEquals("apple is tasty", lowerResult!!.newText)
  }

  @Test
  fun replacePhrase_handlesSpokenPunctuationNames() {
    val result = DictationPunctuationFormatter.replacePhrase("Hello, world", "comma", "period")
    org.junit.Assert.assertNotNull(result)
    assertEquals("Hello. world", result!!.newText)
  }

  @Test
  fun replacePhrase_notFound_returnsNull() {
    val result = DictationPunctuationFormatter.replacePhrase("Hello world", "banana", "apple")
    org.junit.Assert.assertNull(result)
  }
}
