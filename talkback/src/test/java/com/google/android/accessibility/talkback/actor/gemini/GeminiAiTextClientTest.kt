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

package com.google.android.accessibility.talkback.actor.gemini

import com.google.android.accessibility.talkback.ai.AiResult
import org.junit.Assert.assertEquals
import org.junit.Test

class GeminiAiTextClientTest {

  @Test
  fun failureReason_networkProblems() {
    assertEquals(AiResult.Reason.NETWORK, failureReason(GeminiFailure.Kind.NO_CONNECTION))
    assertEquals(AiResult.Reason.NETWORK, failureReason(GeminiFailure.Kind.TIMEOUT))
  }

  @Test
  fun failureReason_badKeyCountsAsNotSetUp() {
    assertEquals(AiResult.Reason.NOT_SET_UP, failureReason(GeminiFailure.Kind.API_KEY))
  }

  @Test
  fun failureReason_everythingElseIsOther() {
    assertEquals(AiResult.Reason.OTHER, failureReason(GeminiFailure.Kind.QUOTA))
    assertEquals(AiResult.Reason.OTHER, failureReason(GeminiFailure.Kind.BUSY))
    assertEquals(AiResult.Reason.OTHER, failureReason(GeminiFailure.Kind.OTHER))
  }

  @Test
  fun toResult_successTrimsText() {
    assertEquals(AiResult.Success("Hello."), toResult(DataFieldUtils.FINISH_REASON_STOP, "  Hello.\n"))
  }

  @Test
  fun toResult_blankAnswerIsEmpty() {
    assertEquals(AiResult.Failure(AiResult.Reason.EMPTY), toResult(DataFieldUtils.FINISH_REASON_STOP, "   "))
    assertEquals(AiResult.Failure(AiResult.Reason.EMPTY), toResult(DataFieldUtils.FINISH_REASON_STOP, null))
  }

  @Test
  fun toResult_blockedOrOtherFinishIsOther() {
    assertEquals(AiResult.Failure(AiResult.Reason.OTHER), toResult("SAFETY", "text"))
  }
}
