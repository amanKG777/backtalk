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

package com.google.android.accessibility.talkback.scripting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptVoiceMatcherTest {

  @Test
  fun rejectsBareWildcardPatterns() {
    assertFalse(ScriptVoiceMatcher.isValidPattern("*"))
    assertFalse(ScriptVoiceMatcher.isValidPattern(" * "))
    assertFalse(ScriptVoiceMatcher.isValidPattern("**"))
    assertFalse(ScriptVoiceMatcher.isValidPattern(""))
    assertFalse(ScriptVoiceMatcher.isValidPattern("   "))
    assertTrue(ScriptVoiceMatcher.isValidPattern("search for *"))
    assertTrue(ScriptVoiceMatcher.isValidPattern("* settings"))
    assertTrue(ScriptVoiceMatcher.isValidPattern("open * now"))

    val targets = listOf(VoiceTarget("*", "bare"), VoiceTarget(" * ", "spaced"))
    assertNull(ScriptVoiceMatcher.match("hello", targets))
  }

  @Test
  fun matchesWildcardsAtWordBoundariesOnly() {
    val targets = listOf(VoiceTarget("search for *", "search"))

    assertNull(ScriptVoiceMatcher.match("search fortnite", targets))
    assertNull(ScriptVoiceMatcher.match("search for", targets))

    val hit = ScriptVoiceMatcher.match("search for cats", targets)
    assertNotNull(hit)
    assertEquals("search", hit!!.value)
    assertEquals(VoiceMatchKind.WILDCARD, hit.kind)
    assertEquals("cats", hit.remainder)
  }

  @Test
  fun matchesSuffixAndMiddleWildcardsAtWordBoundaries() {
    val suffixTargets = listOf(VoiceTarget("* settings", "suffix"))
    assertNull(ScriptVoiceMatcher.match("mysettings", suffixTargets))
    val suffixHit = ScriptVoiceMatcher.match("open settings", suffixTargets)
    assertEquals("open", suffixHit?.remainder)

    val middleTargets = listOf(VoiceTarget("open * now", "middle"))
    assertNull(ScriptVoiceMatcher.match("open now", middleTargets))
    val middleHit = ScriptVoiceMatcher.match("open gmail now", middleTargets)
    assertEquals("gmail", middleHit?.remainder)
  }

  @Test
  fun normalizesPatternsAndManifestPhrasesLikeSpokenCommands() {
    val targets =
      listOf(
        VoiceTarget("Turn of, lights!", "lights"),
        VoiceTarget("Please search for *", "search"),
      )

    val exactHit = ScriptVoiceMatcher.match("please turn off lights", targets)
    assertNotNull(exactHit)
    assertEquals("lights", exactHit!!.value)
    assertEquals(VoiceMatchKind.EXACT, exactHit.kind)

    val wildcardHit = ScriptVoiceMatcher.match("Could you search for, pizza?", targets)
    assertNotNull(wildcardHit)
    assertEquals("search", wildcardHit!!.value)
    assertEquals(VoiceMatchKind.WILDCARD, wildcardHit.kind)
    assertEquals("pizza", wildcardHit.remainder)
  }

  @Test
  fun exactBeatsWildcardAndFuzzyRegardlessOfOrder() {
    // Put fuzzy and wildcard before exact to simulate unordered HashMap iteration.
    val targets =
      listOf(
        VoiceTarget("search for *", "wildcard"),
        VoiceTarget("search cats", "fuzzy"),
        VoiceTarget("search for cats", "exact"),
      )

    val hit = ScriptVoiceMatcher.match("search for cats", targets)
    assertNotNull(hit)
    assertEquals("exact", hit!!.value)
    assertEquals(VoiceMatchKind.EXACT, hit.kind)
    assertNull(hit.remainder)
  }

  @Test
  fun wildcardBeatsFuzzyAndMoreSpecificWildcardWins() {
    val targets =
      listOf(
        VoiceTarget("search *", "short-wildcard"),
        VoiceTarget("search for cats", "fuzzy-only"),
        VoiceTarget("search for *", "long-wildcard"),
      )

    val hit = ScriptVoiceMatcher.match("search for dogs", targets)
    assertNotNull(hit)
    assertEquals("long-wildcard", hit!!.value)
    assertEquals(VoiceMatchKind.WILDCARD, hit.kind)
    assertEquals("dogs", hit.remainder)
  }

  @Test
  fun exactOrWildcardPhaseSkipsFuzzyAndFuzzyPhaseMatchesIt() {
    val targets = listOf(VoiceTarget("open settings", "settings"))

    assertNull(ScriptVoiceMatcher.matchExactOrWildcard("open the settings", targets))

    val fuzzyHit = ScriptVoiceMatcher.matchFuzzy("open the settings", targets)
    assertNotNull(fuzzyHit)
    assertEquals("settings", fuzzyHit!!.value)
    assertEquals(VoiceMatchKind.FUZZY, fuzzyHit.kind)
    assertNull(fuzzyHit.remainder)
  }
}
