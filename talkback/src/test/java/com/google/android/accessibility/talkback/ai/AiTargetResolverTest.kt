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

class AiTargetResolverTest {

  private val text = "Hello there. How are you"

  @Test
  fun selectionWins() {
    assertEquals(TextTarget(0, 5), AiTargetResolver.resolve(text, 0, 5, 13..22))
  }

  @Test
  fun lastDictatedUsedWhenNoSelection() {
    assertEquals(TextTarget(13, 23), AiTargetResolver.resolve(text, 7, 7, 13..22))
  }

  @Test
  fun wholeFieldWhenNothingElse() {
    assertEquals(TextTarget(0, text.length), AiTargetResolver.resolve(text, 3, 3, null))
  }

  @Test
  fun emptyOrBlankTextHasNoTarget() {
    assertNull(AiTargetResolver.resolve("", 0, 0, null))
    assertNull(AiTargetResolver.resolve("   ", 0, 0, null))
  }

  @Test
  fun outOfRangeAndReversedSelectionsAreFixed() {
    assertEquals(TextTarget(6, text.length), AiTargetResolver.resolve(text, 99, 6, null))
    assertEquals(TextTarget(2, 5), AiTargetResolver.resolve(text, 5, 2, null))
  }

  @Test
  fun lastDictatedBeyondTheTextIsClamped() {
    assertEquals(TextTarget(20, text.length), AiTargetResolver.resolve(text, 0, 0, 20..99))
  }
}
