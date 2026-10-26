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

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Feedback.VoiceRecognition
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandFuzzyMatcher
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.PerformActionUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils
import java.util.ArrayDeque

/**
 * Controller for Backtalk's Dictation Mode.
 *
 * Provides:
 * - Hands-free continuous dictation session directly into focused text fields
 * - Spoken punctuation & symbol parsing via [DictationPunctuationFormatter]
 * - Spoken voice editing commands: "delete word", "delete character", "clear text", "undo", "new line", "read text"
 * - Voice command toggle ("dictate", "start dictation", "dictation mode", "stop dictation")
 * - Reading Controls (Selector) toggle integration
 */
class DictationController(
    private val context: Context,
    private val accessibilityFocusMonitor: AccessibilityFocusMonitor
) {
    companion object {
        private const val TAG = "DictationController"
        private const val RESTART_DELAY_MS = 350L
        private const val MAX_SILENCE_TIMEOUTS = 1

        private val REPLACE_ALL_REGEX = Regex("(?i)^(?:please\\s+)?replace\\s+(?:all|every)\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")
        private val REPLACE_REGEX = Regex("(?i)^(?:please\\s+)?replace\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")
        private val CHANGE_ALL_REGEX = Regex("(?i)^(?:please\\s+)?change\\s+(?:all|every)\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+please)?$")
        private val CHANGE_REGEX = Regex("(?i)^(?:please\\s+)?change\\s+(.+?)\\s+to\\s+(.+?)(?:\\s+please)?$")
        private val SWAP_REGEX = Regex("(?i)^(?:please\\s+)?swap\\s+(.+?)\\s+with\\s+(.+?)(?:\\s+please)?$")
    }

    private var pipeline: Pipeline.FeedbackReturner? = null
    private var voiceCommandActor: VoiceCommandActor? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isDictationActive: Boolean = false
        private set

    private val undoHistory = ArrayDeque<String>()
    private var silenceTimeoutCount = 0

    fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
        this.pipeline = pipeline
    }

    fun setVoiceCommandActor(actor: VoiceCommandActor?) {
        this.voiceCommandActor = actor
    }

    /**
     * Returns the currently focused editable node, or null if focus is not editable.
     */
    fun getTargetEditableNode(): AccessibilityNodeInfoCompat? {
        val focusNode = accessibilityFocusMonitor.getAccessibilityFocus(true) ?: return null
        return accessibilityFocusMonitor.getNodeForEditingActions(focusNode)
    }

    /**
     * Toggles dictation mode on or off.
     */
    fun toggleDictation(eventId: EventId? = EVENT_ID_UNTRACKED): Boolean {
        return if (isDictationActive) {
            stopDictation(eventId, userInitiated = true)
            true
        } else {
            startDictation(eventId)
        }
    }

    /**
     * Starts dictation mode on the active text field.
     */
    fun startDictation(eventId: EventId? = EVENT_ID_UNTRACKED): Boolean {
        val targetNode = getTargetEditableNode()
        if (targetNode == null) {
            speak(context.getString(R.string.dictation_not_editable))
            return false
        }

        isDictationActive = true
        silenceTimeoutCount = 0
        undoHistory.clear()

        // Push current text as initial undo state
        val initialText = AccessibilityNodeInfoUtils.getText(targetNode)?.toString() ?: ""
        undoHistory.push(initialText)

        speak(context.getString(R.string.dictation_started))
        scheduleStartListening(RESTART_DELAY_MS)
        return true
    }

    /**
     * Stops dictation mode.
     */
    fun stopDictation(eventId: EventId? = EVENT_ID_UNTRACKED, userInitiated: Boolean = true) {
        if (!isDictationActive) return
        isDictationActive = false
        silenceTimeoutCount = 0
        undoHistory.clear()
        mainHandler.removeCallbacksAndMessages(null)

        voiceCommandActor?.stopListening()

        if (userInitiated) {
            speak(context.getString(R.string.dictation_stopped))
        }
    }

    /**
     * Called when speech recognition reports an error.
     */
    fun onRecognitionError(error: Int) {
        if (!isDictationActive) return

        if (error == android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            if (silenceTimeoutCount < MAX_SILENCE_TIMEOUTS) {
                silenceTimeoutCount++
                scheduleStartListening(RESTART_DELAY_MS)
            } else {
                stopDictation(EVENT_ID_UNTRACKED, userInitiated = false)
                speak(context.getString(R.string.dictation_stopped))
            }
        } else if (error == android.speech.SpeechRecognizer.ERROR_NO_MATCH) {
            // Nothing recognized, retry listening
            scheduleStartListening(RESTART_DELAY_MS)
        } else {
            stopDictation(EVENT_ID_UNTRACKED, userInitiated = false)
            speak(context.getString(R.string.dictation_stopped))
        }
    }

    /**
     * Processes incoming speech results when dictation mode is active.
     */
    fun handleDictationSpeech(
        command: String,
        isPartialResult: Boolean,
        eventId: EventId? = EVENT_ID_UNTRACKED
    ): Boolean {
        if (!isDictationActive) return false
        if (isPartialResult) {
            return false
        }

        val raw = command.trim()
        if (raw.isEmpty()) return false

        silenceTimeoutCount = 0
        val norm = VoiceCommandFuzzyMatcher.normalize(raw).lowercase()

        // 1. Exit / Stop dictation
        if (isStopCommand(norm)) {
            stopDictation(eventId, userInitiated = true)
            return true
        }

        val targetNode = getTargetEditableNode()
        if (targetNode == null) {
            stopDictation(eventId, userInitiated = false)
            speak(context.getString(R.string.dictation_not_editable))
            return true
        }

        // 2. Editing & Correction Commands
        if (isDeleteWordCommand(norm)) {
            deleteLastWord(targetNode, eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isDeleteCharacterCommand(norm)) {
            deleteLastCharacter(targetNode, eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isClearCommand(norm)) {
            clearAllText(targetNode, eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isUndoCommand(norm)) {
            undo(targetNode, eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isNewlineCommand(norm)) {
            insertText(targetNode, "\n", eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isNewParagraphCommand(norm)) {
            insertText(targetNode, "\n\n", eventId)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        if (isReadTextCommand(norm)) {
            readCurrentText(targetNode)
            scheduleStartListening(1000L)
            return true
        }

        if (handleReplaceCommand(raw, targetNode, eventId)) {
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        // 3. Spoken Text with Smart Punctuation
        val currentText = AccessibilityNodeInfoUtils.getText(targetNode)?.toString() ?: ""
        val cursor = getCursorPosition(targetNode, currentText)
        val textBeforeCursor = if (cursor in 1..currentText.length) {
            currentText.substring(0, cursor)
        } else {
            currentText
        }

        val formattedText = DictationPunctuationFormatter.format(raw, textBeforeCursor)
        val spacing = DictationPunctuationFormatter.getPrefixSpacing(textBeforeCursor, formattedText)
        val toInsert = spacing + formattedText

        insertText(targetNode, toInsert, eventId)
        scheduleStartListening(RESTART_DELAY_MS)
        return true
    }

    fun isDictationStartCommand(command: String): Boolean {
        val norm = VoiceCommandFuzzyMatcher.normalize(command).lowercase()
        return norm in setOf(
            "dictate",
            "start dictation",
            "start dictating",
            "dictation mode",
            "turn on dictation",
            "enable dictation",
            "begin dictation",
            "start typing"
        )
    }

    fun isDictationStopCommand(command: String): Boolean {
        val norm = VoiceCommandFuzzyMatcher.normalize(command).lowercase()
        return isStopCommand(norm)
    }

    private fun isStopCommand(norm: String): Boolean {
        return norm in setOf(
            "stop dictation",
            "exit dictation",
            "finish dictation",
            "end dictation",
            "turn off dictation",
            "stop",
            "done",
            "finish",
            "exit",
            "cancel",
            "quit"
        )
    }

    private fun isDeleteWordCommand(norm: String): Boolean {
        return norm in setOf(
            "delete word",
            "delete last word",
            "backspace word",
            "remove word",
            "erase word",
            "delete previous word"
        )
    }

    private fun isDeleteCharacterCommand(norm: String): Boolean {
        return norm in setOf(
            "delete character",
            "delete letter",
            "backspace",
            "remove letter",
            "delete last letter"
        )
    }

    private fun isClearCommand(norm: String): Boolean {
        return norm in setOf(
            "clear text",
            "clear all",
            "delete all",
            "erase text",
            "erase all",
            "delete everything",
            "clear everything",
            "clear"
        )
    }

    private fun isUndoCommand(norm: String): Boolean {
        return norm in setOf(
            "undo",
            "revert",
            "undo that",
            "undo last"
        )
    }

    private fun isNewlineCommand(norm: String): Boolean {
        return norm in setOf(
            "new line",
            "next line",
            "enter",
            "newline",
            "return"
        )
    }

    private fun isNewParagraphCommand(norm: String): Boolean {
        return norm in setOf(
            "new paragraph",
            "paragraph"
        )
    }

    private fun isReadTextCommand(norm: String): Boolean {
        return norm in setOf(
            "read text",
            "read all",
            "read message",
            "read back",
            "what did i say",
            "read"
        )
    }

    private fun getCursorPosition(node: AccessibilityNodeInfoCompat, currentText: String): Int {
        val selEnd = node.textSelectionEnd
        return if (selEnd in 0..currentText.length) selEnd else currentText.length
    }

    private fun insertText(node: AccessibilityNodeInfoCompat, textToInsert: String, eventId: EventId?): Boolean {
        val currentText = AccessibilityNodeInfoUtils.getText(node)?.toString() ?: ""
        undoHistory.push(currentText)

        val selStart = if (node.textSelectionStart in 0..currentText.length) node.textSelectionStart else currentText.length
        val selEnd = if (node.textSelectionEnd in 0..currentText.length) node.textSelectionEnd else currentText.length

        val start = minOf(selStart, selEnd)
        val end = maxOf(selStart, selEnd)

        val newText = StringBuilder(currentText.substring(0, start))
            .append(textToInsert)
            .append(currentText.substring(end))
            .toString()

        val newCursor = start + textToInsert.length
        return setTextAndCursor(node, newText, newCursor, eventId)
    }

    private fun deleteLastWord(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = AccessibilityNodeInfoUtils.getText(node)?.toString() ?: return false
        if (currentText.isEmpty()) return false
        undoHistory.push(currentText)

        val cursor = getCursorPosition(node, currentText)
        val beforeCursor = currentText.substring(0, cursor)
        val afterCursor = currentText.substring(cursor)

        val trimmedBefore = beforeCursor.trimEnd()
        if (trimmedBefore.isEmpty()) {
            val newText = afterCursor
            return setTextAndCursor(node, newText, 0, eventId)
        }

        val lastSpace = trimmedBefore.lastIndexOfAny(charArrayOf(' ', '\t', '\n'))
        val newBefore = if (lastSpace >= 0) {
            trimmedBefore.substring(0, lastSpace + 1)
        } else {
            ""
        }
        val newText = newBefore + afterCursor
        val newCursor = newBefore.length
        val success = setTextAndCursor(node, newText, newCursor, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_deleted_word))
        }
        return success
    }

    private fun deleteLastCharacter(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = AccessibilityNodeInfoUtils.getText(node)?.toString() ?: return false
        if (currentText.isEmpty()) return false
        val cursor = getCursorPosition(node, currentText)
        if (cursor <= 0) return false
        undoHistory.push(currentText)

        val newText = currentText.substring(0, cursor - 1) + currentText.substring(cursor)
        val success = setTextAndCursor(node, newText, cursor - 1, eventId)
        return success
    }

    private fun clearAllText(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = AccessibilityNodeInfoUtils.getText(node)?.toString() ?: ""
        if (currentText.isNotEmpty()) {
            undoHistory.push(currentText)
        }
        val success = setTextAndCursor(node, "", 0, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_cleared))
        }
        return success
    }

    private fun handleReplaceCommand(raw: String, targetNode: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        var target: String? = null
        var replacement: String? = null
        var replaceAll = false

        REPLACE_ALL_REGEX.matchEntire(raw)?.let {
            target = it.groupValues[1]
            replacement = it.groupValues[2]
            replaceAll = true
        } ?: REPLACE_REGEX.matchEntire(raw)?.let {
            target = it.groupValues[1]
            replacement = it.groupValues[2]
            replaceAll = false
        } ?: CHANGE_ALL_REGEX.matchEntire(raw)?.let {
            target = it.groupValues[1]
            replacement = it.groupValues[2]
            replaceAll = true
        } ?: CHANGE_REGEX.matchEntire(raw)?.let {
            target = it.groupValues[1]
            replacement = it.groupValues[2]
            replaceAll = false
        } ?: SWAP_REGEX.matchEntire(raw)?.let {
            target = it.groupValues[1]
            replacement = it.groupValues[2]
            replaceAll = false
        }

        if (target == null || replacement == null) return false

        val currentText = AccessibilityNodeInfoUtils.getText(targetNode)?.toString() ?: ""
        val result = DictationPunctuationFormatter.replacePhrase(currentText, target, replacement, replaceAll)
        if (result == null) {
            speak(context.getString(R.string.dictation_not_found, target))
            return true
        }

        undoHistory.push(currentText)
        val success = setTextAndCursor(targetNode, result.newText, result.newCursor, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_replaced, result.matchedTarget, result.finalReplacement))
        }
        return true
    }

    private fun undo(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        if (undoHistory.isEmpty()) return false
        val previous = undoHistory.pop()
        val success = setTextAndCursor(node, previous, previous.length, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_undo))
        }
        return success
    }

    private fun readCurrentText(node: AccessibilityNodeInfoCompat) {
        val currentText = AccessibilityNodeInfoUtils.getText(node)?.toString() ?: ""
        if (currentText.isBlank()) {
            speak(context.getString(R.string.dictation_empty_text))
        } else {
            speak(currentText)
        }
    }

    private fun setTextAndCursor(
        node: AccessibilityNodeInfoCompat,
        newText: CharSequence,
        cursorIndex: Int,
        eventId: EventId?
    ): Boolean {
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newText)
        val result = PerformActionUtils.performAction(node, AccessibilityNodeInfoCompat.ACTION_SET_TEXT, args, eventId)
        if (result && cursorIndex >= 0) {
            val selArgs = Bundle()
            selArgs.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, cursorIndex)
            selArgs.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, cursorIndex)
            PerformActionUtils.performAction(node, AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, selArgs, eventId)
        }
        return result
    }

    private fun scheduleStartListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({
            if (isDictationActive) {
                pipeline?.returnFeedback(
                    EVENT_ID_UNTRACKED,
                    Feedback.voiceRecognition(VoiceRecognition.Action.START_LISTENING, false)
                )
            }
        }, delayMs)
    }

    private fun speak(text: String) {
        val speakOptions = SpeakOptions.create()
            .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
        pipeline?.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, speakOptions))
    }
}
