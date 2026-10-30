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

/** A spoken "Backtalk, ..." AI request. */
sealed class AiRequest {
  object FixGrammar : AiRequest()

  /** Rewrite the target text following [instruction], e.g. "shorter" or "more polite". */
  data class Rewrite(val instruction: String) : AiRequest()

  data class Translate(val language: String) : AiRequest()

  object ReplyToThis : AiRequest()
}

/**
 * Recognises whole-utterance AI requests. Ordinary sentences are never requests: dictation needs
 * the leading "backtalk" ([parse]); the voice command layer, which strips that prefix before the
 * text reaches it, uses [parseWithoutPrefix].
 */
object AiRequestParser {

  private val PREFIX = Regex("^backtalk\\b\\s*")
  private val STYLE_WORDS =
    listOf(
      "shorter", "longer", "polite", "formal", "informal", "casual", "friendly", "professional",
      "funny", "simpler", "clearer", "concise", "brief", "short", "long", "detailed", "better",
      "nicer", "warm", "direct", "sound")

  private val FIX =
    Regex("^(?:fix|correct)(?: the| my)? (?:grammar|spelling|grammar and spelling|spelling and grammar)$")
  private val FIX_THAT = Regex("^fix (?:that|this|it)$")
  private val MAKE = Regex("^make (?:that|this|it) (.+)$")
  private val REWRITE = Regex("^(?:rewrite|reword|rephrase) (?:that|this|it)(?: (.+))?$")
  private val SHORTEN = Regex("^(?:shorten|condense)(?: (?:that|this|it))?$")
  private val LENGTHEN = Regex("^(?:lengthen|expand)(?: (?:that|this|it))?$")
  private val TRANSLATE = Regex("^translate (?:(?:that|this|it) )?(?:in)?to (.+)$")
  private val REPLY = Regex("^(?:reply to (?:this|that|it)(?: message)?|(?:write|draft) a reply)$")

  /** Parses [raw] only if it starts with "backtalk". */
  @JvmStatic
  fun parse(raw: String): AiRequest? {
    val cleaned = clean(raw)
    val match = PREFIX.find(cleaned) ?: return null
    return parseCleaned(cleaned.substring(match.range.last + 1))
  }

  /** Parses [raw] whose "backtalk" prefix, if any, has already been removed. */
  @JvmStatic
  fun parseWithoutPrefix(raw: String): AiRequest? {
    val cleaned = clean(raw)
    return parseCleaned(PREFIX.replaceFirst(cleaned, ""))
  }

  private fun parseCleaned(s: String): AiRequest? {
    if (s.isEmpty()) return null
    if (FIX.matches(s) || FIX_THAT.matches(s)) return AiRequest.FixGrammar
    if (SHORTEN.matches(s)) return AiRequest.Rewrite("shorter")
    if (LENGTHEN.matches(s)) return AiRequest.Rewrite("longer")
    if (REPLY.matches(s)) return AiRequest.ReplyToThis
    TRANSLATE.matchEntire(s)?.let { return AiRequest.Translate(it.groupValues[1]) }
    MAKE.matchEntire(s)?.let {
      val instruction = it.groupValues[1]
      return if (STYLE_WORDS.any { w -> instruction.contains(w) }) AiRequest.Rewrite(instruction)
      else null
    }
    REWRITE.matchEntire(s)?.let {
      return AiRequest.Rewrite(it.groupValues[1].ifEmpty { "better" })
    }
    return null
  }

  private fun clean(raw: String): String =
    raw.lowercase().replace(Regex("[,.!?;:]"), " ").replace(Regex("\\s+"), " ").trim()
}
