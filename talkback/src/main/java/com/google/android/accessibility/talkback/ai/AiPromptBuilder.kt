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

/** The message being replied to and what the user wants to say in return. */
data class ReplyContext(val incomingMessage: String, val userIntent: String)

/** Builds the text prompt sent to the AI for each [AiRequest]. */
object AiPromptBuilder {

  fun build(request: AiRequest, text: String, reply: ReplyContext? = null): String =
    when (request) {
      AiRequest.FixGrammar ->
        "Fix the grammar, spelling and punctuation of the following text. Keep its meaning, " +
          "tone and language. Return only the corrected text, with no explanation.\n\n$text"
      is AiRequest.Rewrite ->
        "Rewrite the following text so it is ${request.instruction}. Keep its meaning and " +
          "language. Return only the rewritten text, with no explanation.\n\n$text"
      is AiRequest.Translate ->
        "Translate the following text into ${capitalise(request.language)}. " +
          "Return only the translation, with no explanation.\n\n$text"
      AiRequest.ReplyToThis -> {
        requireNotNull(reply) { "ReplyToThis needs a ReplyContext" }
        "Write a reply to the message below. The person replying wants to say: " +
          "\"${reply.userIntent}\". Write it as them, in the first person, in the same language " +
          "as the message. Return only the reply, with no explanation.\n\n" +
          "Message:\n${reply.incomingMessage}"
      }
    }

  private fun capitalise(s: String) = s.replaceFirstChar { it.uppercaseChar() }
}
