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

import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandFuzzyMatcher

/** A spoken editing command recognised while Dictation Mode is active. */
sealed class DictationCommand {
  object Stop : DictationCommand()
  object DeleteWord : DictationCommand()
  object DeleteChar : DictationCommand()
  object DeleteLastSentence : DictationCommand()
  object Clear : DictationCommand()
  object Undo : DictationCommand()
  object NewLine : DictationCommand()
  object NewParagraph : DictationCommand()
  object ReadAll : DictationCommand()
  object ReadLastSentence : DictationCommand()
  object ReadLastWord : DictationCommand()
  object FixPunctuation : DictationCommand()
  object CapitalizeThat : DictationCommand()
  object UpperCaseThat : DictationCommand()
  object LowerCaseThat : DictationCommand()
  object BulletList : DictationCommand()
  object GoToStart : DictationCommand()
  object GoToEnd : DictationCommand()

  data class AutoPunctuation(val on: Boolean) : DictationCommand()

  /** Moves the cursor by [words]; negative moves backwards. */
  data class Move(val words: Int) : DictationCommand()

  data class CapitalizeWord(val word: String) : DictationCommand()
  data class DeletePhrase(val phrase: String) : DictationCommand()
  data class InsertAround(val text: String, val phrase: String, val before: Boolean) :
    DictationCommand()

  data class Replace(val target: String, val replacement: String, val all: Boolean) :
    DictationCommand()
}

/**
 * Maps a whole spoken utterance to a [DictationCommand]. Only whole utterances match, so a
 * sentence that merely contains a command word ("please clear the table") stays dictation text.
 * Harmless one-word commands such as "return" must be prefixed with "command".
 */
object DictationCommandParser {

  private const val COMMAND_PREFIX = "command "

