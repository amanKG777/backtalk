/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.scripting

import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandFuzzyMatcher

enum class VoiceMatchKind {
  EXACT,
  WILDCARD,
  FUZZY,
}

data class VoiceTarget<T>(val pattern: String, val value: T)

data class VoiceMatch<T>(
  val value: T,
  val pattern: String,
  val kind: VoiceMatchKind,
  val remainder: String?,
)

object ScriptVoiceMatcher {
  fun isValidPattern(pattern: String): Boolean {
    if (pattern.isBlank()) return false
    if ('*' !in pattern) return VoiceCommandFuzzyMatcher.normalize(pattern).isNotEmpty()
    if (pattern.count { it == '*' } > 1) return false
    val prefix = VoiceCommandFuzzyMatcher.normalize(pattern.substringBefore('*'))
    val suffix = VoiceCommandFuzzyMatcher.normalize(pattern.substringAfter('*'))
    return prefix.isNotEmpty() || suffix.isNotEmpty()
  }

  fun matchWildcard(normalizedCommand: String, pattern: String): String? {
    if ('*' !in pattern || pattern.count { it == '*' } > 1) return null
    val prefix = VoiceCommandFuzzyMatcher.normalize(pattern.substringBefore('*'))
    val suffix = VoiceCommandFuzzyMatcher.normalize(pattern.substringAfter('*'))
    if (prefix.isEmpty() && suffix.isEmpty()) return null

    val start =
      if (prefix.isEmpty()) {
        0
      } else {
        if (!normalizedCommand.startsWith("$prefix ")) return null
        prefix.length + 1
      }

    val end =
      if (suffix.isEmpty()) {
        normalizedCommand.length
      } else {
        if (!normalizedCommand.endsWith(" $suffix")) return null
        normalizedCommand.length - suffix.length - 1
      }

    if (start > end) return null
    val remainder = normalizedCommand.substring(start, end).trim()
    return remainder.ifEmpty { null }
  }

  fun <T> matchExactOrWildcard(
    command: String,
    targets: Iterable<VoiceTarget<T>>,
  ): VoiceMatch<T>? {
    val normCommand = VoiceCommandFuzzyMatcher.normalize(command)
    if (normCommand.isEmpty()) return null

    // 1. Exact matches always win over wildcards, regardless of map iteration order.
    for (target in targets) {
      if ('*' in target.pattern) continue
      val normPattern = VoiceCommandFuzzyMatcher.normalize(target.pattern)
      if (normPattern.isNotEmpty() && normCommand == normPattern) {
        return VoiceMatch(target.value, target.pattern, VoiceMatchKind.EXACT, remainder = null)
      }
    }

    // 2. Wildcard matches, preferring the most specific (longest fixed prefix + suffix).
    var bestMatch: VoiceMatch<T>? = null
    var bestScore = -1
    for (target in targets) {
      if ('*' !in target.pattern) continue
      val remainder = matchWildcard(normCommand, target.pattern) ?: continue
      val score =
        VoiceCommandFuzzyMatcher.normalize(target.pattern.substringBefore('*')).length +
          VoiceCommandFuzzyMatcher.normalize(target.pattern.substringAfter('*')).length
      if (score > bestScore) {
        bestScore = score
        bestMatch = VoiceMatch(target.value, target.pattern, VoiceMatchKind.WILDCARD, remainder)
      }
    }
    return bestMatch
  }

  fun <T> matchFuzzy(command: String, targets: Iterable<VoiceTarget<T>>): VoiceMatch<T>? {
    val normCommand = VoiceCommandFuzzyMatcher.normalize(command)
    if (normCommand.isEmpty()) return null

    for (target in targets) {
      if ('*' in target.pattern) continue
      val normPattern = VoiceCommandFuzzyMatcher.normalize(target.pattern)
      if (normPattern.isNotEmpty() && VoiceCommandFuzzyMatcher.matchesPhrase(normCommand, normPattern)) {
        return VoiceMatch(target.value, target.pattern, VoiceMatchKind.FUZZY, remainder = null)
      }
    }
    return null
  }

  fun <T> match(
    command: String,
    targets: Iterable<VoiceTarget<T>>,
    allowFuzzy: Boolean = true,
  ): VoiceMatch<T>? =
    matchExactOrWildcard(command, targets)
      ?: if (allowFuzzy) matchFuzzy(command, targets) else null
}
