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
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandFuzzyMatcher
import com.google.android.accessibility.talkback.ai.AiHost
import com.google.android.accessibility.talkback.ai.AiRequestParser
import com.google.android.accessibility.talkback.ai.createBacktalkAi
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.utils.SpeechRecognizerPerformer
import com.google.android.accessibility.talkback.utils.SpeechRecognizerPerformer.SpeechRecognizerRequester
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.Performance.EventId
import com.google.android.accessibility.utils.PerformActionUtils
import com.google.android.accessibility.utils.SharedPreferencesUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

/**
 * Controller for Backtalk's Dictation Mode.
 *
 * Provides:
 * - Hands-free continuous dictation session directly into focused text fields
 * - Spoken punctuation & symbol parsing via [DictationPunctuationFormatter]
 * - Spoken editing commands parsed by [DictationCommandParser] and applied with [DictationTextEditor]
 * - Multi-level undo ([DictationUndoStack]) and optional auto punctuation
 * - Its own speech recognizer session: dictation never goes through the voice command layer
 *   ([VoiceCommandActor]/[VoiceCommandProcessor]) except for the "dictate" command that starts it
 * - Reading Controls (Selector) toggle integration
 */
class DictationController(
    private val context: Context,
    private val accessibilityFocusMonitor: AccessibilityFocusMonitor
) {
    companion object {
        private const val TAG = "DictationController"
        private const val RESTART_DELAY_MS = 350L
        private const val MAX_SILENCE_TIMEOUTS = 5
        private const val QUIET_POLL_MS = 150L
        private const val REQUIRED_QUIET_POLLS = 3
        private const val MAX_RECOGNITION_MS = 20000
        private const val SPEECH_WATCHDOG_MS = 15000L
        private const val PREF_AUTO_PUNCTUATION = "pref_dictation_auto_punctuation"

        private val CONFIRM_WORDS = setOf("yes", "yeah", "yep", "confirm", "do it")
        private val DENY_WORDS = setOf("no", "nope", "cancel", "don t", "dont")
    }

    private var pipeline: Pipeline.FeedbackReturner? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    var isDictationActive: Boolean = false
        private set

    private val undoStack = DictationUndoStack()
    private var silenceTimeoutCount = 0
    private var pendingClear = false
    private var bulletMode = false
    private var speechInFlight = 0

    /** Receives results and events from the dictation recognizer only. */
    private val recognitionListener = object : SpeechRecognizerRequester {
        override fun onResult(command: String, isPartialResult: Boolean): Boolean {
            if (!isPartialResult) handleDictationSpeech(command, false)
            return false
        }

        override fun onError(error: Int) = onRecognitionError(error)

        override fun onFeedbackEvent(eventId: Int) {
            when (eventId) {
                SpeechRecognizerRequester.EVENT_PLATFORM_NOT_SUPPORT,
                SpeechRecognizerRequester.EVENT_FEATURE_UNAVAILABLE,
                SpeechRecognizerRequester.EVENT_OPERATION_FORBIDDEN_DURING_SUW -> {
                    stopDictation(EVENT_ID_UNTRACKED, userInitiated = false)
                    speak(context.getString(R.string.voice_commands_no_action))
                }
                SpeechRecognizerRequester.EVENT_MIC_PERMISSION_NOT_GRANTED -> {
                    stopDictation(EVENT_ID_UNTRACKED, userInitiated = false)
                    speak(context.getString(R.string.voice_commands_no_mic_permissions))
                }
                // Unlike voice commands, dictation never silences or restores TalkBack speech.
                else -> Unit
            }
        }
    }

    private val recognizer =
        SpeechRecognizerPerformer(context, MAX_RECOGNITION_MS, false).also {
            it.setListener(recognitionListener)
        }

    /** The range of the text most recently dictated, so "that" can mean it. Null after any edit. */
    private var lastDictatedRange: IntRange? = null

    /** Lets Backtalk AI read and edit the field that dictation is typing into. */
    private val aiHost = object : AiHost {
        override fun speak(text: String) = this@DictationController.speak(text)

        override fun currentText(): String =
            getTargetEditableNode()?.let { currentText(it) } ?: ""

        override fun selection(): Pair<Int, Int> {
            val node = getTargetEditableNode() ?: return 0 to 0
            val length = currentText(node).length
            return node.textSelectionStart.coerceIn(0, length) to
                node.textSelectionEnd.coerceIn(0, length)
        }

        override fun lastDictated(): IntRange? = lastDictatedRange

        override fun replaceRange(start: Int, end: Int, newText: String) {
            val node = getTargetEditableNode() ?: return
            val text = currentText(node)
            val s = start.coerceIn(0, text.length)
            val e = end.coerceIn(s, text.length)
            pushUndo(text, getCursorPosition(node, text), R.string.dictation_label_ai)
            setTextAndCursor(
                node, text.substring(0, s) + newText + text.substring(e), s + newText.length,
                EVENT_ID_UNTRACKED)
        }

        // Dictation has no on-screen message; "reply to this" belongs to the voice command layer.
        override fun readOnScreenMessage(): String? = null

        override fun listenForAnswer() = scheduleStartListening(RESTART_DELAY_MS)
    }

    private val ai = createBacktalkAi(context, aiHost)

    /** Reports whether TalkBack as a whole is speaking or has speech queued. */
    private var isTalkBackSpeaking: () -> Boolean = { false }

    fun setTalkBackSpeaking(provider: () -> Boolean) {
        isTalkBackSpeaking = provider
    }

    fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
        this.pipeline = pipeline
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
        pendingClear = false
        bulletMode = false
        speechInFlight = 0
        undoStack.clear()
        lastDictatedRange = null
        ai.cancel()

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
        pendingClear = false
        bulletMode = false
        undoStack.clear()
        lastDictatedRange = null
        ai.cancel()
        mainHandler.removeCallbacksAndMessages(null)

        recognizer.stopListening()

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

        // Backtalk AI: ignore speech while the AI is thinking, route an answer to a pending
        // question, or start a new "Backtalk, ..." request. Anything else carries on below.
        if (ai.isWorking) {
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }
        if (ai.isAwaitingAnswer && ai.onAnswer(raw)) {
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }
        AiRequestParser.parse(raw)?.let { request ->
            ai.handle(request)
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        // A pending "clear" consumes the next utterance only if it is a yes/no answer.
        val clearWasPending = pendingClear
        pendingClear = false
        if (clearWasPending && norm in DENY_WORDS) {
            speak(context.getString(R.string.dictation_clear_cancelled))
            scheduleStartListening(RESTART_DELAY_MS)
            return true
        }

        val rawParsed = DictationCommandParser.parse(raw)
        if (rawParsed == DictationCommand.Stop) {
            stopDictation(eventId, userInitiated = true)
            return true
        }

        val targetNode = getTargetEditableNode()
        if (targetNode == null) {
            stopDictation(eventId, userInitiated = false)
            speak(context.getString(R.string.dictation_not_editable))
            return true
        }

        // A phrase command whose phrase isn't in the text is ordinary dictation.
        val parsed = rawParsed?.takeIf { DictationCommandParser.appliesTo(it, currentText(targetNode)) }

        if (clearWasPending && norm in CONFIRM_WORDS) {
            clearAllText(targetNode, eventId)
        } else if (parsed != null) {
            runCommand(parsed, targetNode, eventId)
        } else {
            insertDictatedText(raw, targetNode, eventId)
        }
        scheduleStartListening(if (parsed == DictationCommand.ReadAll) 1000L else RESTART_DELAY_MS)
        return true
    }

    private fun runCommand(
        command: DictationCommand,
        node: AccessibilityNodeInfoCompat,
        eventId: EventId?
    ) {
        val text = currentText(node)
        val cursor = getCursorPosition(node, text)
        when (command) {
            DictationCommand.Stop -> Unit // handled by the caller
            DictationCommand.DeleteWord -> deleteLastWord(node, eventId)
            DictationCommand.DeleteChar -> deleteLastCharacter(node, eventId)
            DictationCommand.Clear -> {
                if (text.isEmpty()) {
                    speak(context.getString(R.string.dictation_empty_text))
                } else {
                    pendingClear = true
                    speak(context.getString(R.string.dictation_confirm_clear))
                }
            }
            DictationCommand.Undo -> undo(node, eventId)
            DictationCommand.NewLine -> {
                if (bulletMode) {
                    val r = DictationTextEditor.continueBullet(text, cursor)
                    if (r.feedback == EditFeedback.BulletEnded) bulletMode = false
                    applyEdit(node, text, cursor, r, R.string.dictation_label_edit, eventId)
                } else {
                    insertText(node, "\n", eventId)
                    speak(context.getString(R.string.dictation_new_line))
                }
            }
            DictationCommand.NewParagraph -> {
                insertText(node, "\n\n", eventId)
                speak(context.getString(R.string.dictation_new_paragraph))
            }
            DictationCommand.ReadAll -> readCurrentText(node)
            DictationCommand.ReadLastSentence ->
                speak(DictationTextEditor.lastSentence(text, cursor)
                    ?: context.getString(R.string.dictation_nothing_to_do))
            DictationCommand.ReadLastWord ->
                speak(DictationTextEditor.lastWord(text, cursor)
                    ?: context.getString(R.string.dictation_nothing_to_do))
            DictationCommand.FixPunctuation -> {
                val fixed = DictationPunctuationFormatter.fixPunctuation(text)
                if (fixed == text) {
                    speak(context.getString(R.string.dictation_punctuation_ok))
                } else {
                    val r = EditResult(fixed, fixed.length, EditFeedback.Changed(""))
                    applyEdit(node, text, cursor, r, R.string.dictation_label_edit, eventId)
                    speak(context.getString(R.string.dictation_punctuation_fixed))
                }
            }
            DictationCommand.CapitalizeThat -> applyEdit(node, text, cursor,
                DictationTextEditor.capitalizeThat(text, cursor), R.string.dictation_label_edit, eventId)
            DictationCommand.UpperCaseThat -> applyEdit(node, text, cursor,
                DictationTextEditor.upperCaseThat(text, cursor), R.string.dictation_label_edit, eventId)
            DictationCommand.LowerCaseThat -> applyEdit(node, text, cursor,
                DictationTextEditor.lowerCaseThat(text, cursor), R.string.dictation_label_edit, eventId)
            DictationCommand.BulletList -> {
                bulletMode = true
                val r = DictationTextEditor.startBullet(text, cursor)
                applyEdit(node, text, cursor, r, R.string.dictation_label_edit, eventId, speakResult = false)
                speak(context.getString(R.string.dictation_bullets_started))
            }
            DictationCommand.GoToStart -> applyEdit(node, text, cursor,
                DictationTextEditor.goToStart(text, cursor), R.string.dictation_label_edit, eventId)
            DictationCommand.GoToEnd -> applyEdit(node, text, cursor,
                DictationTextEditor.goToEnd(text, cursor), R.string.dictation_label_edit, eventId)
            DictationCommand.DeleteLastSentence -> applyEdit(node, text, cursor,
                DictationTextEditor.deleteLastSentence(text, cursor), R.string.dictation_label_delete, eventId)
            is DictationCommand.AutoPunctuation -> {
                SharedPreferencesUtils.getSharedPreferences(context).edit()
                    .putBoolean(PREF_AUTO_PUNCTUATION, command.on).apply()
                speak(context.getString(
                    if (command.on) R.string.dictation_auto_punct_on else R.string.dictation_auto_punct_off))
            }
            is DictationCommand.Move -> applyEdit(node, text, cursor,
                DictationTextEditor.moveWords(text, cursor, command.words), R.string.dictation_label_edit, eventId)
            is DictationCommand.CapitalizeWord -> applyEdit(node, text, cursor,
                DictationTextEditor.capitalizeWord(text, cursor, command.word), R.string.dictation_label_edit, eventId)
            is DictationCommand.DeletePhrase -> applyEdit(node, text, cursor,
                DictationTextEditor.deletePhrase(text, cursor, command.phrase), R.string.dictation_label_delete, eventId)
            is DictationCommand.InsertAround -> applyEdit(node, text, cursor,
                DictationTextEditor.insertAround(text, cursor, command.text, command.phrase, command.before),
                R.string.dictation_label_edit, eventId)
            is DictationCommand.Replace -> replacePhrase(node, text, command, eventId)
        }
    }

    private fun insertDictatedText(raw: String, node: AccessibilityNodeInfoCompat, eventId: EventId?) {
        val currentText = currentText(node)
        val cursor = getCursorPosition(node, currentText)
        val textBeforeCursor = if (cursor in 1..currentText.length) {
            currentText.substring(0, cursor)
        } else {
            currentText
        }

        var formattedText = DictationPunctuationFormatter.format(raw, textBeforeCursor)
        if (isAutoPunctuationEnabled()) {
            formattedText = DictationPunctuationFormatter.autoPunctuate(formattedText)
        }
        val spacing = DictationPunctuationFormatter.getPrefixSpacing(textBeforeCursor, formattedText)
        if (insertText(node, spacing + formattedText, eventId)) {
            val start = cursor + spacing.length
            lastDictatedRange = start until (start + formattedText.length)
            speak(formattedText.trim())
        }
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

    fun isDictationStopCommand(command: String): Boolean =
        DictationCommandParser.isStopCommand(command)

    private fun isAutoPunctuationEnabled(): Boolean =
        SharedPreferencesUtils.getSharedPreferences(context).getBoolean(PREF_AUTO_PUNCTUATION, false)

    private fun currentText(node: AccessibilityNodeInfoCompat): String =
        AccessibilityNodeInfoUtils.getText(node)?.toString() ?: ""

    private fun getCursorPosition(node: AccessibilityNodeInfoCompat, currentText: String): Int {
        val selEnd = node.textSelectionEnd
        return if (selEnd in 0..currentText.length) selEnd else currentText.length
    }

    private fun pushUndo(text: String, cursor: Int, labelRes: Int) {
        undoStack.push(Snapshot(text, cursor, context.getString(labelRes)))
        lastDictatedRange = null
    }

    /**
     * Applies an [EditResult]: records undo, writes text and cursor, and speaks the outcome.
     * Cursor-only results just move the selection.
     */
    private fun applyEdit(
        node: AccessibilityNodeInfoCompat,
        oldText: String,
        oldCursor: Int,
        result: EditResult,
        labelRes: Int,
        eventId: EventId?,
        speakResult: Boolean = true,
    ) {
        when (val fb = result.feedback) {
            is EditFeedback.NotFound -> {
                speak(context.getString(R.string.dictation_not_found, fb.what))
                return
            }
            EditFeedback.NothingToDo -> {
                speak(context.getString(R.string.dictation_nothing_to_do))
                return
            }
            else -> Unit
        }

        if (result.text != oldText) {
            pushUndo(oldText, oldCursor, labelRes)
            setTextAndCursor(node, result.text, result.cursor, eventId)
        } else {
            setSelection(node, result.cursor, eventId)
        }
        if (!speakResult) return
        when (val fb = result.feedback) {
            is EditFeedback.Inserted -> speak(context.getString(R.string.dictation_changed, fb.text))
            is EditFeedback.Deleted -> speak(context.getString(R.string.dictation_deleted_what, fb.what))
            is EditFeedback.Changed ->
                if (fb.what.isNotEmpty()) speak(context.getString(R.string.dictation_changed, fb.what))
            is EditFeedback.Moved -> speak(context.getString(R.string.dictation_moved, fb.description))
            EditFeedback.BulletEnded -> speak(context.getString(R.string.dictation_bullets_ended))
            else -> Unit
        }
    }

    private fun insertText(node: AccessibilityNodeInfoCompat, textToInsert: String, eventId: EventId?): Boolean {
        val currentText = currentText(node)
        val selStart = if (node.textSelectionStart in 0..currentText.length) node.textSelectionStart else currentText.length
        val selEnd = if (node.textSelectionEnd in 0..currentText.length) node.textSelectionEnd else currentText.length

        val start = minOf(selStart, selEnd)
        val end = maxOf(selStart, selEnd)
        pushUndo(currentText, start, R.string.dictation_label_typing)

        val newText = StringBuilder(currentText.substring(0, start))
            .append(textToInsert)
            .append(currentText.substring(end))
            .toString()

        val newCursor = start + textToInsert.length
        return setTextAndCursor(node, newText, newCursor, eventId)
    }

    private fun deleteLastWord(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = currentText(node)
        if (currentText.isEmpty()) {
            speak(context.getString(R.string.dictation_empty_text))
            return false
        }
        val cursor = getCursorPosition(node, currentText)
        val beforeCursor = currentText.substring(0, cursor)
        val afterCursor = currentText.substring(cursor)

        val trimmedBefore = beforeCursor.trimEnd()
        if (trimmedBefore.isEmpty()) {
            speak(context.getString(R.string.dictation_nothing_to_do))
            return false
        }
        pushUndo(currentText, cursor, R.string.dictation_label_delete)

        val lastSpace = trimmedBefore.lastIndexOfAny(charArrayOf(' ', '\t', '\n'))
        val newBefore = if (lastSpace >= 0) trimmedBefore.substring(0, lastSpace + 1) else ""
        val success = setTextAndCursor(node, newBefore + afterCursor, newBefore.length, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_deleted_word))
        }
        return success
    }

    private fun deleteLastCharacter(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = currentText(node)
        val cursor = getCursorPosition(node, currentText)
        if (currentText.isEmpty() || cursor <= 0) {
            speak(context.getString(R.string.dictation_nothing_to_do))
            return false
        }
        pushUndo(currentText, cursor, R.string.dictation_label_delete)

        val newText = currentText.substring(0, cursor - 1) + currentText.substring(cursor)
        val success = setTextAndCursor(node, newText, cursor - 1, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_char_deleted))
        }
        return success
    }

    private fun clearAllText(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val currentText = currentText(node)
        if (currentText.isNotEmpty()) {
            pushUndo(currentText, getCursorPosition(node, currentText), R.string.dictation_label_clear)
        }
        val success = setTextAndCursor(node, "", 0, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_cleared))
        }
        return success
    }

    private fun replacePhrase(
        targetNode: AccessibilityNodeInfoCompat,
        currentText: String,
        command: DictationCommand.Replace,
        eventId: EventId?
    ) {
        val result = DictationPunctuationFormatter.replacePhrase(
            currentText, command.target, command.replacement, command.all)
        if (result == null) {
            speak(context.getString(R.string.dictation_not_found, command.target))
            return
        }

        pushUndo(currentText, getCursorPosition(targetNode, currentText), R.string.dictation_label_edit)
        val success = setTextAndCursor(targetNode, result.newText, result.newCursor, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_replaced, result.matchedTarget, result.finalReplacement))
        }
    }

    private fun undo(node: AccessibilityNodeInfoCompat, eventId: EventId?): Boolean {
        val previous = undoStack.pop()
        if (previous == null) {
            speak(context.getString(R.string.dictation_nothing_to_undo))
            return false
        }
        val success = setTextAndCursor(node, previous.text, previous.cursor, eventId)
        if (success) {
            speak(context.getString(R.string.dictation_undid, previous.label))
        }
        return success
    }

    private fun readCurrentText(node: AccessibilityNodeInfoCompat) {
        val currentText = currentText(node)
        if (currentText.isBlank()) {
            speak(context.getString(R.string.dictation_empty_text))
        } else {
            speak(currentText)
        }
    }

    private fun setSelection(node: AccessibilityNodeInfoCompat, index: Int, eventId: EventId?) {
        val selArgs = Bundle()
        selArgs.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, index)
        selArgs.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, index)
        PerformActionUtils.performAction(node, AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, selArgs, eventId)
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
            setSelection(node, cursorIndex, eventId)
        }
        return result
    }

    /**
     * Restarts the recognizer after [delayMs], but only once TalkBack is completely quiet. Opening
     * the mic interrupts all TalkBack feedback (see VoiceActionMonitor), so starting it while
     * anything is still being spoken, ours or TalkBack's own text announcements, cuts speech off.
     */
    private fun scheduleStartListening(delayMs: Long) {
        mainHandler.removeCallbacksAndMessages(null)
        mainHandler.postDelayed({ startWhenQuiet(0L, 0) }, delayMs)
    }

    private fun startWhenQuiet(waitedMs: Long, quietPolls: Int) {
        if (!isDictationActive) return
        val busy = speechInFlight > 0 || isTalkBackSpeaking()
        val quiet = if (busy) 0 else quietPolls + 1
        if (quiet >= REQUIRED_QUIET_POLLS || waitedMs >= SPEECH_WATCHDOG_MS) {
            speechInFlight = 0
            recognizer.doListening()
            return
        }
        mainHandler.postDelayed({ startWhenQuiet(waitedMs + QUIET_POLL_MS, quiet) }, QUIET_POLL_MS)
    }

    private fun onSpeechFinished() {
        if (speechInFlight > 0) speechInFlight--
    }

    private fun speak(text: String) {
        speechInFlight++
        val speakOptions = SpeakOptions.create()
            .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
            .setCompletedAction { _ -> mainHandler.post { onSpeechFinished() } }
        pipeline?.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, speakOptions))
    }
}
