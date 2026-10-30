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

import java.util.regex.Matcher

/**
 * Intelligent punctuation and formatting engine for Backtalk Dictation Mode.
 *
 * Automatically converts spoken punctuation marks into punctuation characters,
 * normalizes whitespace around punctuation, auto-capitalizes sentence beginnings
 * and internal sentences, and capitalizes standalone pronouns ('I', "I'm", etc.).
 */
object DictationPunctuationFormatter {

  private val PUNCTUATION_REPLACEMENTS = listOf(
    // Sentence terminators and common punctuation
    Regex("(?i)\\b(full stop|period)\\b") to ".",
    Regex("(?i)\\bcomma\\b") to ",",
    Regex("(?i)\\bquestion mark\\b") to "?",
    Regex("(?i)\\b(exclamation mark|exclamation point)\\b") to "!",
    Regex("(?i)\\bcolon\\b") to ":",
    Regex("(?i)\\bsemicolon\\b") to ";",

    // Line breaks
    Regex("(?i)[ \\t]*\\b(new paragraph)\\b[ \\t]*") to "\n\n",
    Regex("(?i)[ \\t]*\\b(new line|next line|newline)\\b[ \\t]*") to "\n",

    // Quotes & brackets (absorb spaces inside the quotes/brackets)
    Regex("(?i)\\b(open quote|opening quote|begin quote)\\b[ \\t]*") to "\"",
    Regex("(?i)[ \\t]*\\b(close quote|closing quote|end quote)\\b") to "\"",
    Regex("(?i)\\b(single quote|apostrophe)\\b") to "'",
    Regex("(?i)\\b(open parenthesis|open paren|left paren|left parenthesis)\\b[ \\t]*") to "(",
    Regex("(?i)[ \\t]*\\b(close parenthesis|close paren|right paren|right parenthesis)\\b") to ")",
    Regex("(?i)\\b(open bracket|left bracket)\\b[ \\t]*") to "[",
    Regex("(?i)[ \\t]*\\b(close bracket|right bracket)\\b") to "]",

    // Symbols
    Regex("(?i)\\b(hyphen|dash)\\b") to "-",
    Regex("(?i)\\bunderscore\\b") to "_",
    Regex("(?i)\\b(at sign|at the rate)\\b") to "@",
    Regex("(?i)\\b(hashtag|pound sign|hash symbol)\\b") to "#",
    Regex("(?i)\\b(dollar sign|dollar)\\b[ \\t]*") to "$",
    Regex("(?i)[ \\t]*\\b(percent sign|percent)\\b") to "%",
    Regex("(?i)\\b(ampersand|and sign)\\b") to "&",
    Regex("(?i)\\b(asterisk|star symbol)\\b") to "*",
    Regex("(?i)\\b(forward slash|slash)\\b") to "/",
    Regex("(?i)\\bbackslash\\b") to "\\",
    Regex("(?i)\\b(equals sign|equal sign)\\b") to "=",
    Regex("(?i)\\bplus sign\\b") to "+",

    // Common emoticons
    Regex("(?i)\\b(smiley face|happy face|smiley)\\b") to ":)",
    Regex("(?i)\\bsad face\\b") to ":(",
    Regex("(?i)\\b(winky face|wink face)\\b") to ";)"
  )

  /**
   * Formats the raw spoken speech by:
   * 1. Converting spoken punctuation phrases into punctuation marks.
   * 2. Normalizing whitespace around punctuation.
   * 3. Applying auto-capitalization (sentence starts, after [.?!], newline, standalone 'I' and contractions).
   *
   * @param rawText The raw text recognized from speech.
   * @param previousText The existing text immediately preceding the cursor in the text field.
   * @return Formatted text ready for insertion.
   */
  fun format(rawText: String, previousText: String? = null): String {
    if (rawText.isBlank()) return ""

    // 1. Spoken punctuation word replacement using literal quoteReplacement
    return tidy(replacePunctuationTokens(rawText.trim()), previousText)
  }

  private val QUESTION_WORDS = setOf(
    "who", "what", "when", "where", "why", "how", "is", "are", "do", "does", "did", "can",
    "could", "will", "would", "should"
  )

