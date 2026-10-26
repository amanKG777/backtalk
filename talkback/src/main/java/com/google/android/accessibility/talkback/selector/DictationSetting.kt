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

package com.google.android.accessibility.talkback.selector

import android.content.Context
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import com.google.android.accessibility.talkback.focusmanagement.AccessibilityFocusMonitor
import com.google.android.accessibility.talkback.selector.SelectorController.ContextualSetting
import com.google.android.accessibility.talkback.selector.SelectorController.Setting

/**
 * Contextual reading control setting for Dictation Mode.
 * Only supported and displayed when the focused element is an editable text box.
 */
class DictationSetting(
    private val accessibilityFocusMonitor: AccessibilityFocusMonitor
) : ContextualSetting {

    override fun getSetting(): Setting = Setting.DICTATION

    override fun isNodeSupportSetting(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
        var targetNode = node
        if (targetNode == null) {
            targetNode = accessibilityFocusMonitor.getAccessibilityFocus(true)
        }
        if (targetNode == null) {
            return false
        }
        return accessibilityFocusMonitor.getNodeForEditingActions(targetNode) != null
    }

    override fun shouldActivateSetting(context: Context, node: AccessibilityNodeInfoCompat?): Boolean {
        return false
    }
}
