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

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityNodeInfo
import com.google.android.accessibility.talkback.Feedback
import com.google.android.accessibility.talkback.Pipeline
import com.google.android.accessibility.talkback.R
import com.google.android.accessibility.talkback.actor.voicecommands.VoiceCommandActor.RECOGNITION_SPEECH_DELAY_MS
import com.google.android.accessibility.talkback.monitor.BatteryMonitor
import com.google.android.accessibility.talkback.preference.TalkBackPreferencesActivity
import com.google.android.accessibility.talkback.status.StatusReader
import com.google.android.accessibility.utils.Performance.EVENT_ID_UNTRACKED
import com.google.android.accessibility.utils.SettingsUtils
import com.google.android.accessibility.utils.output.FeedbackItem
import com.google.android.accessibility.utils.output.SpeechController.SpeakOptions
import com.google.android.libraries.accessibility.utils.log.LogUtils

/**
 * Handles system-level voice commands such as:
 * - Launching applications ("open [app]")
 * - Directly opening System Settings ("open settings")
 * - Opening Backtalk settings ("open backtalk settings")
 * - Toggling Do Not Disturb ("turn on/off dnd")
 * - Toggling Hotspot ("turn on/off hotspot")
 * - Toggling Mobile Data ("turn on/off mobile data")
 * - Toggling Wi-Fi and Bluetooth ("turn on/off wifi", "turn on/off bluetooth")
 * - Device status and battery inquiries ("battery", "status", "time")
 * - Volume adjustments ("volume up", "volume down", "mute", "unmute")
 */
