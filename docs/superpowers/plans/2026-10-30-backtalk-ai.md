# Backtalk AI Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Spoken AI editing, translation and reply drafting ("Backtalk, make that shorter") as its own layer that plugs into both Dictation Mode and the voice-command layer.

**Architecture:** A new package `com.google.android.accessibility.talkback.ai` of pure, unit-tested pieces (request parser, prompt builder, target resolver) plus a small state machine, `BacktalkAiController`, that talks to the outside world only through the interfaces `AiTextClient`, `AiHost` and `AiConsentStore`. Dictation and the voice-command processor each supply an `AiHost`; neither contains AI logic.

**Tech Stack:** Kotlin, JUnit4 with hand-written fakes, existing AI plumbing in `talkback/.../actor/gemini/` (`AiService`, `GeminiApiKey`, `GeminiActor`).

**Spec:** `docs/superpowers/specs/2026-10-30-backtalk-ai-design.md`

## Global Constraints

- New code lives in package `com.google.android.accessibility.talkback.ai`; Apache header copied from `DictationController.kt`; English strings in `talkback/src/main/res/values/strings.xml`.
- Reuse the user's existing AI setup (`AiService.isSetUp`, `GeminiApiKey`); no new key or service screens.
- No user text is sent anywhere until the user has said yes once to a spoken consent prompt (remembered in shared preferences).
- Every AI result is spoken as a preview and applied only after the user says "yes"; "undo" restores the original (Dictation's `DictationUndoStack`, label `ai`).
- Layers stay separate: do not use the shared voice-command recognizer during dictation; follow `DictationController`'s "open the mic only when TalkBack is quiet" rule (`isSpeakingOrSpeechQueued`).
- "backtalk" is stripped as a polite prefix by `VoiceCommandFuzzyMatcher.normalize`; the command layer therefore uses `parseWithoutPrefix`, dictation requires the prefix.
- Gradle test command: `./gradlew --offline :talkback:testPhoneDebugUnitTest --tests "<pattern>"`; build: `./gradlew --offline :talkback:assemblePhoneDebug`.

## Review Focus

- A dictated sentence that begins with "backtalk" but is not a request ("backtalk is a great app") stays text.
- No AI set up / no network / empty reply: spoken plain-language error, text untouched.
- User says "no", stays silent, or says something else while a preview is pending: original text kept, pending state cleared.
- Empty field or nothing to act on ("make that shorter" with no text): spoken "nothing to change", no AI call.
- Consent declined: nothing is sent, and the next request asks again.

---

### Task 1: Request parser

**Files:**
- Create: `talkback/src/main/java/com/google/android/accessibility/talkback/ai/AiRequestParser.kt`
- Test: `talkback/src/test/java/com/google/android/accessibility/talkback/ai/AiRequestParserTest.kt`

**Interfaces:**
- Produces: `sealed class AiRequest` with `object FixGrammar`, `data class Rewrite(val instruction: String)`, `data class Translate(val language: String)`, `object ReplyToThis`.
- Produces: `object AiRequestParser { fun parse(raw: String): AiRequest?; fun parseWithoutPrefix(raw: String): AiRequest? }`. `parse` requires a leading "backtalk" (optional comma); `parseWithoutPrefix` is for text whose prefix was already stripped.

- [ ] **Step 1: Write failing tests**: `parse("backtalk fix grammar")` is `FixGrammar`; `parse("Backtalk, make that shorter.")` is `Rewrite("shorter")`; `parse("backtalk make that more polite")` is `Rewrite("more polite")`; `parse("backtalk shorten that")` is `Rewrite("shorter")`; `parse("backtalk translate that to Hindi")` is `Translate("hindi")`; `parse("backtalk reply to this")` is `ReplyToThis`; `parse("translate that to Hindi")` is null; `parseWithoutPrefix("translate that to Hindi")` is `Translate("hindi")`; `parse("backtalk is a great app")` is null; `parseWithoutPrefix("make the cake shorter")` is null.
- [ ] **Step 2: Run** `--tests "*AiRequestParserTest"`. Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement** both functions in the file above. Lowercase and trim trailing punctuation; whole-utterance regexes only ("make (that|it) <instruction>", "(re)write that <instruction>", "shorten|lengthen that", "fix|correct (the )?(grammar|spelling)", "translate (that|it) (in)?to <language>", "reply to (this|that|it)").
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `git add talkback && git commit -m "Add Backtalk AI request parser"`

### Task 2: Prompt builder

**Files:**
- Create: `.../ai/AiPromptBuilder.kt`
- Test: `.../ai/AiPromptBuilderTest.kt`

**Interfaces:**
- Consumes: `AiRequest` (Task 1).
- Produces: `data class ReplyContext(val incomingMessage: String, val userIntent: String)`; `object AiPromptBuilder { fun build(request: AiRequest, text: String, reply: ReplyContext? = null): String }`.

- [ ] **Step 1: Write failing tests**: the prompt for `FixGrammar` contains the text and the phrase "Return only the corrected text"; `Rewrite("shorter")` contains "shorter" and the text; `Translate("hindi")` contains "Hindi" (capitalised) and "Return only the translation"; `ReplyToThis` with a `ReplyContext` contains both the incoming message and the user's intent and "Return only the reply"; `ReplyToThis` without a context throws `IllegalArgumentException`.
- [ ] **Step 2: Run** `--tests "*AiPromptBuilderTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `build` in the file above (plain string templates; every prompt ends with the "Return only ..." instruction so the answer can be spoken and typed verbatim).
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Add Backtalk AI prompt builder"`

### Task 3: Target resolver

**Files:**
- Create: `.../ai/AiTargetResolver.kt`
- Test: `.../ai/AiTargetResolverTest.kt`

**Interfaces:**
- Produces: `data class TextTarget(val start: Int, val end: Int)`; `object AiTargetResolver { fun resolve(text: String, selStart: Int, selEnd: Int, lastDictated: IntRange?): TextTarget? }`.

- [ ] **Step 1: Write failing tests**: a non-empty selection wins over everything; with no selection and `lastDictated = 5..9` the target is `TextTarget(5, 10)`; with neither it is the whole field `TextTarget(0, text.length)`; empty or blank text returns null; out-of-range selection indices are clamped and a reversed selection (start > end) is normalised.
- [ ] **Step 2: Run** `--tests "*AiTargetResolverTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** `resolve` in the file above.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Add Backtalk AI target resolver"`

### Task 4: AI client adapter

**Files:**
- Create: `.../ai/AiTextClient.kt`
- Test: `.../ai/GeminiAiTextClientTest.kt` (tests the failure mapping only; the network call is checked manually)

**Interfaces:**
- Produces: `sealed class AiResult { data class Success(val text: String); data class Failure(val reason: Reason) }` with `enum class Reason { NOT_SET_UP, NETWORK, EMPTY, OTHER }`; `interface AiTextClient { fun isAvailable(): Boolean; fun complete(prompt: String, callback: (AiResult) -> Unit) }`; `class GeminiAiTextClient(context: Context) : AiTextClient` plus a pure `internal fun mapFailure(...)`-style helper tested in isolation.

- [ ] **Step 1: Write failing tests** for the failure-mapping helper: no AI configured maps to `NOT_SET_UP`; a network/Volley error maps to `NETWORK`; a blank answer maps to `EMPTY`; anything else maps to `OTHER`.
- [ ] **Step 2: Run** `--tests "*GeminiAiTextClientTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the types and `GeminiAiTextClient`. Send a text-only chat request through the existing `AiService` / `AiServiceRequestPerformer` path (see `actor/gemini/AiService.kt`; `AiService.isSetUp(prefs)` backs `isAvailable`). If the existing classes cannot send a text-only prompt without a screen or image, add the smallest text-only entry point next to them and record that as a ruling.
- [ ] **Step 4: Run.** Expected: PASS; also run `:talkback:assemblePhoneDebug`, expected BUILD SUCCESSFUL.
- [ ] **Step 5: Commit** `git commit -am "Add Backtalk AI text client"`

### Task 5: BacktalkAiController

**Files:**
- Create: `.../ai/BacktalkAiController.kt`
- Test: `.../ai/BacktalkAiControllerTest.kt` (fakes for the three interfaces)

**Interfaces:**
- Consumes: `AiRequest`, `AiPromptBuilder`, `ReplyContext`, `AiTargetResolver`, `AiTextClient`, `AiResult`.
- Produces: `interface AiHost { fun speak(text: String); fun currentText(): String; fun selection(): Pair<Int, Int>; fun lastDictated(): IntRange?; fun replaceRange(start: Int, end: Int, newText: String); fun readOnScreenMessage(): String?; fun listenForAnswer() }`; `interface AiConsentStore { var granted: Boolean }`; `class BacktalkAiController(host: AiHost, client: AiTextClient, consent: AiConsentStore)` with `val isAwaitingAnswer: Boolean`, `fun handle(request: AiRequest)`, `fun onAnswer(utterance: String): Boolean` (false when nothing was pending), `fun cancel()`.

- [ ] **Step 1: Write failing tests** with fakes, one per flow: (a) no consent: `handle(Rewrite("shorter"))` speaks the consent prompt, calls no AI, `onAnswer("yes")` stores consent and runs the request; `onAnswer("no")` stores nothing and speaks "cancelled"; (b) consented rewrite: AI result is spoken as a preview and `replaceRange` is NOT called until `onAnswer("yes")`, then it is called with the resolved target and the AI text; `onAnswer("no")` leaves the text and clears pending; (c) empty field: speaks "nothing to change", client never called; (d) client `Failure(NETWORK)`: spoken error, text unchanged, nothing pending; client not set up: spoken setup message; (e) reply flow: `handle(ReplyToThis)` with a message on screen asks "What do you want to say?", `onAnswer("tell him I'll be late")` calls the client with a prompt containing both, previews the reply, `yes` applies; no on-screen message speaks "no message found"; (f) `onAnswer("something else")` during a pending preview clears pending and returns false so the caller treats it as normal speech; (g) `cancel()` clears pending.
- [ ] **Step 2: Run** `--tests "*BacktalkAiControllerTest"`. Expected: FAIL.
- [ ] **Step 3: Implement** the controller as a small state machine (`Idle`, `AwaitingConsent(request)`, `AwaitingReplyIntent(message)`, `AwaitingApply(target, result)`). All spoken strings come from a `Strings` interface or string constants the Android wiring (Task 6) fills from `R.string`; keep it free of Android imports so tests run on the JVM.
- [ ] **Step 4: Run.** Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Add Backtalk AI controller"`

### Task 6: Wire into Dictation

**Files:**
- Modify: `.../dictation/DictationController.kt`, `talkback/src/main/res/values/strings.xml`, `talkback/src/main/java/com/google/android/accessibility/talkback/TalkBackService.java`
- Create: `.../dictation/DictationAiHost.kt` (implements `AiHost` over the focused node and the undo stack)

**Interfaces:**
- Consumes: `BacktalkAiController`, `AiRequestParser.parse`, `AiHost`.
- Produces: `DictationController.setAiController(controller: BacktalkAiController?)`.

- [ ] **Step 1: In `handleDictationSpeech`**, before `DictationCommandParser.parse`: if `ai.isAwaitingAnswer`, call `ai.onAnswer(raw)` and return when it returns true; else if `AiRequestParser.parse(raw)` is non-null, call `ai.handle(request)` and return true. Otherwise continue as before. `listenForAnswer()` in `DictationAiHost` calls `scheduleStartListening`.
- [ ] **Step 2: Implement `DictationAiHost`**: `replaceRange` pushes a `Snapshot(label = "ai")` then sets text and cursor; `lastDictated()` returns the range of the last inserted dictation (track it in `insertDictatedText`); `readOnScreenMessage()` returns null in dictation.
- [ ] **Step 3: Add strings** (consent prompt, "Here is the result", "say yes to apply", "cancelled", errors, "nothing to change", "What do you want to say?", "no message found") and construct the controller in `TalkBackService` next to `dictationController`.
- [ ] **Step 4: Build** `:talkback:assemblePhoneDebug`, expected BUILD SUCCESSFUL; run `--tests "*dictation*" --tests "*.ai.*"`, expected PASS.
- [ ] **Step 5: Commit** `git commit -am "Wire Backtalk AI into dictation"`

### Task 7: Wire into the voice-command layer, help screen, manual checks

**Files:**
- Modify: `.../actor/voicecommands/VoiceCommandProcessor.java`, `.../actor/voicecommands/VoiceCommandActor.java`, `talkback/src/main/res/values/strings_voicecommands.xml`, `talkback/src/phone/java/com/google/android/accessibility/talkback/training/VoiceCommandAndHelpConfigs.java`
- Create: `.../actor/voicecommands/VoiceCommandAiHost.kt` (implements `AiHost` over the focused editable node, and reads the on-screen message from the accessibility focus / window text)

- [ ] **Step 1: In `handleSpeechCommand`**, after the dictation-start check: if `ai.isAwaitingAnswer`, route to `ai.onAnswer(command)`; else if `AiRequestParser.parseWithoutPrefix(command)` is non-null, call `ai.handle(request)`. `VoiceCommandAiHost.listenForAnswer()` reopens the command recognizer only when TalkBack is quiet.
- [ ] **Step 2: Implement `VoiceCommandAiHost`**: edits go to the focused editable field (speak "focus a text box first" if none); `readOnScreenMessage()` returns the text of the accessibility-focused node, falling back to the visible text of its parent.
- [ ] **Step 3: Add help entries** (same pattern as the dictation help entries) for: fix grammar, make that shorter/more polite/formal, translate that to <language>, reply to this.
- [ ] **Step 4: Build and test**: `:talkback:assemblePhoneDebug` BUILD SUCCESSFUL; `--tests "*dictation*" --tests "*.ai.*" --tests "*voicecommands*"` PASS.
- [ ] **Step 5: Manual on-device checklist (put in the PR description):** with an AI service set up and TalkBack on: dictate a sentence, say "Backtalk, fix grammar" (consent prompt, yes, preview, yes, undo restores); "Backtalk, translate that to Hindi"; outside dictation, focus a message, say "reply to this", answer the prompt, hear the draft, say yes; turn Wi-Fi/data off and confirm the spoken error; say "no" to a preview and confirm the text is unchanged.
- [ ] **Step 6: Commit** `git commit -am "Wire Backtalk AI into voice commands and help screen"`

---

## Self-review notes

- Spec coverage: fix/rewrite (T1, T2, T5), translate (T1, T2), reply to this (T1, T2, T5, T7), "that" resolution (T3), spoken preview + undo (T5, T6), consent (T5), both places (T6, T7), reuse of existing AI setup (T4), errors (T4, T5), help screen (T7).
- Type consistency: `AiRequest`, `AiHost`, `AiConsentStore`, `AiResult`, `ReplyContext`, `TextTarget` are each defined once in an Interfaces block above.
- Not in this plan: the Calling plan, a refactor of Dictation to share a quiet-waiter helper (the AI hosts reuse each layer's existing wait-until-quiet logic), non-English request phrases.
