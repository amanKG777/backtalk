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

/** Text, cursor and a spoken label describing the edit that is about to be undone. */
data class Snapshot(val text: String, val cursor: Int, val label: String)

/** Bounded undo history for Dictation Mode; the oldest entries are dropped past [limit]. */
class DictationUndoStack(private val limit: Int = 50) {
  private val items = ArrayDeque<Snapshot>()

  val size: Int
    get() = items.size

  fun push(snapshot: Snapshot) {
    items.addLast(snapshot)
    while (items.size > limit) items.removeFirst()
  }

  fun pop(): Snapshot? = items.removeLastOrNull()

  fun clear() = items.clear()
}
