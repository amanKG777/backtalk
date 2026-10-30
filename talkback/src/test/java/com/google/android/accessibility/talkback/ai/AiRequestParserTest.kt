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

package com.google.android.accessibility.talkback.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AiRequestParserTest {

  @Test
  fun parse_fixGrammar() {
    assertEquals(AiRequest.FixGrammar, AiRequestParser.parse("backtalk fix grammar"))
    assertEquals(AiRequest.FixGrammar, AiRequestParser.parse("Backtalk, correct the spelling"))
  }

  @Test
  fun parse_rewriteRequests() {
    assertEquals(AiRequest.Rewrite("shorter"), AiRequestParser.parse("Backtalk, make that shorter."))
    assertEquals(
      AiRequest.Rewrite("more polite"), AiRequestParser.parse("backtalk make that more polite"))
    assertEquals(AiRequest.Rewrite("shorter"), AiRequestParser.parse("backtalk shorten that"))
    assertEquals(AiRequest.Rewrite("longer"), AiRequestParser.parse("backtalk expand that"))
  }

  @Test
  fun parse_translate() {
    assertEquals(
      AiRequest.Translate("hindi"), AiRequestParser.parse("backtalk translate that to Hindi"))
    assertEquals(
      AiRequest.Translate("french"), AiRequestParser.parse("backtalk translate this into French"))
  }

  @Test
  fun parse_replyToThis() {
    assertEquals(AiRequest.ReplyToThis, AiRequestParser.parse("backtalk reply to this"))
    assertEquals(AiRequest.ReplyToThis, AiRequestParser.parse("backtalk, reply to this message"))
  }

  @Test
  fun parse_requiresThePrefix() {
    assertNull(AiRequestParser.parse("translate that to Hindi"))
    assertNull(AiRequestParser.parse("make that shorter"))
  }

  @Test
  fun parseWithoutPrefix_acceptsTheSameRequests() {
    assertEquals(
      AiRequest.Translate("hindi"), AiRequestParser.parseWithoutPrefix("translate that to Hindi"))
    assertEquals(AiRequest.ReplyToThis, AiRequestParser.parseWithoutPrefix("reply to this"))
    assertEquals(AiRequest.FixGrammar, AiRequestParser.parseWithoutPrefix("fix grammar"))
  }

  @Test
  fun ordinarySentencesAreNotRequests() {
    assertNull(AiRequestParser.parse("backtalk is a great app"))
    assertNull(AiRequestParser.parse("backtalk"))
    assertNull(AiRequestParser.parseWithoutPrefix("make the cake shorter"))
    assertNull(AiRequestParser.parseWithoutPrefix("make it rain"))
    assertNull(AiRequestParser.parseWithoutPrefix("i will reply to this tomorrow"))
  }
}
