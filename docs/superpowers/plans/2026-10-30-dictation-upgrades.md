# Dictation Upgrades Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Backtalk Dictation Mode usable by a screen-reader user: auto punctuation, case/format commands, richer editing, spoken feedback for every action, safe command words, and multi-level undo.

**Architecture:** Move all text logic into pure Kotlin objects that take `(text, cursor)` and return a result (`DictationTextEditor`, `DictationCommandParser`), unit-tested without Android. `DictationController` becomes a thin dispatcher: parse utterance -> apply edit -> set text -> speak confirmation.

**Tech Stack:** Kotlin, JUnit4 (existing `DictationPunctuationFormatterTest.kt` style), Android accessibility `ACTION_SET_TEXT` / `ACTION_SET_SELECTION`.

**Spec:** `docs/superpowers/specs/2026-10-30-dictation-and-calling-design.md` (Part 1). Calling is a separate plan.

## Global Constraints

- Package `com.google.android.accessibility.talkback.dictation`; Apache license header on new files (copy from `DictationController.kt`).
- Every user-visible action speaks a confirmation via `speak(...)`; strings go in `talkback/src/main/res/values/strings.xml` next to the existing `dictation_*` strings (English only).
- Speech layer stays isolated: `VoiceCommandProcessor` must keep routing active-dictation speech only to `DictationController.handleDictationSpeech` (do not regress commit b5a6e9c3).
- Auto punctuation setting defaults OFF. "fix punctuation" always works.
- "clear" / "clear all" / "delete everything" ask "Clear all text? Say yes" and need "yes" to proceed. "return" / "enter" / other harmless one-word commands need the prefix "command" (e.g. "command return").
- Plain text only: no bold/italic.

## Review Focus

- Dictating a sentence containing a command word ("please clear the table") inserts it as text, not a command.
- Empty field: delete sentence / undo / cursor moves speak a failure ("nothing to delete") and do not crash or push a bogus undo entry.
- Cursor mid-text: insertion, case commands and undo act at the cursor, not the end.
- Replace/delete/insert phrase that matches several times or not at all speaks the outcome; unicode and emoji in text do not break indexes.
- "yes" with no pending confirmation is inserted as text; any other utterance cancels a pending clear.

---

### Task 1: Command parser

**Files:**
- Create: `talkback/src/main/java/com/google/android/accessibility/talkback/dictation/DictationCommandParser.kt`
- Test: `talkback/src/test/java/com/google/android/accessibility/talkback/dictation/DictationCommandParserTest.kt`

**Interfaces:**
- Produces: `sealed class DictationCommand` with objects/classes `Stop, DeleteWord, DeleteChar, DeleteLastSentence, Clear, Undo, NewLine, NewParagraph, ReadAll, ReadLastSentence, ReadLastWord, FixPunctuation, CapitalizeThat, UpperCaseThat, LowerCaseThat, BulletList, AutoPunctuation(on: Boolean), GoToStart, GoToEnd` and `Move(words: Int)` (negative = back), `CapitalizeWord(word)`, `DeletePhrase(phrase)`, `InsertAround(text, phrase, before: Boolean)`, `Replace(target, replacement, all: Boolean)`.
- Produces: `object DictationCommandParser { fun parse(raw: String): DictationCommand? }` — null means "this is dictation text".

- [ ] **Step 1: Write failing tests** in `DictationCommandParserTest`: `parse("delete last sentence")` is `DeleteLastSentence`; `parse("back 2 words")` is `Move(-2)`; `parse("back two words")` is `Move(-2)`; `parse("insert hello after dear")` is `InsertAround("hello","dear",false)`; `parse("replace all cat with dog")` is `Replace("cat","dog",true)`; `parse("clear")` is `Clear`; `parse("return")` is null while `parse("command return")` is `NewLine`; `parse("please clear the table")` is null; `parse("stop")` is `Stop`; `parse("auto punctuation on")` is `AutoPunctuation(true)`.
- [ ] **Step 2: Run** `./gradlew :talkback:testPhoneDebugUnitTest --tests "*DictationCommandParserTest"` — Expected: FAIL (unresolved reference).
- [ ] **Step 3: Implement** `DictationCommand` and `DictationCommandParser.parse` in the file above. Move the replace/change/swap regexes and the whole-utterance command sets out of `DictationController`; match whole utterances only (normalize with `VoiceCommandFuzzyMatcher.normalize(...).lowercase()`); small word-number map for "one".."ten".
- [ ] **Step 4: Run** the same command — Expected: PASS.
- [ ] **Step 5: Commit** `git add -A && git commit -m "Add dictation command parser"`

