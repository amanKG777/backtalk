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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BacktalkAiControllerTest {

  private class FakeHost : AiHost {
    val spoken = mutableListOf<String>()
    var text = "this is long"
    var sel = 0 to 0
    var dictated: IntRange? = null
    var message: String? = null
    val replaced = mutableListOf<Triple<Int, Int, String>>()
    var listens = 0

    override fun speak(text: String) {
      spoken += text
    }

    override fun currentText() = text

    override fun selection() = sel

    override fun lastDictated() = dictated

    override fun replaceRange(start: Int, end: Int, newText: String) {
      replaced += Triple(start, end, newText)
    }

    override fun readOnScreenMessage() = message

    override fun listenForAnswer() {
      listens++
    }
  }

  private class FakeClient : AiTextClient {
    var available = true
    val prompts = mutableListOf<String>()
    private val callbacks = mutableListOf<(AiResult) -> Unit>()

    override fun isAvailable() = available

    override fun complete(prompt: String, callback: (AiResult) -> Unit) {
      prompts += prompt
      callbacks += callback
    }

    fun finish(result: AiResult, index: Int = callbacks.lastIndex) = callbacks[index](result)
  }

  private class FakeConsent(override var granted: Boolean) : AiConsentStore

  private object FakeStrings : AiStrings {
    override fun consentPrompt() = "CONSENT"

    override fun cancelled() = "CANCELLED"

    override fun nothingToChange() = "NOTHING"

    override fun preview(text: String) = "PREVIEW:$text"

    override fun applied() = "APPLIED"

    override fun notSetUp() = "NOT_SET_UP"

    override fun networkError() = "NETWORK"

    override fun emptyAnswer() = "EMPTY"

    override fun otherError() = "OTHER"

    override fun askReplyIntent() = "ASK_INTENT"

    override fun noMessage() = "NO_MESSAGE"
  }

  private val host = FakeHost()
  private val client = FakeClient()

  private fun controller(consented: Boolean = true) =
    BacktalkAiController(host, client, FakeConsent(consented), FakeStrings)

  @Test
  fun noConsent_asksFirst_thenRunsOnYes() {
    val consent = FakeConsent(false)
    val c = BacktalkAiController(host, client, consent, FakeStrings)
    c.handle(AiRequest.Rewrite("shorter"))
    assertEquals(listOf("CONSENT"), host.spoken)
    assertTrue(client.prompts.isEmpty())
    assertTrue(c.isAwaitingAnswer)

    assertTrue(c.onAnswer("yes"))
    assertTrue(consent.granted)
    assertEquals(1, client.prompts.size)
    assertTrue(client.prompts[0].contains("this is long"))
  }

  @Test
  fun consentDeclined_sendsNothing_andAsksAgainNextTime() {
    val consent = FakeConsent(false)
    val c = BacktalkAiController(host, client, consent, FakeStrings)
    c.handle(AiRequest.FixGrammar)
    assertTrue(c.onAnswer("no"))
    assertFalse(consent.granted)
    assertEquals("CANCELLED", host.spoken.last())
    assertTrue(client.prompts.isEmpty())
    assertFalse(c.isAwaitingAnswer)

    c.handle(AiRequest.FixGrammar)
    assertEquals("CONSENT", host.spoken.last())
  }

  @Test
  fun rewrite_previewsFirst_appliesOnlyOnYes() {
    val c = controller()
    c.handle(AiRequest.Rewrite("shorter"))
    client.finish(AiResult.Success("short"))
    assertEquals("PREVIEW:short", host.spoken.last())
    assertTrue(host.replaced.isEmpty())
    assertTrue(c.isAwaitingAnswer)

    assertTrue(c.onAnswer("yes"))
    assertEquals(listOf(Triple(0, 12, "short")), host.replaced)
    assertEquals("APPLIED", host.spoken.last())
    assertFalse(c.isAwaitingAnswer)
  }

  @Test
  fun rewrite_usesSelectionWhenPresent() {
    host.sel = 5 to 7
    val c = controller()
    c.handle(AiRequest.Rewrite("shorter"))
    client.finish(AiResult.Success("X"))
    c.onAnswer("yes")
    assertEquals(listOf(Triple(5, 7, "X")), host.replaced)
  }

  @Test
  fun no_keepsTheOriginalText() {
    val c = controller()
    c.handle(AiRequest.Rewrite("shorter"))
    client.finish(AiResult.Success("short"))
    assertTrue(c.onAnswer("no"))
    assertTrue(host.replaced.isEmpty())
    assertEquals("CANCELLED", host.spoken.last())
    assertFalse(c.isAwaitingAnswer)
  }

  @Test
  fun somethingElse_clearsPending_andIsNotConsumed() {
    val c = controller()
    c.handle(AiRequest.Rewrite("shorter"))
    client.finish(AiResult.Success("short"))
    assertFalse(c.onAnswer("what time is it"))
    assertFalse(c.isAwaitingAnswer)
    assertTrue(host.replaced.isEmpty())
  }

  @Test
  fun emptyField_saysNothingToChange_withoutCallingTheAi() {
    host.text = ""
    val c = controller()
    c.handle(AiRequest.FixGrammar)
    assertEquals("NOTHING", host.spoken.last())
    assertTrue(client.prompts.isEmpty())
  }

  @Test
  fun failures_areSpoken_andLeaveTextAlone() {
    val c = controller()
    val cases =
      listOf(
        AiResult.Reason.NETWORK to "NETWORK",
        AiResult.Reason.EMPTY to "EMPTY",
        AiResult.Reason.NOT_SET_UP to "NOT_SET_UP",
        AiResult.Reason.OTHER to "OTHER")
    for ((reason, spoken) in cases) {
      c.handle(AiRequest.FixGrammar)
      client.finish(AiResult.Failure(reason))
      assertEquals(spoken, host.spoken.last())
      assertFalse(c.isAwaitingAnswer)
    }
    assertTrue(host.replaced.isEmpty())
  }

  @Test
  fun aiNotAvailable_saysSoBeforeAskingConsent() {
    client.available = false
    val c = controller(consented = false)
    c.handle(AiRequest.FixGrammar)
    assertEquals(listOf("NOT_SET_UP"), host.spoken)
    assertFalse(c.isAwaitingAnswer)
  }

  @Test
  fun reply_asksIntent_thenPreviewsAndInsertsAtCursor() {
    host.text = ""
    host.sel = 0 to 0
    host.message = "Are you coming tonight?"
    val c = controller()
    c.handle(AiRequest.ReplyToThis)
    assertEquals("ASK_INTENT", host.spoken.last())
    assertTrue(client.prompts.isEmpty())

    assertTrue(c.onAnswer("tell him I will be late"))
    assertTrue(client.prompts[0].contains("Are you coming tonight?"))
    assertTrue(client.prompts[0].contains("tell him I will be late"))
    client.finish(AiResult.Success("Sorry, I will be late."))
    assertEquals("PREVIEW:Sorry, I will be late.", host.spoken.last())

    assertTrue(c.onAnswer("yes"))
    assertEquals(listOf(Triple(0, 0, "Sorry, I will be late.")), host.replaced)
  }

  @Test
  fun reply_withNoMessageOnScreen_saysSo() {
    host.message = null
    val c = controller()
    c.handle(AiRequest.ReplyToThis)
    assertEquals("NO_MESSAGE", host.spoken.last())
    assertFalse(c.isAwaitingAnswer)
  }

  @Test
  fun reply_cancelledWhileGivingIntent() {
    host.message = "hi"
    val c = controller()
    c.handle(AiRequest.ReplyToThis)
    assertTrue(c.onAnswer("never mind"))
    assertEquals("CANCELLED", host.spoken.last())
    assertTrue(client.prompts.isEmpty())
  }

  @Test
  fun cancel_ignoresALateAnswer() {
    val c = controller()
    c.handle(AiRequest.FixGrammar)
    c.cancel()
    client.finish(AiResult.Success("late"))
    assertFalse(host.spoken.contains("PREVIEW:late"))
    assertFalse(c.isAwaitingAnswer)
  }

  @Test
  fun aNewRequest_replacesAnUnansweredOne() {
    val c = controller()
    c.handle(AiRequest.Rewrite("shorter"))
    client.finish(AiResult.Success("short"))
    c.handle(AiRequest.FixGrammar)
    client.finish(AiResult.Success("fixed"))
    c.onAnswer("yes")
    assertEquals(listOf(Triple(0, 12, "fixed")), host.replaced)
  }
}
