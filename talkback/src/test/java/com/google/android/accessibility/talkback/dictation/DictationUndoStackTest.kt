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

class DictationUndoStackTest {

  private fun snap(n: Int) = Snapshot("t$n", n, "l$n")

  @Test
  fun pop_returnsMostRecentFirst() {
    val s = DictationUndoStack()
    s.push(snap(1))
    s.push(snap(2))
    assertEquals(snap(2), s.pop())
    assertEquals(snap(1), s.pop())
  }

  @Test
  fun push_pastLimitDropsOldest() {
    val s = DictationUndoStack(limit = 2)
    s.push(snap(1))
    s.push(snap(2))
    s.push(snap(3))
    assertEquals(2, s.size)
    assertEquals(snap(3), s.pop())
    assertEquals(snap(2), s.pop())
    assertNull(s.pop())
  }

  @Test
  fun pop_onEmptyIsNull() {
    assertNull(DictationUndoStack().pop())
  }

  @Test
  fun clear_empties() {
    val s = DictationUndoStack()
    s.push(snap(1))
    s.clear()
    assertEquals(0, s.size)
  }
}
