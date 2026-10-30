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

import android.content.Context
import com.google.android.accessibility.talkback.actor.gemini.DataFieldUtils.GeminiResponse
import com.google.android.accessibility.talkback.actor.gemini.GeminiRestRequestPerformer.GeminiRestResponseCallback
import com.google.android.accessibility.talkback.ai.AiResult
import com.google.android.accessibility.talkback.ai.AiTextClient
import com.google.android.accessibility.utils.SharedPreferencesUtils
import org.json.JSONArray
import org.json.JSONObject

/**
 * Text-only AI requests for Backtalk's spoken AI editing. Uses the AI service the user set up in
 * Automatic descriptions settings if there is one, otherwise Gemini with the user's own API key.
 * (The key built into the app is not used here: it is for screen descriptions.)
 */
class GeminiAiTextClient(private val context: Context) : AiTextClient {

  private val prefs
    get() = SharedPreferencesUtils.getSharedPreferences(context)

  private val geminiPerformer by lazy { GeminiRestRequestPerformer(context) }
  private val servicePerformer by lazy { AiServiceRequestPerformer(context) }

  override fun isAvailable(): Boolean =
    AiService.isSetUp(prefs) || GeminiApiKey.userKey(prefs).isNotEmpty()

  override fun complete(prompt: String, callback: (AiResult) -> Unit) {
    val body = textRequest(prompt)
    val responseCallback =
      object : GeminiRestResponseCallback {
        override fun onResponse(response: GeminiResponse) =
          callback(toResult(response.finishReason(), response.text()))

        override fun onFailure(failure: GeminiFailure) =
          callback(AiResult.Failure(failureReason(failure.kind)))

        override fun onCancelled() = callback(AiResult.Failure(AiResult.Reason.OTHER))
      }
    val p = prefs
    when {
      AiService.isSetUp(p) ->
        servicePerformer.performRequest(
          AiService.address(p), AiService.model(p), AiService.apiKey(p), body, responseCallback)
      GeminiApiKey.userKey(p).isNotEmpty() ->
        geminiPerformer.performRequest(
          GEMINI_URL + GeminiConfiguration.getGeminiModel(context) + ":generateContent",
          GeminiApiKey.userKey(p),
          body,
          responseCallback)
      else -> callback(AiResult.Failure(AiResult.Reason.NOT_SET_UP))
    }
  }

  private companion object {
    const val GEMINI_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
  }
}

internal fun textRequest(prompt: String): JSONObject =
  JSONObject()
    .put(
      "contents",
      JSONArray()
        .put(
          JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))

internal fun failureReason(kind: GeminiFailure.Kind): AiResult.Reason =
  when (kind) {
    GeminiFailure.Kind.NO_CONNECTION,
    GeminiFailure.Kind.TIMEOUT -> AiResult.Reason.NETWORK
    GeminiFailure.Kind.API_KEY -> AiResult.Reason.NOT_SET_UP
    else -> AiResult.Reason.OTHER
  }

internal fun toResult(finishReason: String?, text: String?): AiResult =
  when {
    text.isNullOrBlank() -> AiResult.Failure(AiResult.Reason.EMPTY)
    finishReason == DataFieldUtils.FINISH_REASON_STOP -> AiResult.Success(text.trim())
    else -> AiResult.Failure(AiResult.Reason.OTHER)
  }