  /**
   * Adds a sentence-ending mark to a finished utterance that lacks one: '?' when the last
   * sentence starts with a question word, otherwise '.'. Text already ending in punctuation or
   * a line break is returned unchanged.
   */
  fun autoPunctuate(text: String): String {
    val trimmed = text.trimEnd()
    if (trimmed.isEmpty() || text.endsWith("\n")) return text
    if (trimmed.last() in ".!?:;,)]\"") return text
    val lastStart = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?', '\n')) + 1
    val firstWord = trimmed.substring(lastStart).trim().substringBefore(' ').lowercase()
    return trimmed + if (firstWord in QUESTION_WORDS) "?" else "."
  }

  /**
   * Repairs text already in a field: spacing around punctuation, sentence and "I"
   * capitalization, and an end mark. Literal words such as "comma" are left alone.
   */
  fun fixPunctuation(text: String): String {
    if (text.isBlank()) return text
    return autoPunctuate(tidy(text.trim(), null))
  }

  private fun tidy(input: String, previousText: String?): String {
    var text = input

    // 2. Whitespace normalization around punctuation
    // Remove space before punctuation marks: , . ! ? : ; ) ] } > %
    text = text.replace(Regex("\\s+([,.:;!?)\\]}>%])"), "$1")

    // Remove space immediately after opening marks: ( [ { < $
    text = text.replace(Regex("([(\\[{<$])\\s+"), "$1")

    // Remove any spaces around newlines
    text = text.replace(Regex("[ \\t]*\\n+[ \\t]*")) { match ->
      if (match.value.count { it == '\n' } > 1) "\n\n" else "\n"
    }

    // Ensure single space after punctuation if followed by alphanumeric
    text = text.replace(Regex("([,.:;!?])([A-Za-z0-9])"), "$1 $2")

    // Collapse multiple horizontal spaces
    text = text.replace(Regex("[ \\t]+"), " ")

    // 3. Auto-capitalization of pronouns and contractions
    text = text.replace(Regex("(?i)\\bi\\b"), "I")
    text = text.replace(Regex("(?i)\\bi'm\\b"), "I'm")
    text = text.replace(Regex("(?i)\\bi've\\b"), "I've")
    text = text.replace(Regex("(?i)\\bi'll\\b"), "I'll")
    text = text.replace(Regex("(?i)\\bi'd\\b"), "I'd")
    text = text.replace(Regex("(?i)\\bim\\b"), "I'm")
    text = text.replace(Regex("(?i)\\bive\\b"), "I've")
    text = text.replace(Regex("(?i)\\bill\\b"), "I'll")
    text = text.replace(Regex("(?i)\\bid\\b"), "I'd")

    // 4. Sentence capitalization inside the text
    // Capitalize after [.?!] followed by whitespace
    text = text.replace(Regex("([.!?]\\s+)([a-z])")) { matchResult ->
      matchResult.groupValues[1] + matchResult.groupValues[2].uppercase()
    }
    // Capitalize after newline
    text = text.replace(Regex("(\\n\\s*)([a-z])")) { matchResult ->
      matchResult.groupValues[1] + matchResult.groupValues[2].uppercase()
    }

    // 5. Initial letter capitalization
    val shouldCapitalizeFirst: Boolean = if (previousText.isNullOrBlank()) {
      true
    } else {
      val trimmedPrev = previousText.trimEnd()
      trimmedPrev.isEmpty() ||
          trimmedPrev.endsWith('.') ||
          trimmedPrev.endsWith('?') ||
          trimmedPrev.endsWith('!') ||
          previousText.endsWith('\n')
    }

    if (shouldCapitalizeFirst && text.isNotEmpty() && text[0].isLowerCase()) {
      text = text[0].uppercaseChar() + text.substring(1)
    }

    return text
  }

  /**
   * Calculates prefix spacing needed between the existing text in the text box
   * and the newly dictated text to insert.
   */
  fun getPrefixSpacing(previousText: String?, textToInsert: String): String {
    if (previousText.isNullOrEmpty() || textToInsert.isEmpty()) return ""
    val lastChar = previousText.last()
    val firstChar = textToInsert.first()

    // If text to insert starts with punctuation like ',', '.', '!', '?', ':', ';', no leading space
    if (firstChar in ",.!?:;)]}\n") return ""

    // If previous text already ends with whitespace or newline, no leading space
    if (lastChar.isWhitespace()) return ""

    // Otherwise, separate with a space
    return " "
  }

