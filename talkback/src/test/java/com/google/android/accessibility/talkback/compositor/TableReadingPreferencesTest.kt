/*
 * Copyright 2026 Backtalk contributors
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

package com.google.android.accessibility.talkback.compositor

import com.google.android.accessibility.talkback.utils.VerbosityPreferences
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class TableReadingPreferencesTest {

  @Test
  fun preferenceConstantsMatchExpectedValues() {
    assertEquals("before", GlobalVariables.TABLE_HEADERS_BEFORE)
    assertEquals("after", GlobalVariables.TABLE_HEADERS_AFTER)
    assertEquals("off", GlobalVariables.TABLE_HEADERS_OFF)
  }

  @Test
  fun verbosityCustomKeyGeneration() {
    val key = "pref_table_speak_row_column_numbers_key"
    val customKey = VerbosityPreferences.toVerbosityPrefKey("pref_verbosity_preset_value_custom", key)
    assertEquals("pref_verbosity_preset_value_custom_pref_table_speak_row_column_numbers_key", customKey)
  }

  @Test
  fun rowColumnNumbersSwitchIsInsidePresetCategoryInPreferencesXml() {
    for (xmlPath in listOf(
      "src/main/res/xml/verbosity_preferences.xml",
      "src/wear/res/xml-watch/verbosity_preferences.xml"
    )) {
      val file = File(xmlPath).let { if (it.exists()) it else File("talkback", xmlPath) }
      assertTrue("File should exist: $xmlPath", file.exists())

      val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
      val allElements = doc.getElementsByTagName("*")
      var foundInPresetCategory = false

      for (i in 0 until allElements.length) {
        val cat = allElements.item(i) as Element
        if (cat.getAttribute("android:key") == "@string/pref_verbosity_category_preset_settings_key") {
          val switches = cat.getElementsByTagName("*")
          for (j in 0 until switches.length) {
            val sw = switches.item(j) as Element
            if (sw.getAttribute("android:key") == "@string/pref_table_speak_row_column_numbers_key") {
              foundInPresetCategory = true
              break
            }
          }
        }
      }

      assertTrue(
        "pref_table_speak_row_column_numbers_key must be inside pref_verbosity_category_preset_settings_key in $xmlPath",
        foundInPresetCategory
      )
    }
  }

  @Test
  fun columnHeadersValuesIncludeAllConstantsInDonottranslateXml() {
    val file = File("talkback/src/main/res/values/donottranslate.xml").let {
      if (it.exists()) it else File("src/main/res/values/donottranslate.xml")
    }
    assertTrue(file.exists())
    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
    val arrays = doc.getElementsByTagName("string-array")
    var foundValuesArray = false

    for (i in 0 until arrays.length) {
      val arr = arrays.item(i) as Element
      if (arr.getAttribute("name") == "pref_table_column_headers_values") {
        foundValuesArray = true
        val items = arr.getElementsByTagName("item")
        val values = (0 until items.length).map { items.item(it).textContent.trim() }
        assertEquals(
          listOf(
            "@string/pref_table_column_headers_value_after",
            "@string/pref_table_column_headers_value_before",
            "@string/pref_table_column_headers_value_off"
          ),
          values
        )
      }
    }
    assertTrue(foundValuesArray)
  }

  @Test
  fun verbosityPrefFragmentBuildMapIncludesTableNumbersSwitch() {
    val file = File("talkback/src/main/java/com/google/android/accessibility/talkback/preference/base/VerbosityPrefFragment.java").let {
      if (it.exists()) it else File("src/main/java/com/google/android/accessibility/talkback/preference/base/VerbosityPrefFragment.java")
    }
    assertTrue(file.exists())
    val content = file.readText()
    assertTrue(
      "VerbosityPrefFragment.buildMap() must include pref_table_speak_row_column_numbers_key",
      content.contains("pref_table_speak_row_column_numbers_key")
    )
  }

  @Test
  fun differencesMdDocumentsTableReadingFeatures() {
    val file = File("../differences.md").let {
      if (it.exists()) it else File("differences.md")
    }
    assertTrue("differences.md should exist", file.exists())
    val text = file.readText()
    assertTrue(text.contains("Table column headers"))
    assertTrue(text.contains("Speak row and column numbers"))
  }
}
