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

import android.content.Context
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.gemini.GeminiAiTextClient
import com.google.android.accessibility.utils.SharedPreferencesUtils

/** The strings Backtalk AI speaks, from resources. */
class AndroidAiStrings(private val context: Context) : AiStrings {
  override fun consentPrompt() = context.getString(R.string.ai_consent_prompt)

  override fun cancelled() = context.getString(R.string.ai_cancelled)

  override fun nothingToChange() = context.getString(R.string.ai_nothing_to_change)

  override fun preview(text: String) = context.getString(R.string.ai_preview, text)

  override fun applied() = context.getString(R.string.ai_applied)

  override fun notSetUp() = context.getString(R.string.ai_not_set_up)

  override fun networkError() = context.getString(R.string.ai_network_error)

  override fun emptyAnswer() = context.getString(R.string.ai_empty_answer)

  override fun otherError() = context.getString(R.string.ai_other_error)

  override fun askReplyIntent() = context.getString(R.string.ai_ask_reply_intent)

  override fun noMessage() = context.getString(R.string.ai_no_message)
}

/** Remembers the user's one-time agreement to send text to the AI in shared preferences. */
class PrefsAiConsentStore(private val context: Context) : AiConsentStore {
  override var granted: Boolean
    get() = SharedPreferencesUtils.getSharedPreferences(context).getBoolean(KEY, false)
    set(value) {
      SharedPreferencesUtils.getSharedPreferences(context).edit().putBoolean(KEY, value).apply()
    }

  private companion object {
    const val KEY = "pref_backtalk_ai_consent"
  }
}

/** Builds a controller wired to the user's real AI setup. [host] supplies the text field. */
fun createBacktalkAi(context: Context, host: AiHost): BacktalkAiController =
  BacktalkAiController(
    host, GeminiAiTextClient(context), PrefsAiConsentStore(context), AndroidAiStrings(context))
