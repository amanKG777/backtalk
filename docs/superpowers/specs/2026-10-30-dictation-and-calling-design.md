# Backtalk: Dictation upgrades and phone calling by voice

Branch: `voice-commands-improvements`. Two independent parts, built in this order: (1) Dictation, (2) Calling.

## Intent

The user is a TalkBack screen-reader user on Android. Typing is slow and painful, and a single mistake is expensive to fix. They want Backtalk's voice commands to be powerful enough that people switching from other assistants (their words: Siri) are impressed. Every action must be confirmed **aloud**; nothing may rely on the screen.

## Current state (read from the code)

`talkback/.../dictation/DictationController.kt` and `DictationPunctuationFormatter.kt` already provide: continuous listening, spoken punctuation/symbols, auto-capitalization, delete word / delete character / clear / undo / new line / new paragraph / read text, and `replace|change|swap X with Y` (with a replace-all form, case preservation and spoken confirmation). Voice command plumbing lives in `actor/voicecommands/` (`VoiceCommandProcessor`, `VoiceCommandActor`, `SystemVoiceCommands.kt`, `VoiceCommandFuzzyMatcher.kt`). The manifest has no call or contacts permissions (only `READ_PHONE_STATE`, `MANAGE_ONGOING_CALLS`).

## Goals

### Part 1: Dictation

1. **Auto punctuation.** Add commas and full stops / question marks to dictated text without the user saying them, in addition to the existing spoken punctuation. A command such as "fix punctuation" repairs existing text in the field (spacing, sentence capitalization, missing end punctuation).
2. **Case and formatting commands.** "Capitalize that", "all caps", "lowercase that", "capitalize <word>", "bullet list" (start a line-based list; each new line gets a bullet). No bold/italic: plain text fields cannot show it.
3. **Richer editing.** Delete last sentence; delete `<phrase>`; insert `<text>` before/after `<phrase>`; cursor movement ("go to start", "go to end", "back N words", "forward N words"); read last sentence / last word.
4. **Spoken feedback for every action**, including plain insertions (read back what was inserted), delete character, new line, cursor moves, and failures ("couldn't find X").
   **Decided:** auto punctuation is a setting that starts OFF; "fix punctuation" always works.
5. **No accidental commands. Decided:** "clear" / "delete everything" stay commands but ask "Clear all text? Say yes" first; harmless one-word commands such as "return" and "enter" need the prefix "command" (e.g. "command return"). Original note: single-word commands ("clear", "read", "return", "done", "paragraph", "enter") must not swallow genuine dictation. Builder's call on the mechanism; one acceptable approach is requiring a distinct spoken prefix or a short confirmation for destructive ones. "Replace the cat with a dog" spoken as a sentence is a known ambiguity; the design must reduce false triggers and keep undo reliable.
6. **Better undo.** Undo restores text and puts the cursor where the edit happened, not the end. Multiple levels, spoken confirmation of what was undone.
7. Keep and extend the unit tests in `DictationPunctuationFormatterTest.kt`; new editing logic should live in pure, testable code like the formatter.

### Part 2: Calling

1. **Place a call:** "call Mom", "call 9876543210". Spoken digits ("nine eight seven six...") are understood.
2. **Confirm first, then call straight away.** It speaks "Calling Mom, say yes to confirm"; yes places the call immediately; anything else cancels. (Decided by the user.)
3. **Speaker:** "call Mom on speaker" turns speakerphone on once the call connects.
4. **Incoming calls:** "answer the call" / "pick up the call" answers; "decline" / "reject the call" rejects. (Answer decided by the user; decline is my guess.)
5. **In-call:** "hang up" / "end call", "speaker on/off". (My guess.)
6. **Ambiguous contacts** (two "Rahul"s): read the options and ask the user to choose by number. (My guess.)
7. **Permissions via one guided setup.** Needs phone-call, contacts and answer-calls permissions. A TalkBack user cannot easily handle stock permission dialogs mid-command, so provide one accessible, spoken setup flow that requests all of them; if a permission is missing when a call command is used, say so aloud and offer the setup. (My call.)

## Non-goals

- Automatically starting to listen when a call rings. **The user wants gesture-only triggering** of voice commands, including for answering a call.
- Bold/italic or rich-text formatting.
- Messaging, video calls, or calling through third-party apps (WhatsApp etc.). Possible later work.
- Non-English languages in this pass (strings go in `values/` as the existing dictation strings do).

## Anti-goals (failure even if it "works")

- Any action with no spoken confirmation.
- A call placed to the wrong person without the user hearing the name or number first.
- Normal dictated sentences being eaten as commands, or text lost with no way back via undo.
- Permission requests that a screen-reader user cannot complete.

## Constraints

- Existing codebase: Kotlin for dictation, Java for the voice-command actors; Apache license headers; follow existing style and the pipeline/`Feedback` speech patterns.
- Voice commands start from the user's existing trigger gesture; the dictation speech layer stays isolated from the normal command processor (the previous commit established this, do not regress it).
- Android API realities the builder must verify: answering/ending calls programmatically (`TelecomManager` needs `ANSWER_PHONE_CALLS`), placing calls (`CALL_PHONE`), contact lookup (`READ_CONTACTS`), and turning on speaker during a call. Which mechanism to use is the builder's call.
- Cannot be tested by running the app in this environment; correctness rests on unit tests for pure logic (parsing, name matching, spoken-digit conversion, text edits) plus clear manual test steps for the on-device parts.

## Decided by the user

- Calls go out right after a spoken confirmation (like Siri).
- "Call X on speaker" is supported.
- "Answer the call" / "pick up the call" are supported.
- Voice commands are triggered by gesture only.

## My calls (flag these when reviewing)

- Auto punctuation, case commands, richer editing, and better undo as listed above.
- Decline, in-call hang up/speaker, disambiguation by number, and the guided permission setup.
- Build order: dictation first, calling second.
- Dropped for now: announcing the caller's name when a call rings (needs more permissions and may duplicate TalkBack's own notification speech).

## Left to the builder

Class and file layout, grammar/matching approach (reuse `VoiceCommandFuzzyMatcher` where it fits), contact name matching rules, how to anchor undo, exact spoken strings, and the Android mechanisms listed under Constraints.