### Task 2: Pure text editing operations

**Files:**
- Create: `.../dictation/DictationTextEditor.kt`
- Test: `.../dictation/DictationTextEditorTest.kt`

**Interfaces:**
- Produces: `data class EditResult(val text: String, val cursor: Int, val feedback: Feedback)` where `Feedback` is a small sealed class (`Inserted(text)`, `Deleted(what)`, `Changed(what)`, `Moved(description)`, `NotFound(what)`, `NothingToDo`) — the controller maps these to strings.
- Produces: `object DictationTextEditor` with `deleteLastSentence`, `deletePhrase`, `insertAround`, `moveWords`, `goToStart`, `goToEnd`, `capitalizeThat`, `upperCaseThat`, `lowerCaseThat`, `capitalizeWord`, `startBullet`, `lastSentence`, `lastWord`, each `(text: String, cursor: Int, ...) -> EditResult?` (read helpers return `String?`). "That" = the last dictated word/sentence ending at the cursor.

- [ ] **Step 1: Write failing tests**, one per function, including: delete last sentence in `"Hi there. How are you? Fine"` with cursor at end gives `"Hi there. How are you? "`; same call with cursor after `"Hi there."` deletes `"Hi there."`; `deleteLastSentence("", 0)` is `NothingToDo`; `deletePhrase` of absent phrase is `NotFound`; `insertAround("dear sam", 8, "hello", "sam", before=true)` gives `"dear hello sam"`; `moveWords` clamps at 0 and `text.length`; text containing an emoji keeps cursor indexes valid.
- [ ] **Step 2: Run** `--tests "*DictationTextEditorTest"` — Expected: FAIL.
- [ ] **Step 3: Implement** the object. Work on the string and cursor only, no Android imports.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Add pure dictation text editing operations"`

### Task 3: Auto punctuation and "fix punctuation"

**Files:**
- Modify: `.../dictation/DictationPunctuationFormatter.kt`
- Test: `DictationPunctuationFormatterTest.kt`

**Interfaces:**
- Produces: `DictationPunctuationFormatter.autoPunctuate(text: String): String` (adds a sentence-ending mark to a finished utterance that lacks one: `?` if it starts with a question word — who/what/when/where/why/how/is/are/do/does/can/will/should — else `.`; leaves text ending in punctuation or a newline alone) and `fixPunctuation(text: String): String` (normalizes spacing around punctuation, capitalizes every sentence start and standalone "I", ensures end punctuation).

- [ ] **Step 1: Write failing tests**: `autoPunctuate("how are you")` is `"how are you?"`; `autoPunctuate("see you soon")` is `"see you soon."`; `autoPunctuate("hello.")` unchanged; `autoPunctuate("")` is `""`; `fixPunctuation("hello  ,world .this is i")` is `"Hello, world. This is I."`.
- [ ] **Step 2: Run** `--tests "*DictationPunctuationFormatterTest"` — Expected: new tests FAIL.
- [ ] **Step 3: Implement** both functions, reusing existing `format` rules rather than duplicating them.
- [ ] **Step 4: Run** — Expected: all PASS.
- [ ] **Step 5: Commit** `git commit -am "Add auto punctuation and fix punctuation"`

### Task 4: Multi-level undo with cursor

**Files:**
- Create: `.../dictation/DictationUndoStack.kt`
- Test: `.../dictation/DictationUndoStackTest.kt`

**Interfaces:**
- Produces: `data class Snapshot(val text: String, val cursor: Int, val label: String)`; `class DictationUndoStack(limit: Int = 50) { fun push(s: Snapshot); fun pop(): Snapshot?; fun clear(); val size: Int }`.