  private val NUMBER_WORDS =
    mapOf(
      "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
      "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)

  private val STOP = setOf(
    "stop dictation", "exit dictation", "finish dictation", "end dictation",
    "turn off dictation", "stop", "done", "finish", "exit", "cancel", "quit")
  private val DELETE_WORD = setOf(
    "delete word", "delete last word", "backspace word", "remove word", "erase word",
    "delete previous word")
  private val DELETE_CHAR = setOf(
    "delete character", "delete letter", "backspace", "remove letter", "delete last letter")
  private val DELETE_SENTENCE = setOf(
    "delete last sentence", "delete sentence", "delete previous sentence", "remove last sentence")
  private val CLEAR = setOf(
    "clear text", "clear all", "delete all", "erase text", "erase all", "delete everything",
    "clear everything", "clear")
  private val UNDO = setOf("undo", "revert", "undo that", "undo last")
  private val NEW_LINE = setOf("new line", "next line", "newline", "command return",
    "command enter")
  private val NEW_PARAGRAPH = setOf("new paragraph", "command paragraph")
  private val READ_ALL = setOf(
    "read text", "read all", "read message", "read back", "what did i say", "command read")
  private val READ_SENTENCE = setOf("read last sentence", "read sentence")
  private val READ_WORD = setOf("read last word", "read word")
  private val FIX_PUNCT = setOf("fix punctuation", "correct punctuation", "add punctuation")
  private val CAPITALIZE = setOf("capitalize that", "capitalize this", "capitalise that")
  private val UPPER = setOf("all caps", "uppercase that", "upper case that", "make that uppercase")
  private val LOWER = setOf("lowercase that", "lower case that", "make that lowercase")
  private val BULLET = setOf("bullet list", "start bullet list", "bullets", "start bullets")
  private val GO_START = setOf("go to start", "go to beginning", "go to the start",
    "go to the beginning", "go to top")
  private val GO_END = setOf("go to end", "go to the end", "go to bottom")

  private val AUTO_PUNCT = Regex("^auto(?:matic)? punctuation (on|off)$")
  private val MOVE = Regex(
    "^(?:(?:go|move)\\s+)?(back|backward|backwards|forward|forwards|ahead)\\s+(\\d+|[a-z]+)\\s+words?$")
  private val CAPITALIZE_WORD = Regex("^capitali[sz]e\\s+(.+)$")
  private val DELETE_PHRASE = Regex("^(?:delete|remove|erase)\\s+(.+)$")
  private val INSERT_AROUND = Regex("^insert\\s+(.+?)\\s+(before|after)\\s+(.+)$")

  private val REPLACE_ALL = Regex("(?i)^(?:please\\s+)?replace\\s+(?:all|every)\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")
  private val REPLACE = Regex("(?i)^(?:please\\s+)?replace\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")
  private val CHANGE_ALL = Regex("(?i)^(?:please\\s+)?change\\s+(?:all|every)\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+please)?$")
  private val CHANGE = Regex("(?i)^(?:please\\s+)?change\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+please)?$")
  private val SWAP = Regex("(?i)^(?:please\\s+)?swap\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")

  /** Returns the command for [raw], or null when [raw] is ordinary dictation text. */
  @JvmStatic
  fun parse(raw: String): DictationCommand? {
    val norm = VoiceCommandFuzzyMatcher.normalize(raw).lowercase()
    if (norm.isEmpty()) return null

    when (norm) {
      in STOP -> return DictationCommand.Stop
      in DELETE_WORD -> return DictationCommand.DeleteWord
      in DELETE_CHAR -> return DictationCommand.DeleteChar
      in DELETE_SENTENCE -> return DictationCommand.DeleteLastSentence
      in CLEAR -> return DictationCommand.Clear
      in UNDO -> return DictationCommand.Undo
      in NEW_LINE -> return DictationCommand.NewLine
      in NEW_PARAGRAPH -> return DictationCommand.NewParagraph
      in READ_ALL -> return DictationCommand.ReadAll
      in READ_SENTENCE -> return DictationCommand.ReadLastSentence
      in READ_WORD -> return DictationCommand.ReadLastWord
      in FIX_PUNCT -> return DictationCommand.FixPunctuation
      in CAPITALIZE -> return DictationCommand.CapitalizeThat
      in UPPER -> return DictationCommand.UpperCaseThat
      in LOWER -> return DictationCommand.LowerCaseThat
      in BULLET -> return DictationCommand.BulletList
      in GO_START -> return DictationCommand.GoToStart
      in GO_END -> return DictationCommand.GoToEnd
    }

    AUTO_PUNCT.matchEntire(norm)?.let {
      return DictationCommand.AutoPunctuation(it.groupValues[1] == "on")
    }

    MOVE.matchEntire(norm)?.let {
      val count = parseCount(it.groupValues[2]) ?: return@let
      val backwards = it.groupValues[1].startsWith("back")
      return DictationCommand.Move(if (backwards) -count else count)
    }

    // Phrase-bearing commands use the lightly cleaned raw text so apostrophes survive.
    val cleaned = raw.trim().trimEnd('.', '!', '?', ',').trim()

    parseReplace(cleaned)?.let { return it }

    INSERT_AROUND.matchEntire(cleaned.lowercase())?.let {
      return DictationCommand.InsertAround(
        it.groupValues[1], it.groupValues[3], it.groupValues[2] == "before")
    }

    CAPITALIZE_WORD.matchEntire(cleaned.lowercase())?.let {
      return DictationCommand.CapitalizeWord(it.groupValues[1])
    }

    DELETE_PHRASE.matchEntire(cleaned.lowercase())?.let {
      return DictationCommand.DeletePhrase(it.groupValues[1])
    }

    return null
  }

  @JvmStatic
  fun isStopCommand(raw: String): Boolean = parse(raw) == DictationCommand.Stop

  private fun parseCount(token: String): Int? = token.toIntOrNull() ?: NUMBER_WORDS[token]

  private fun parseReplace(cleaned: String): DictationCommand.Replace? {
    val candidates = listOf(
      REPLACE_ALL to true, CHANGE_ALL to true, REPLACE to false, CHANGE to false, SWAP to false)
    for ((regex, all) in candidates) {
      regex.matchEntire(cleaned)?.let {
        return DictationCommand.Replace(it.groupValues[1], it.groupValues[2], all)
      }
    }
    return null
  }
}
