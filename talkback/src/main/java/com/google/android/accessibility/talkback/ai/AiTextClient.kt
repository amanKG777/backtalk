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

/** The outcome of one AI text request. */
sealed class AiResult {
  data class Success(val text: String) : AiResult()

  data class Failure(val reason: Reason) : AiResult()

  enum class Reason {
    /** No AI service or key is set up, or the key was refused. */
    NOT_SET_UP,
    /** The phone could not reach the AI service in time. */
    NETWORK,
    /** The AI answered with nothing. */
    EMPTY,
    OTHER,
  }
}

/** Sends a text prompt to the AI the user has set up and returns its text answer. */
interface AiTextClient {
  fun isAvailable(): Boolean

  /** Calls [callback] exactly once, on the main thread. */
  fun complete(prompt: String, callback: (AiResult) -> Unit)
}