- [ ] **Step 1: Write failing tests**: pop returns the most recent snapshot first; pushing past `limit` drops the oldest; `pop()` on empty is null; `clear()` empties.
- [ ] **Step 2: Run** `--tests "*DictationUndoStackTest"` — Expected: FAIL.
- [ ] **Step 3: Implement** with `ArrayDeque`.
- [ ] **Step 4: Run** — Expected: PASS.
- [ ] **Step 5: Commit** `git commit -am "Add dictation undo stack"`

### Task 5: Wire everything into DictationController

**Files:**
- Modify: `.../dictation/DictationController.kt`, `talkback/src/main/res/values/strings.xml`, `talkback/src/main/res/values/donottranslate.xml` (pref key `pref_dictation_auto_punctuation` + `<bool ... default>` false)

**Interfaces:**
- Consumes: everything from Tasks 1-4.
- Produces: unchanged public API of `DictationController` (`startDictation`, `stopDictation`, `handleDictationSpeech`, `isDictationActive`, etc.) so `VoiceCommandProcessor` and `TalkBackService` need no changes.

- [ ] **Step 1: Replace** the `is*Command` sets and `handleReplaceCommand` in `handleDictationSpeech` with a single `DictationCommandParser.parse(raw)` + `when` over the sealed class; non-null results never fall through to text insertion.
- [ ] **Step 2: Add** `pendingClear: Boolean`. `Clear` sets it and speaks `dictation_confirm_clear`; next utterance "yes" clears (and pushes undo), anything else clears the flag and is handled normally. "yes" with no pending flag is inserted as text.
- [ ] **Step 3: Replace** `undoHistory: ArrayDeque<String>` with `DictationUndoStack`; `undo` restores text AND cursor and speaks the snapshot label (`dictation_undid`, `%1$s`).
- [ ] **Step 4: Speak** a confirmation for every branch via new strings: inserted text read back, character deleted, new line, cursor moved, case changed, bullet started, punctuation fixed, auto punctuation on/off, and each `NotFound`/`NothingToDo` failure.
- [ ] **Step 5: Apply** `autoPunctuate` to inserted text only when the preference is on (read via the app's existing shared-preferences helper used elsewhere in `talkback/`).
- [ ] **Step 6: Build** `./gradlew :talkback:assemblePhoneDebug` — Expected: BUILD SUCCESSFUL. **Run all dictation tests** `--tests "*dictation*"` — Expected: PASS.
- [ ] **Step 7: Manual on-device check (write into the PR description):** with TalkBack on, in a Notes field: dictate a sentence (hear it read back); "delete last sentence"; "back two words" then dictate (inserts mid-text); "clear" then "no" (text kept) then "clear" "yes" then "undo"; "auto punctuation on" then dictate "how are you"; "command return".
- [ ] **Step 8: Commit** `git commit -am "Wire dictation parser, editor, undo and confirmations into controller"`

### Task 6: Help screen strings

**Files:**
- Modify: `talkback/src/main/res/values/strings_voicecommands.xml`, `talkback/src/phone/java/com/google/android/accessibility/talkback/training/VoiceCommandAndHelpConfigs.java`

- [ ] **Step 1: Add** help entries (same pattern as the existing Dictation / Find & Replace entries from b5a6e9c3) for: delete last sentence, delete/insert around a phrase, move by words, capitalize / all caps / lowercase, bullet list, fix punctuation, auto punctuation on/off, "command return", and clear confirmation.
- [ ] **Step 2: Build** `./gradlew :talkback:assemblePhoneDebug` — Expected: BUILD SUCCESSFUL.
- [ ] **Step 3: Commit** `git commit -am "Document new dictation commands on help screen"`

---

## Self-review notes

- Spec coverage (Part 1): auto punctuation + fix punctuation (T3, T5), case/bullet (T1, T2, T5), richer editing (T2), spoken feedback everywhere (T5), no accidental commands + clear confirmation (T1, T5), better undo (T4, T5), tests (T1-T4), help text (T6).
- Names used across tasks (`DictationCommand`, `EditResult`, `Snapshot`, `DictationUndoStack`) are defined once in the Interfaces blocks above.
- Not in this plan: calling (own plan), a settings-screen toggle for auto punctuation (voice command + pref key only, to keep scope tight).
