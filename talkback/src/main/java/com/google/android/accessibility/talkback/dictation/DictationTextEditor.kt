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

/** What an edit did, so the controller can speak a matching confirmation. */
sealed class EditFeedback {
  data class Inserted(val text: String) : EditFeedback()
  data class Deleted(val what: String) : EditFeedback()
  data class Changed(val what: String) : EditFeedback()
  data class Moved(val description: String) : EditFeedback()
  data class NotFound(val what: String) : EditFeedback()
  object NothingToDo : EditFeedback()
  object BulletEnded : EditFeedback()
}

data class EditResult(val text: String, val cursor: Int, val feedback: EditFeedback)

/**
 * Pure text-editing operations for Dictation Mode. Every function takes the field's text and
 * cursor and returns the new text, the new cursor and a feedback value; none touches Android.
 * "That" in the case commands means the last word before the cursor.
 */
object DictationTextEditor {

  private const val BULLET = "• "
  private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '\n')

  fun deleteLastSentence(text: String, cursor: Int): EditResult {
    val c = clamp(text, cursor)
    val range = sentenceRange(text, c) ?: return nothing(text, c)
    val deleted = text.substring(range.first, range.second)
    return EditResult(
      text.substring(0, range.first) + text.substring(c),
      range.first,
      EditFeedback.Deleted(deleted))
  }

  fun deletePhrase(text: String, cursor: Int, phrase: String): EditResult {
    val c = clamp(text, cursor)
    val idx = text.lastIndexOf(phrase, ignoreCase = true)
    if (phrase.isEmpty() || idx < 0) return EditResult(text, c, EditFeedback.NotFound(phrase))
    val before = text.substring(0, idx)
    var after = text.substring(idx + phrase.length)
    if (before.endsWith(" ") && after.startsWith(" ")) after = after.substring(1)
    return EditResult(before + after, before.length, EditFeedback.Deleted(phrase))
  }

  fun insertAround(
    text: String,
    cursor: Int,
    insert: String,
    phrase: String,
    before: Boolean,
  ): EditResult {
    val c = clamp(text, cursor)
    val idx = text.lastIndexOf(phrase, ignoreCase = true)
    if (phrase.isEmpty() || idx < 0) return EditResult(text, c, EditFeedback.NotFound(phrase))
    return if (before) {
      val newText = text.substring(0, idx) + insert + " " + text.substring(idx)
      EditResult(newText, idx + insert.length + 1, EditFeedback.Inserted(insert))
    } else {
      val end = idx + phrase.length
      val newText = text.substring(0, end) + " " + insert + text.substring(end)
      EditResult(newText, end + 1 + insert.length, EditFeedback.Inserted(insert))
    }
  }

  /** Moves the cursor by [words] words; negative moves backwards. Clamped to the text. */
  fun moveWords(text: String, cursor: Int, words: Int): EditResult {
    val start = clamp(text, cursor)
    var pos = start
    repeat(kotlin.math.abs(words)) {
      if (words > 0) {
        while (pos < text.length && text[pos].isWhitespace()) pos++
        while (pos < text.length && !text[pos].isWhitespace()) pos++
      } else {
        while (pos > 0 && text[pos - 1].isWhitespace()) pos--
        while (pos > 0 && !text[pos - 1].isWhitespace()) pos--
      }
    }
    pos = clamp(text, pos)
    val description =
      when {
        pos == 0 -> "start"
        pos == text.length -> "end"
        else -> text.substring(minOf(start, pos), maxOf(start, pos)).trim()
      }
    return EditResult(text, pos, EditFeedback.Moved(description))
  }

  fun goToStart(text: String, cursor: Int): EditResult =
    EditResult(text, 0, EditFeedback.Moved("start"))

  fun goToEnd(text: String, cursor: Int): EditResult =
    EditResult(text, text.length, EditFeedback.Moved("end"))

  fun capitalizeThat(text: String, cursor: Int): EditResult =
    transformLastWord(text, cursor) { w -> w.replaceFirstChar { it.uppercaseChar() } }

  fun upperCaseThat(text: String, cursor: Int): EditResult =
    transformLastWord(text, cursor) { it.uppercase() }

  fun lowerCaseThat(text: String, cursor: Int): EditResult =
    transformLastWord(text, cursor) { it.lowercase() }

  fun capitalizeWord(text: String, cursor: Int, word: String): EditResult {
    val c = clamp(text, cursor)
    val idx = text.lastIndexOf(word, ignoreCase = true)
    if (word.isEmpty() || idx < 0) return EditResult(text, c, EditFeedback.NotFound(word))
    val matched = text.substring(idx, idx + word.length)
    val cap = matched.replaceFirstChar { it.uppercaseChar() }
    val newText = text.substring(0, idx) + cap + text.substring(idx + word.length)
    return EditResult(newText, c, EditFeedback.Changed(cap))
  }

  fun startBullet(text: String, cursor: Int): EditResult {
    val c = clamp(text, cursor)
    val atLineStart = c == 0 || text[c - 1] == '\n'
    val ins = if (atLineStart) BULLET else "\n" + BULLET
    return EditResult(
      text.substring(0, c) + ins + text.substring(c), c + ins.length, EditFeedback.Changed("bullet list"))
  }

  /**
   * Called for "new line" while a bullet list is active: adds the next bullet, or ends the list
   * when the current line holds only an empty bullet.
   */
  fun continueBullet(text: String, cursor: Int): EditResult {
    val c = clamp(text, cursor)
    val lineStart = text.lastIndexOf('\n', c - 1) + 1
    val line = text.substring(lineStart, c)
    if (line.trim() == BULLET.trim()) {
      return EditResult(
        text.substring(0, lineStart) + text.substring(c), lineStart, EditFeedback.BulletEnded)
    }
    val ins = "\n" + BULLET
    return EditResult(
      text.substring(0, c) + ins + text.substring(c), c + ins.length, EditFeedback.Inserted("bullet"))
  }

  fun lastSentence(text: String, cursor: Int): String? {
    val range = sentenceRange(text, clamp(text, cursor)) ?: return null
    return text.substring(range.first, range.second).trim().ifEmpty { null }
  }

  fun lastWord(text: String, cursor: Int): String? {
    val range = lastWordRange(text, clamp(text, cursor)) ?: return null
    return text.substring(range.first, range.second)
  }

  private fun transformLastWord(text: String, cursor: Int, f: (String) -> String): EditResult {
    val c = clamp(text, cursor)
    val range = lastWordRange(text, c) ?: return nothing(text, c)
    val changed = f(text.substring(range.first, range.second))
    val newText = text.substring(0, range.first) + changed + text.substring(range.second)
    return EditResult(newText, c, EditFeedback.Changed(changed))
  }

  /** The [start, end) of the last word ending at or before [cursor], or null if none. */
  private fun lastWordRange(text: String, cursor: Int): Pair<Int, Int>? {
    var end = cursor
    while (end > 0 && !isWordChar(text[end - 1])) end--
    var start = end
    while (start > 0 && isWordChar(text[start - 1])) start--
    return if (start == end) null else start to end
  }

  /** The [start, end) of the last sentence before [cursor], excluding leading spaces. */
  private fun sentenceRange(text: String, cursor: Int): Pair<Int, Int>? {
    val trimmedEnd = text.substring(0, cursor).trimEnd().length
    if (trimmedEnd == 0) return null
    val searchEnd = if (text[trimmedEnd - 1] in SENTENCE_ENDS) trimmedEnd - 1 else trimmedEnd
    var start = text.lastIndexOfAny(SENTENCE_ENDS, searchEnd - 1) + 1
    while (start < trimmedEnd && text[start].isWhitespace()) start++
    return if (start >= trimmedEnd) null else start to cursor
  }

  private fun isWordChar(ch: Char) = ch.isLetterOrDigit() || ch == '\''

  private fun nothing(text: String, cursor: Int) = EditResult(text, cursor, EditFeedback.NothingToDo)

  /** Clamps [cursor] into the text and never leaves it between a surrogate pair. */
  private fun clamp(text: String, cursor: Int): Int {
    val c = cursor.coerceIn(0, text.length)
    return if (c in 1 until text.length && Character.isLowSurrogate(text[c]) &&
      Character.isHighSurrogate(text[c - 1])) c - 1 else c
  }
}
