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
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPromptBuilderTest {

  @Test
  fun fixGrammar_containsTextAndOutputInstruction() {
    val p = AiPromptBuilder.build(AiRequest.FixGrammar, "me go home")
    assertTrue(p.contains("me go home"))
    assertTrue(p.contains("Return only the corrected text"))
  }

  @Test
  fun rewrite_containsInstructionAndText() {
    val p = AiPromptBuilder.build(AiRequest.Rewrite("shorter"), "this is a very long message")
    assertTrue(p.contains("shorter"))
    assertTrue(p.contains("this is a very long message"))
    assertTrue(p.contains("Return only the rewritten text"))
  }

  @Test
  fun translate_capitalisesLanguage() {
    val p = AiPromptBuilder.build(AiRequest.Translate("hindi"), "good morning")
    assertTrue(p.contains("Hindi"))
    assertTrue(p.contains("good morning"))
    assertTrue(p.contains("Return only the translation"))
  }

  @Test
  fun reply_containsMessageAndIntent() {
    val p =
      AiPromptBuilder.build(
        AiRequest.ReplyToThis, "", ReplyContext("Are you coming tonight?", "tell him I'll be late"))
    assertTrue(p.contains("Are you coming tonight?"))
    assertTrue(p.contains("tell him I'll be late"))
    assertTrue(p.contains("Return only the reply"))
  }

  @Test(expected = IllegalArgumentException::class)
  fun reply_withoutContextThrows() {
    AiPromptBuilder.build(AiRequest.ReplyToThis, "")
  }

  @Test
  fun userTextIsNeverDroppedForRewrite() {
    assertEquals(
      true, AiPromptBuilder.build(AiRequest.Rewrite("more polite"), "give me the file").contains("give me the file"))
  }
}
