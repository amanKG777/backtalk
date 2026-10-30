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

/** Where Backtalk AI reads text from, speaks to, and writes results to. */
interface AiHost {
  fun speak(text: String)

  fun currentText(): String

  /** The selection as (start, end); equal values mean a cursor. */
  fun selection(): Pair<Int, Int>

  /** The range of the last dictated text, if any. */
  fun lastDictated(): IntRange?

  /** Replaces [start, end) with [newText], recording undo. */
  fun replaceRange(start: Int, end: Int, newText: String)

  /** The text of the message on screen that "reply to this" refers to. */
  fun readOnScreenMessage(): String?

  /** Reopens the microphone, once TalkBack is quiet, for the user's spoken answer. */
  fun listenForAnswer()
}

/** Remembers whether the user agreed to send their text to the AI. */
interface AiConsentStore {
  var granted: Boolean
}

/** Everything Backtalk AI says aloud. */
interface AiStrings {
  fun consentPrompt(): String

  fun cancelled(): String

  fun nothingToChange(): String

  fun preview(text: String): String

  fun applied(): String

  fun notSetUp(): String

  fun networkError(): String

  fun emptyAnswer(): String

  fun otherError(): String

  fun askReplyIntent(): String

  fun noMessage(): String
}

/**
 * Runs one Backtalk AI exchange: consent, the AI call, a spoken preview, and the user's yes or no.
 * Nothing is sent before consent and nothing is written to the field before a yes.
 */
class BacktalkAiController(
  private val host: AiHost,
  private val client: AiTextClient,
  private val consent: AiConsentStore,
  private val strings: AiStrings,
) {

  private sealed class State {
    object Idle : State()

    object Working : State()

    data class AwaitingConsent(val request: AiRequest) : State()

    data class AwaitingReplyIntent(val message: String, val target: TextTarget) : State()

    data class AwaitingApply(val target: TextTarget, val result: String) : State()
  }

  private var state: State = State.Idle
  private var generation = 0

  val isAwaitingAnswer: Boolean
    get() = state !is State.Idle && state !is State.Working

  fun handle(request: AiRequest) {
    cancel()
    if (!client.isAvailable()) {
      host.speak(strings.notSetUp())
      return
    }
    if (!consent.granted) {
      state = State.AwaitingConsent(request)
      host.speak(strings.consentPrompt())
      host.listenForAnswer()
      return
    }
    run(request)
  }

  /** Returns true if [utterance] was an answer to a pending question. */
  fun onAnswer(utterance: String): Boolean {
    val said = normalise(utterance)
    return when (val s = state) {
      State.Idle,
      State.Working -> false
      is State.AwaitingConsent ->
        when {
          said in YES -> {
            consent.granted = true
            state = State.Idle
            run(s.request)
            true
          }
          said in NO -> decline()
          else -> dropPending()
        }
      is State.AwaitingReplyIntent ->
        if (said in NO) {
          decline()
        } else {
          send(
            AiRequest.ReplyToThis,
            "",
            s.target,
            ReplyContext(s.message, utterance.trim()),
          )
          true
        }
      is State.AwaitingApply ->
        when {
          said in YES -> {
            state = State.Idle
            host.replaceRange(s.target.start, s.target.end, s.result)
            host.speak(strings.applied())
            true
          }
          said in NO -> decline()
          else -> dropPending()
        }
    }
  }

  /** Forgets any pending question; a late AI answer is ignored. */
  fun cancel() {
    generation++
    state = State.Idle
  }

  private fun run(request: AiRequest) {
    val text = host.currentText()
    val (selStart, selEnd) = host.selection()
    if (request == AiRequest.ReplyToThis) {
      val message = host.readOnScreenMessage()?.takeIf { it.isNotBlank() }
      if (message == null) {
        host.speak(strings.noMessage())
        return
      }
      val a = selStart.coerceIn(0, text.length)
      val b = selEnd.coerceIn(0, text.length)
      state = State.AwaitingReplyIntent(message, TextTarget(minOf(a, b), maxOf(a, b)))
      host.speak(strings.askReplyIntent())
      host.listenForAnswer()
      return
    }
    val target = AiTargetResolver.resolve(text, selStart, selEnd, host.lastDictated())
    if (target == null) {
      host.speak(strings.nothingToChange())
      return
    }
    send(request, text.substring(target.start, target.end), target, null)
  }

  private fun send(request: AiRequest, source: String, target: TextTarget, reply: ReplyContext?) {
    state = State.Working
    val mine = ++generation
    client.complete(AiPromptBuilder.build(request, source, reply)) { result ->
      if (mine != generation) return@complete
      when (result) {
        is AiResult.Success -> {
          state = State.AwaitingApply(target, result.text)
          host.speak(strings.preview(result.text))
          host.listenForAnswer()
        }
        is AiResult.Failure -> {
          state = State.Idle
          host.speak(
            when (result.reason) {
              AiResult.Reason.NOT_SET_UP -> strings.notSetUp()
              AiResult.Reason.NETWORK -> strings.networkError()
              AiResult.Reason.EMPTY -> strings.emptyAnswer()
              AiResult.Reason.OTHER -> strings.otherError()
            })
        }
      }
    }
  }

  private fun decline(): Boolean {
    state = State.Idle
    host.speak(strings.cancelled())
    return true
  }

  private fun dropPending(): Boolean {
    state = State.Idle
    return false
  }

  private fun normalise(s: String) =
    s.lowercase().replace(Regex("[^a-z' ]"), " ").replace(Regex("\\s+"), " ").trim()

  private companion object {
    val YES = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "apply", "do it", "confirm", "go ahead")
    val NO = setOf("no", "nope", "cancel", "stop", "never mind", "nevermind", "dont", "don't")
  }
}
