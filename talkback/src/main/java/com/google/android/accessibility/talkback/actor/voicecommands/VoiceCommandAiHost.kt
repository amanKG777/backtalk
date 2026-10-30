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

package com.google.android.accessibility.talkback.actor.voicecommands

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Feedback.VoiceRecognition
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.TalkBackService
import com.google.android.accessibility.talkback.ai.AiHost
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.utils.AccessibilityNodeInfoUtils
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.PerformActionUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions

/**
 * Lets Backtalk AI work from the voice command layer, on whatever screen the user is on: it reads
 * the message the user is focused on and writes into the text box that has input focus. It keeps
 * the text from before the last AI edit so the user can say "undo".
 */
class VoiceCommandAiHost(
  private val service: TalkBackService,
  private val focusMonitor: AccessibilityFocusMonitor,
  private val pipelineProvider: () -> Pipeline.FeedbackReturner?,
) : AiHost {

  private val handler = Handler(Looper.getMainLooper())
  private var undoText: String? = null
  private var undoCursor = 0

  override fun speak(text: String) {
    val options =
      SpeakOptions.create()
        .setFlags(FeedbackItem.FLAG_NO_HISTORY or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL)
    pipelineProvider()?.returnFeedback(EVENT_ID_UNTRACKED, Feedback.speech(text, options))
  }

  override fun currentText(): String = editableNode()?.let { textOf(it) } ?: ""

  override fun selection(): Pair<Int, Int> {
    val node = editableNode() ?: return 0 to 0
    val length = textOf(node).length
    return node.textSelectionStart.coerceIn(0, length) to node.textSelectionEnd.coerceIn(0, length)
  }

  override fun lastDictated(): IntRange? = null

  override fun replaceRange(start: Int, end: Int, newText: String) {
    val node = editableNode()
    if (node == null) {
      speak(service.getString(R.string.ai_no_text_box))
      return
    }
    val text = textOf(node)
    val s = start.coerceIn(0, text.length)
    val e = end.coerceIn(s, text.length)
    undoText = text
    undoCursor = node.textSelectionEnd.coerceIn(0, text.length)
    setText(node, text.substring(0, s) + newText + text.substring(e), s + newText.length)
  }

  override fun readOnScreenMessage(): String? {
    val focus = focusMonitor.getAccessibilityFocus(false) ?: return null
    if (focus.isEditable) return null
    val own = nodeText(focus)
    if (own.isNotBlank()) return own
    val parent = focus.parent ?: return null
    return subtreeText(parent).ifBlank { null }
  }

  override fun listenForAnswer() = waitForQuiet(0L, 0)

  /** Whether the last AI edit can still be undone. */
  fun canUndo(): Boolean = undoText != null

  /** Puts back the text from before the last AI edit. */
  fun undo() {
    val previous = undoText ?: return
    val node = editableNode()
    if (node == null) {
      speak(service.getString(R.string.ai_no_text_box))
      return
    }
    undoText = null
    setText(node, previous, undoCursor.coerceIn(0, previous.length))
    speak(service.getString(R.string.ai_undone))
  }

  /** Reopens the command recognizer once TalkBack has been silent for a moment. */
  private fun waitForQuiet(waitedMs: Long, quietPolls: Int) {
    val busy = service.speechController.isSpeakingOrSpeechQueued()
    val quiet = if (busy) 0 else quietPolls + 1
    if (quiet >= REQUIRED_QUIET_POLLS || waitedMs >= WATCHDOG_MS) {
      pipelineProvider()
        ?.returnFeedback(
          EVENT_ID_UNTRACKED, Feedback.voiceRecognition(VoiceRecognition.Action.START_LISTENING, false))
      return
    }
    handler.postDelayed({ waitForQuiet(waitedMs + POLL_MS, quiet) }, POLL_MS)
  }

  /** The text box with input focus, else the one TalkBack's focus edits. */
  private fun editableNode(): AccessibilityNodeInfoCompat? {
    val input = service.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    if (input != null) {
      val compat = AccessibilityNodeInfoCompat.wrap(input)
      if (compat.isEditable) return compat
    }
    val focus = focusMonitor.getAccessibilityFocus(true) ?: return null
    return focusMonitor.getNodeForEditingActions(focus)
  }

  private fun textOf(node: AccessibilityNodeInfoCompat): String =
    AccessibilityNodeInfoUtils.getText(node)?.toString() ?: ""

  private fun nodeText(node: AccessibilityNodeInfoCompat): String =
    AccessibilityNodeInfoUtils.getText(node)?.toString()?.takeIf { it.isNotBlank() }
      ?: node.contentDescription?.toString().orEmpty()

  private fun subtreeText(root: AccessibilityNodeInfoCompat): String {
    val parts = mutableListOf<String>()
    var visited = 0
    fun walk(node: AccessibilityNodeInfoCompat) {
      if (visited++ >= MAX_NODES) return
      val t = nodeText(node)
      if (t.isNotBlank() && !node.isEditable) parts += t
      for (i in 0 until node.childCount) node.getChild(i)?.let { walk(it) }
    }
    walk(root)
    return parts.joinToString(". ")
  }

  private fun setText(node: AccessibilityNodeInfoCompat, text: String, cursor: Int) {
    val args = Bundle()
    args.putCharSequence(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    val ok =
      PerformActionUtils.performAction(
        node, AccessibilityNodeInfoCompat.ACTION_SET_TEXT, args, EVENT_ID_UNTRACKED)
    if (ok) {
      val sel = Bundle()
      sel.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
      sel.putInt(AccessibilityNodeInfoCompat.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
      PerformActionUtils.performAction(
        node, AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, sel, EVENT_ID_UNTRACKED)
    }
  }

  private companion object {
    const val POLL_MS = 150L
    const val REQUIRED_QUIET_POLLS = 3
    const val WATCHDOG_MS = 15000L
    const val MAX_NODES = 30
  }
}