class SystemVoiceCommands(
  private val context: Context,
  private var pipeline: Pipeline.FeedbackReturner? = null,
  var batteryMonitor: BatteryMonitor? = null,
  var statusReader: StatusReader? = null
) {

  private data class AppEntry(
    val label: String,
    val normalizedLabel: String,
    val packageName: String
  )

  private var cachedApps: List<AppEntry>? = null
  private var lastAppQueryTime: Long = 0L

  fun setPipeline(pipeline: Pipeline.FeedbackReturner?) {
    this.pipeline = pipeline
  }

  /**
   * Attempts to handle the spoken command as a system or app command.
   * Returns true if the command was recognized and handled.
   */
  fun handleCommand(command: String, isPartialResult: Boolean): Boolean {
    val norm = VoiceCommandFuzzyMatcher.normalize(command)
    if (norm.isBlank()) return false

    // 1. Backtalk Settings (requires explicit mention of Backtalk or TalkBack)
    if (handleBacktalkSettings(norm)) {
      return true
    }

    // 2. System Settings ("open settings", "settings")
    if (handleSystemSettings(norm)) {
      return true
    }

    // 3. Do Not Disturb
    if (handleDoNotDisturb(norm)) {
      return true
    }

    // 4. Hotspot
    if (handleHotspot(norm)) {
      return true
    }

    // 5. Mobile Data
    if (handleMobileData(norm)) {
      return true
    }

    // 6. Wi-Fi
    if (handleWifi(norm)) {
      return true
    }

    // 7. Bluetooth
    if (handleBluetooth(norm)) {
      return true
    }

    // 8. Battery & Device Status
    if (handleStatusAndBattery(norm)) {
      return true
    }

    // 9. Volume Controls
    if (handleVolume(norm)) {
      return true
    }

    // 10. Open / Launch App
    if (handleOpenApp(command, isPartialResult)) {
      return true
    }

    // 11. Click / Tap on screen element ("click play button", "tap submit", "activate next")
    if (handleClickOnScreen(norm, command)) {
      return true
    }

    // 12. Find / Focus on screen element ("find play button", "focus search", "locate cancel")
    if (handleFindOnScreen(norm, command)) {
      return true
    }

    return false
  }

  // --- Sub-handlers ---

  /**
   * Handles opening Backtalk settings. Strictly requires that the user said
   * "backtalk" or "talkback" so generic "settings" or "open settings" does not trigger this.
   */
  private fun handleBacktalkSettings(norm: String): Boolean {
    if (!norm.contains("backtalk") && !norm.contains("talkback")) {
      return false
    }

    val targets = listOf(
      "open backtalk settings",
      "backtalk settings",
      "backtalk setting",
      "open talkback settings",
      "talkback settings",
      "talkback setting"
    )
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, targets, threshold = 0.85)) {
      if (SettingsUtils.allowLinksOutOfSettings(context)) {
        speakDelayed(context.getString(R.string.voice_commands_opening_backtalk_settings))
        val intent = Intent(context, TalkBackPreferencesActivity::class.java).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      }
      return true
    }
    return false
  }

  /**
   * Handles opening the device's System Settings app when the user says "open settings",
   * "settings", "system settings", or "device settings".
   */
  private fun handleSystemSettings(norm: String): Boolean {
    val settingsTargets = listOf(
      "open settings",
      "settings",
      "launch settings",
      "start settings",
      "go to settings",
      "system settings",
      "device settings"
    )
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, settingsTargets, threshold = 0.88)) {
      speakDelayed(context.getString(R.string.voice_commands_opening_settings))
      val intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      return true
    }
    return false
  }

  private fun handleDoNotDisturb(norm: String): Boolean {
    val dndOnTargets = listOf(
      "turn on dnd",
      "turn on do not disturb",
      "enable dnd",
      "enable do not disturb",
      "dnd on",
      "do not disturb on"
    )
    val dndOffTargets = listOf(
      "turn off dnd",
      "turn off do not disturb",
      "disable dnd",
      "disable do not disturb",
      "dnd off",
      "do not disturb off"
    )

    val isOn = VoiceCommandFuzzyMatcher.matchesAny(norm, dndOnTargets, threshold = 0.85)
    val isOff = VoiceCommandFuzzyMatcher.matchesAny(norm, dndOffTargets, threshold = 0.85)

    if (!isOn && !isOff) return false

    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
    if (notificationManager != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && notificationManager.isNotificationPolicyAccessGranted) {
        if (isOn) {
          notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
          speakDelayed(context.getString(R.string.voice_commands_dnd_on))
        } else {
          notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
          speakDelayed(context.getString(R.string.voice_commands_dnd_off))
        }
      } else {
        speakDelayed(context.getString(R.string.voice_commands_dnd_permission_required))
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
      }
      return true
    }
    return false
  }

  private fun handleHotspot(norm: String): Boolean {
    val onTargets = listOf(
      "turn on hotspot", "enable hotspot", "start hotspot", "hotspot on",
      "turn on personal hotspot", "turn on portable hotspot", "personal hotspot on", "portable hotspot on"
    )
    val offTargets = listOf(
      "turn off hotspot", "disable hotspot", "stop hotspot", "hotspot off",
      "turn off personal hotspot", "turn off portable hotspot", "personal hotspot off", "portable hotspot off"
    )
    val isOn = VoiceCommandFuzzyMatcher.matchesAny(norm, onTargets, threshold = 0.85)
    val isOff = VoiceCommandFuzzyMatcher.matchesAny(norm, offTargets, threshold = 0.85)
    if (!isOn && !isOff) return false

    val targetState = isOn
    val intent = Intent("android.settings.TETHER_SETTINGS").apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    automateToggle(
      launchIntent = intent,
      keywords = listOf("Portable hotspot", "Personal hotspot", "Wi-Fi hotspot", "Hotspot"),
      targetState = targetState,
      turningAnnouncement = context.getString(if (targetState) R.string.voice_commands_turning_on_hotspot else R.string.voice_commands_turning_off_hotspot),
      successAnnouncement = context.getString(if (targetState) R.string.voice_commands_hotspot_on else R.string.voice_commands_hotspot_off),
      alreadyAnnouncement = context.getString(if (targetState) R.string.voice_commands_hotspot_already_on else R.string.voice_commands_hotspot_already_off),
      fallbackToFirstSwitch = true
    )
    return true
  }

  private fun handleMobileData(norm: String): Boolean {
    val onTargets = listOf(
      "turn on mobile data", "enable mobile data", "mobile data on",
      "turn on cellular data", "enable cellular data", "cellular data on"
    )
    val offTargets = listOf(
      "turn off mobile data", "disable mobile data", "mobile data off",
      "turn off cellular data", "disable cellular data", "cellular data off"
    )
    val isOn = VoiceCommandFuzzyMatcher.matchesAny(norm, onTargets, threshold = 0.85)
    val isOff = VoiceCommandFuzzyMatcher.matchesAny(norm, offTargets, threshold = 0.85)
    if (!isOn && !isOff) return false

    val targetState = isOn

    // Attempt direct API via reflection first
    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
    var directOk = false
    try {
      val method = telephonyManager?.javaClass?.getMethod("setDataEnabled", Boolean::class.javaPrimitiveType)
      method?.invoke(telephonyManager, targetState)
      directOk = true
    } catch (ignored: Throwable) {}

    if (directOk) {
      speakDelayed(context.getString(if (targetState) R.string.voice_commands_mobile_data_on else R.string.voice_commands_mobile_data_off))
      return true
    }

    // Automate toggle via Internet connectivity panel
    val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      Intent(Settings.ACTION_DATA_ROAMING_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }

    automateToggle(
      launchIntent = panelIntent,
      keywords = listOf("Mobile networks", "Mobile network", "Mobile data", "Cellular data", "Data connection"),
      targetState = targetState,
      turningAnnouncement = context.getString(if (targetState) R.string.voice_commands_turning_on_mobile_data else R.string.voice_commands_turning_off_mobile_data),
      successAnnouncement = context.getString(if (targetState) R.string.voice_commands_mobile_data_on else R.string.voice_commands_mobile_data_off),
      alreadyAnnouncement = context.getString(if (targetState) R.string.voice_commands_mobile_data_already_on else R.string.voice_commands_mobile_data_already_off),
      fallbackToFirstSwitch = false
    )
    return true
  }

  private fun handleWifi(norm: String): Boolean {
    val onTargets = listOf("turn on wifi", "enable wifi", "wifi on", "turn on wi-fi", "enable wi-fi", "wi-fi on")
    val offTargets = listOf("turn off wifi", "disable wifi", "wifi off", "turn off wi-fi", "disable wi-fi", "wi-fi off")
    val isOn = VoiceCommandFuzzyMatcher.matchesAny(norm, onTargets, threshold = 0.85)
    val isOff = VoiceCommandFuzzyMatcher.matchesAny(norm, offTargets, threshold = 0.85)
    if (!isOn && !isOff) return false

    val targetState = isOn
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    if (wifiManager != null && wifiManager.isWifiEnabled == targetState) {
      speakDelayed(context.getString(if (targetState) R.string.voice_commands_wifi_already_on else R.string.voice_commands_wifi_already_off))
      return true
    }

    // Attempt direct API first (supported on pre-Q or when granted)
    @Suppress("DEPRECATION")
    val directSuccess = wifiManager?.setWifiEnabled(targetState) ?: false
    if (directSuccess) {
      speakDelayed(context.getString(if (targetState) R.string.voice_commands_wifi_on else R.string.voice_commands_wifi_off))
      return true
    }

    // Automate toggle via Internet connectivity panel
    val panelIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    } else {
      Intent(Settings.ACTION_WIFI_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    }

    automateToggle(
      launchIntent = panelIntent,
      keywords = listOf("Wi-Fi", "Wifi"),
      targetState = targetState,
      turningAnnouncement = context.getString(if (targetState) R.string.voice_commands_turning_on_wifi else R.string.voice_commands_turning_off_wifi),
      successAnnouncement = context.getString(if (targetState) R.string.voice_commands_wifi_on else R.string.voice_commands_wifi_off),
      alreadyAnnouncement = context.getString(if (targetState) R.string.voice_commands_wifi_already_on else R.string.voice_commands_wifi_already_off),
      fallbackToFirstSwitch = false
    )
    return true
  }

  private fun handleBluetooth(norm: String): Boolean {
    val onTargets = listOf("turn on bluetooth", "enable bluetooth", "bluetooth on", "start bluetooth")
    val offTargets = listOf("turn off bluetooth", "disable bluetooth", "bluetooth off", "stop bluetooth")

    val isOn = VoiceCommandFuzzyMatcher.matchesAny(norm, onTargets, threshold = 0.85)
    val isOff = VoiceCommandFuzzyMatcher.matchesAny(norm, offTargets, threshold = 0.85)
    if (!isOn && !isOff) return false

    val adapter = BluetoothAdapter.getDefaultAdapter()
    if (adapter != null) {
      if (isOn) {
        if (!adapter.isEnabled) {
          speakDelayed(context.getString(R.string.voice_commands_turning_on_bluetooth))
          adapter.enable()
        } else {
          speakDelayed(context.getString(R.string.voice_commands_bluetooth_already_on))
        }
      } else {
        if (adapter.isEnabled) {
          speakDelayed(context.getString(R.string.voice_commands_turning_off_bluetooth))
          adapter.disable()
        } else {
          speakDelayed(context.getString(R.string.voice_commands_bluetooth_already_off))
        }
      }
      return true
    }
    return false
  }

  /**
   * Automates toggling a settings switch node using AccessibilityService capabilities,
   * then dismisses the settings screen immediately to return the user to their active task.
   */
  private fun automateToggle(
    launchIntent: Intent,
    keywords: List<String>,
    targetState: Boolean,
    turningAnnouncement: String,
    successAnnouncement: String,
    alreadyAnnouncement: String,
    fallbackToFirstSwitch: Boolean = false
  ) {
    speakDelayed(turningAnnouncement)

    try {
      context.startActivity(launchIntent)
    } catch (e: Exception) {
      LogUtils.e("SystemVoiceCommands", "Failed to launch intent: %s", e)
      return
    }

    val handler = Handler(Looper.getMainLooper())
    val startTime = SystemClock.uptimeMillis()

    val poller = object : Runnable {
      override fun run() {
        val service = context as? AccessibilityService
        val root = service?.rootInActiveWindow

        if (service != null && root != null) {
          val switchNode = findSwitchForKeywords(root, keywords)
            ?: if (fallbackToFirstSwitch) findFirstSwitch(root) else null

          if (switchNode != null) {
            val currentState = switchNode.isChecked
            if (currentState != targetState) {
              clickNode(switchNode)
              speakDelayed(successAnnouncement)
            } else {
              speakDelayed(alreadyAnnouncement)
            }

            // Dismiss the window after switch is triggered
            handler.postDelayed({
              val currentRoot = service.rootInActiveWindow ?: root
              dismissWindow(service, currentRoot)
            }, 120)
            return
          }
        }

        // Continue polling for up to 2.5 seconds
        if (SystemClock.uptimeMillis() - startTime < 2500) {
          handler.postDelayed(this, 80)
        }
      }
    }

    // Give the window manager a tiny initial delay before first check
    handler.postDelayed(poller, 120)
  }

  private fun findSwitchForKeywords(
    root: AccessibilityNodeInfo,
    keywords: List<String>
  ): AccessibilityNodeInfo? {
    for (kw in keywords) {
      val matches = root.findAccessibilityNodeInfosByText(kw)
      for (node in matches) {
        val nodeText = node.text?.toString() ?: ""
        if (keywords.any { nodeText.contains(it, ignoreCase = true) }) {
          var ancestor: AccessibilityNodeInfo? = node
          for (depth in 0..3) {
            ancestor = ancestor?.parent ?: break
            val sw = findCheckableDescendant(ancestor)
            if (sw != null) return sw
          }
        }
      }
    }
    return null
  }

  private fun findCheckableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (isSwitchOrCheckable(node)) {
      return node
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = findCheckableDescendant(child)
      if (found != null) return found
    }
    return null
  }

  private fun isSwitchOrCheckable(node: AccessibilityNodeInfo): Boolean {
    val cls = node.className?.toString() ?: ""
    return node.isCheckable || cls.contains("Switch") || cls.contains("CheckBox") || cls.contains("CompoundButton")
  }

  private fun findFirstSwitch(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (isSwitchOrCheckable(node)) {
      return node
    }
    for (i in 0 until node.childCount) {
      val child = node.getChild(i) ?: continue
      val found = findFirstSwitch(child)
      if (found != null) return found
    }
    return null
  }

  private fun clickNode(node: AccessibilityNodeInfo): Boolean {
    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
      return true
    }
    var parent = node.parent
    while (parent != null) {
      if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        return true
      }
      parent = parent.parent
    }
    return false
  }

  private fun dismissWindow(service: AccessibilityService, root: AccessibilityNodeInfo?) {
    if (root != null) {
      val doneButtons = root.findAccessibilityNodeInfosByViewId("com.android.settings:id/done")
      if (!doneButtons.isNullOrEmpty() && doneButtons[0].performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
        return
      }
      val doneByText = root.findAccessibilityNodeInfosByText("Done")
      for (d in doneByText) {
        if (d.isClickable && d.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
          return
        }
      }
    }
    service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
  }

  private fun handleStatusAndBattery(norm: String): Boolean {
    val batteryTargets = listOf("battery", "battery level", "how much battery", "check battery", "battery status")
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, batteryTargets, threshold = 0.85)) {
      val desc = batteryMonitor?.batteryStateDescription
      if (!desc.isNullOrBlank()) {
        speakDelayed(desc)
        return true
      }
    }

    val statusTargets = listOf("status", "device status", "what time is it", "time", "check status")
    if (VoiceCommandFuzzyMatcher.matchesAny(norm, statusTargets, threshold = 0.85)) {
      val desc = statusReader?.describe()
      if (!desc.isNullOrBlank()) {
        speakDelayed(desc)
        return true
      }
    }

    return false
  }

  private fun handleVolume(norm: String): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false

    val isVolUp = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("volume up", "increase volume", "louder", "turn volume up"), threshold = 0.85)
    val isVolDown = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("volume down", "decrease volume", "quieter", "turn volume down", "lower volume"), threshold = 0.85)
    val isMute = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("mute", "mute volume", "mute audio"), threshold = 0.85)
    val isUnmute = VoiceCommandFuzzyMatcher.matchesAny(norm, listOf("unmute", "unmute volume", "unmute audio"), threshold = 0.85)

    if (isVolUp) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
      announceCurrentVolume(audioManager)
      return true
    }
    if (isVolDown) {
      audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
      announceCurrentVolume(audioManager)
      return true
    }
    if (isMute) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
      }
      speakDelayed(context.getString(R.string.voice_commands_muted))
      return true
    }
    if (isUnmute) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
      }
      speakDelayed(context.getString(R.string.voice_commands_unmuted))
      return true
    }

    return false
  }

  private fun announceCurrentVolume(audioManager: AudioManager) {
    val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val pct = if (max > 0) (current * 100) / max else 0
    speakDelayed(context.getString(R.string.voice_commands_volume_set, pct))
  }

  private fun handleOpenApp(command: String, isPartialResult: Boolean): Boolean {
    val query = VoiceCommandFuzzyMatcher.extractPrefixRemainder(
      command,
      listOf("open", "launch", "start", "go to")
    ) ?: return false

    // Explicit check: "open settings" -> opens System Settings
    if (query == "settings" || query == "system settings" || query == "device settings") {
      speakDelayed(context.getString(R.string.voice_commands_opening_settings))
      val intent = Intent(Settings.ACTION_SETTINGS).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      return true
    }

    val match = findBestMatchingApp(query)

    if (isPartialResult) {
      if (match != null && match.second >= 0.95) {
        launchApp(match.first)
        return true
      }
      return false
    }

    if (match != null) {
      launchApp(match.first)
      return true
    } else {
      speakDelayed(context.getString(R.string.voice_commands_app_not_found, query))
      return true
    }
  }

  private fun launchApp(app: AppEntry) {
    val pm = context.packageManager
    val intent = pm.getLaunchIntentForPackage(app.packageName)?.apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    if (intent != null) {
      context.startActivity(intent)
      speakDelayed(context.getString(R.string.voice_commands_opening_app, app.label))
    } else {
      speakDelayed(context.getString(R.string.voice_commands_app_not_found, app.label))
    }
  }

  private fun getInstalledApps(): List<AppEntry> {
    val now = System.currentTimeMillis()
    cachedApps?.let {
      if (now - lastAppQueryTime < 15_000L) {
        return it
      }
    }
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
    val list = resolveInfos.mapNotNull { ri ->
      val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
      val label = ri.loadLabel(pm).toString()
      val norm = VoiceCommandFuzzyMatcher.normalize(label)
      AppEntry(label = label, normalizedLabel = norm, packageName = pkg)
    }
    cachedApps = list
    lastAppQueryTime = now
    return list
  }

  private fun findBestMatchingApp(query: String): Pair<AppEntry, Double>? {
    val normQuery = VoiceCommandFuzzyMatcher.normalize(query)
    if (normQuery.isBlank()) return null

    val apps = getInstalledApps()

    // 1. Exact match
    apps.firstOrNull { it.normalizedLabel == normQuery }?.let {
      return Pair(it, 1.0)
    }

    // 2. Acronym match (e.g. "yt" -> "YouTube", "fb" -> "Facebook")
    if (normQuery.length in 2..4) {
      for (app in apps) {
        val words = app.normalizedLabel.split(" ")
        if (words.size >= normQuery.length) {
          val acronym = words.mapNotNull { it.firstOrNull() }.joinToString("")
          if (acronym.startsWith(normQuery)) {
            return Pair(app, 0.96)
          }
        }
      }
    }

    // 3. Substring match (starts with / contains)
    apps.firstOrNull { it.normalizedLabel.startsWith("$normQuery ") || it.normalizedLabel.startsWith(normQuery) }?.let {
      return Pair(it, 0.90)
    }
    apps.firstOrNull { it.normalizedLabel.contains(normQuery) }?.let {
      return Pair(it, 0.85)
    }

    // 4. Fuzzy similarity match
    var bestMatch: AppEntry? = null
    var bestScore = 0.0

    for (app in apps) {
      val score = VoiceCommandFuzzyMatcher.similarity(normQuery, app.normalizedLabel)
      if (score > bestScore) {
        bestScore = score
        bestMatch = app
      }
    }

    if (bestMatch != null && bestScore >= 0.70) {
      return Pair(bestMatch, bestScore)
    }

    return null
  }

  private fun speakDelayed(text: CharSequence) {
    val returner = pipeline ?: return
    val speakOptions = SpeakOptions.create().apply {
      setFlags(
        FeedbackItem.FLAG_NO_HISTORY
          or FeedbackItem.FLAG_FORCE_FEEDBACK_ALL
      )
    }
    val feedback = Feedback.speech(text, speakOptions).setDelayMs(RECOGNITION_SPEECH_DELAY_MS)
    returner.returnFeedback(EVENT_ID_UNTRACKED, feedback)
  }

  // --- Screen Actions: Click and Find ---

  private val CLICK_PREFIX_REGEX = Regex(
    "^(?:click|press|tap|activate|select)(?:\\s+on)?(?:\\s+the)?\\s+(.+)$",
    RegexOption.IGNORE_CASE
  )

  private val FIND_PREFIX_REGEX = Regex(
    "^(?:find|search\\s+for|search|locate|focus)(?:\\s+on)?(?:\\s+the)?\\s+(.+)$",
    RegexOption.IGNORE_CASE
  )

  private val ROLE_SUFFIXES = listOf(
    "button", "btn", "tab", "switch", "checkbox", "check box", "toggle",
    "icon", "image", "link", "menu", "option", "item", "header", "heading",
    "slider", "field", "text field", "input", "card"
  )

  private data class TargetSpec(
    val rawTarget: String,
    val baseTarget: String,
    val expectedRole: String?
  )

  private fun parseTarget(rawRemainder: String): TargetSpec {
    var clean = rawRemainder.trim()
    if (clean.startsWith("the ", ignoreCase = true)) {
      clean = clean.substring(4).trim()
    }
    for (role in ROLE_SUFFIXES) {
      if (clean.endsWith(" $role", ignoreCase = true)) {
        val base = clean.substring(0, clean.length - role.length - 1).trim()
        if (base.isNotBlank()) {
          return TargetSpec(clean, base, role.lowercase())
        }
      }
    }
    return TargetSpec(clean, clean, null)
  }

  private fun handleClickOnScreen(normInput: String, rawInput: String): Boolean {
    val match = CLICK_PREFIX_REGEX.matchEntire(normInput) ?: return false
    val rawTarget = match.groupValues[1].trim()
    if (rawTarget.length < 2) return false

    val spec = parseTarget(rawTarget)
    val candidate = findBestNodeOnScreen(spec)
    if (candidate == null) {
      speakDelayed(context.getString(R.string.voice_commands_element_not_found, spec.rawTarget))
      return true
    }

    // Find the nearest clickable node (self or ancestor)
    val targetNode = findClickableAncestor(candidate.node) ?: candidate.node
    val clicked = targetNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
      || targetNode.performAction(AccessibilityNodeInfo.ACTION_SELECT)
      || candidate.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    if (clicked) {
      speakDelayed(context.getString(R.string.voice_commands_clicked, candidate.label))
    } else {
      speakDelayed(context.getString(R.string.voice_commands_cannot_click, candidate.label))
    }
    return true
  }

  private fun handleFindOnScreen(normInput: String, rawInput: String): Boolean {
    val match = FIND_PREFIX_REGEX.matchEntire(normInput) ?: return false
    val rawTarget = match.groupValues[1].trim()
    if (rawTarget.length < 2) return false

    val spec = parseTarget(rawTarget)
    val candidate = findBestNodeOnScreen(spec)
    if (candidate == null) {
      speakDelayed(context.getString(R.string.voice_commands_element_not_found, spec.rawTarget))
      return true
    }

    candidate.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
    speakDelayed(context.getString(R.string.voice_commands_found, candidate.label))
    return true
  }

  private data class NodeCandidate(
    val node: AccessibilityNodeInfo,
    val score: Double,
    val label: String
  )

  private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isClickable) return node
    var current: AccessibilityNodeInfo? = node.parent
    while (current != null) {
      if (current.isClickable) return current
      current = current.parent
    }
    return null
  }

  private fun findBestNodeOnScreen(spec: TargetSpec): NodeCandidate? {
    val accessService = context as? AccessibilityService ?: return null
    val root = accessService.rootInActiveWindow ?: run {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
        accessService.windows.firstOrNull { it.isFocused || it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION }?.root
      } else null
    } ?: return null

    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(root)
    var inspected = 0
    var bestCandidate: NodeCandidate? = null

    val targetFull = spec.rawTarget.lowercase()
    val targetBase = spec.baseTarget.lowercase()
    val expectedRole = spec.expectedRole

    while (queue.isNotEmpty() && inspected < 1000) {
      val node = queue.removeFirst()
      inspected++

      val isVisible = node.isVisibleToUser
      if (isVisible) {
        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()
        val viewId = node.viewIdResourceName?.substringAfterLast('/')?.trim()
        val className = node.className?.toString() ?: ""
        val isClickable = node.isClickable || node.parent?.isClickable == true
        val isCheckable = node.isCheckable

        val label = when {
          !desc.isNullOrBlank() -> desc
          !text.isNullOrBlank() -> text
          !viewId.isNullOrBlank() -> viewId
          else -> null
        }

        if (!label.isNullOrBlank()) {
          val normLabel = label.lowercase()
          var score = 0.0

          // Exact matches
          if (normLabel == targetFull) {
            score = 1.0
          } else if (normLabel == targetBase) {
            score = 0.95
          } else if (normLabel.startsWith(targetFull) || normLabel.endsWith(targetFull)) {
            score = 0.85
          } else if (normLabel.startsWith(targetBase) || normLabel.endsWith(targetBase)) {
            score = 0.80
          } else if (normLabel.contains(targetFull)) {
            score = 0.75
          } else if (normLabel.contains(targetBase)) {
            score = 0.70
          } else {
            val sim = maxOf(
              VoiceCommandFuzzyMatcher.similarity(targetFull, normLabel),
              VoiceCommandFuzzyMatcher.similarity(targetBase, normLabel)
            )
            if (sim >= 0.72) {
              score = sim * 0.85
            }
          }

          if (score > 0.0) {
            // Role bonus
            if (expectedRole != null) {
              val matchesRole = when (expectedRole) {
                "button", "btn" -> className.contains("Button", ignoreCase = true) || isClickable
                "switch", "toggle", "checkbox", "check box" -> isCheckable || className.contains("Switch", ignoreCase = true)
                "tab" -> className.contains("Tab", ignoreCase = true)
                "image", "icon" -> className.contains("Image", ignoreCase = true)
                "field", "input", "text field" -> node.isEditable || className.contains("Edit", ignoreCase = true)
                else -> false
              }
              if (matchesRole) {
                score += 0.15
              }
            }

            if (isClickable) score += 0.10
            if (isVisible) score += 0.05

            if (bestCandidate == null || score > bestCandidate.score) {
              bestCandidate = NodeCandidate(node, score, label)
            }
          }
        }
      }

      for (i in 0 until node.childCount) {
        val child = node.getChild(i)
        if (child != null) {
          queue.add(child)
        }
      }
    }

    return bestCandidate?.takeIf { it.score >= 0.65 }
  }

}