  /**
   * Replaces spoken punctuation words (e.g. "comma" -> ",", "period" -> ".")
   * without applying casing or whitespace trimming.
   */
  fun replacePunctuationTokens(raw: String): String {
    var text = raw
    for ((pattern, replacement) in PUNCTUATION_REPLACEMENTS) {
      text = pattern.replace(text, Matcher.quoteReplacement(replacement))
    }
    return text
  }

  data class ReplaceResult(
    val newText: String,
    val newCursor: Int,
    val matchedTarget: String,
    val finalReplacement: String
  )

  /**
   * Finds and replaces [target] with [replacement] inside [currentText].
   *
   * Features:
   * - Case-insensitive matching.
   * - Automatically preserves casing of the matched target (Capitalized, ALL CAPS, lowercase).
   * - Resolves spoken punctuation phrases (e.g. target "comma" matches "," or "comma").
   * - When [replaceAll] is false, replaces the last occurrence (most recent speech).
   * - Sets cursor immediately following the newly inserted replacement.
   *
   * @return [ReplaceResult] if matched and replaced, null if target not found.
   */
  fun replacePhrase(
    currentText: String,
    target: String,
    replacement: String,
    replaceAll: Boolean = false
  ): ReplaceResult? {
    val cleanTarget = target.trim()
    val cleanReplacement = replacement.trim()
    if (cleanTarget.isEmpty() || currentText.isEmpty()) return null

    // Determine actual search needle: raw target or formatted punctuation
    val punctTarget = replacePunctuationTokens(cleanTarget).trim()
    val searchNeedle = if (currentText.contains(cleanTarget, ignoreCase = true)) {
      cleanTarget
    } else if (punctTarget.isNotEmpty() && currentText.contains(punctTarget, ignoreCase = true)) {
      punctTarget
    } else {
      return null
    }

    // Determine base replacement string (format if punctuation name)
    val punctReplacement = replacePunctuationTokens(cleanReplacement).trim()
    val baseReplacement = if (punctReplacement != cleanReplacement) {
      punctReplacement
    } else {
      cleanReplacement
    }

    fun applyCasing(original: String, rep: String): String {
      if (rep.isEmpty() || original.isEmpty()) return rep
      return when {
        original.all { it.isUpperCase() } && original.length > 1 -> rep.uppercase()
        original.first().isUpperCase() -> rep.replaceFirstChar { it.uppercase() }
        else -> rep
      }
    }

    if (replaceAll) {
      val regex = Regex(Regex.escape(searchNeedle), RegexOption.IGNORE_CASE)
      var lastEnd = 0
      val sb = StringBuilder()
      var lastRep = baseReplacement

      val matches = regex.findAll(currentText).toList()
      if (matches.isEmpty()) return null

      for (match in matches) {
        sb.append(currentText.substring(lastEnd, match.range.first))
        val repWithCasing = applyCasing(match.value, baseReplacement)
        sb.append(repWithCasing)
        lastEnd = match.range.last + 1
        lastRep = repWithCasing
      }
      sb.append(currentText.substring(lastEnd))
      val newText = sb.toString()
      return ReplaceResult(
        newText = newText,
        newCursor = newText.length,
        matchedTarget = searchNeedle,
        finalReplacement = lastRep
      )
    } else {
      val index = currentText.lastIndexOf(searchNeedle, ignoreCase = true)
      if (index < 0) return null

      val matchedOriginal = currentText.substring(index, index + searchNeedle.length)
      val repWithCasing = applyCasing(matchedOriginal, baseReplacement)

      val newText = currentText.substring(0, index) + repWithCasing + currentText.substring(index + searchNeedle.length)
      val newCursor = index + repWithCasing.length

      return ReplaceResult(
        newText = newText,
        newCursor = newCursor,
        matchedTarget = matchedOriginal,
        finalReplacement = repWithCasing
      )
    }
  }
}
