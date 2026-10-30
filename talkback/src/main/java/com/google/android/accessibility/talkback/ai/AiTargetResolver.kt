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

/** A [start, end) range of the text field that an AI request acts on. */
data class TextTarget(val start: Int, val end: Int)

/** Decides what "that" means for an AI request. */
object AiTargetResolver {

  /**
   * Order of preference: a non-empty selection, then the last dictated range, then the whole
   * field. Returns null when the field has no text.
   */
  fun resolve(text: String, selStart: Int, selEnd: Int, lastDictated: IntRange?): TextTarget? {
    if (text.isBlank()) return null
    val a = selStart.coerceIn(0, text.length)
    val b = selEnd.coerceIn(0, text.length)
    if (a != b) return TextTarget(minOf(a, b), maxOf(a, b))
    if (lastDictated != null && !lastDictated.isEmpty()) {
      val start = lastDictated.first.coerceIn(0, text.length)
      val end = (lastDictated.last + 1).coerceIn(0, text.length)
      if (start < end) return TextTarget(start, end)
    }
    return TextTarget(0, text.length)
  }
}
