# Backtalk AI: spoken AI editing, translation and replies

Branch: `voice-commands-improvements`. Part 3 of the voice work, after Dictation (done) and Calling (design in `2026-10-30-dictation-and-calling-design.md`, plan not yet written).

## Intent

A TalkBack user dictating or reading on Android gets a capability Google voice input and keyboards do not have: speak a request, and AI rewrites, translates or answers it, hands-free and eyes-free, with the result spoken aloud and always undoable. The user wants it to feel powerful enough that people choose Backtalk over Google voice input. The user named the feature simply **"Backtalk"**: the spoken form is "Backtalk, <request>".

## Goals

1. **Fix and rewrite text:** "Backtalk, fix grammar", "make that more polite", "shorter", "more formal" (and similar tone/length requests the AI can interpret).
2. **Translate:** "Backtalk, translate that to Hindi" (any language the AI service supports).
3. **Reply to what is on screen:** "Backtalk, reply to this" reads the message currently on screen or focused, asks what the user wants to say, drafts a reply, speaks it, and types it only after the user says yes.
4. **Target of edits ("that"):** the last dictated sentence when dictating; otherwise the selected text, otherwise the whole field. (My call.)
5. **Every result is spoken aloud before it is applied**, and "undo" restores the original text exactly. The existing Dictation undo stack is reused when inside dictation.
6. **Works in two places (my call):** inside Dictation Mode, and from the normal voice-command layer on any screen (needed for "reply to this"). It is its own layer: it plugs into both, it is not part of either, matching the user's rule that dictation and voice commands stay separate layers.

## Non-goals

- Building a new AI client. Use the AI integration Backtalk already has (`actor/gemini/`: Gemini online with the user's key, on-device Gemma, AI Core).
- Sending messages for the user. Backtalk only types the reply into the field; the user sends it.
- Other languages for the command phrases in this pass (English commands; the AI's output language is whatever is requested).

## Anti-goals (failure even if it "works")

- Text leaving the phone without the user having agreed to it.
- AI output replacing the user's text with no spoken preview, or with no way back via undo.
- Silent failure: no key, no network, quota or model errors must be spoken in plain words with what to do next.
- Typing a hallucinated reply the user never confirmed.

## Constraints

- Reuse `GeminiActor` and the existing opt-in dialog/preferences (`GeminiOptInDialog`, `AiServicePreference`, `GeminiApiKeyPreference`). Do not add a second consent or key flow.
- First use must ask permission aloud before any text is sent (my call: one spoken yes/no, remembered).
- Respect the existing layer separation: dictation speech is handled only by `DictationController`'s own recognizer; the new layer must not reopen the shared voice-command recognizer during dictation.
- Interaction with TalkBack speech: opening the mic interrupts TalkBack feedback (`VoiceActionMonitor`); follow the "wait until TalkBack is quiet" pattern in `DictationController` before listening again.
- "backtalk" is already stripped as a polite prefix by `VoiceCommandFuzzyMatcher.normalize`; the builder must not lose the request that follows it, and must not mistake ordinary dictated sentences starting with "backtalk" for requests (require the prefix plus a recognised request verb).
- Cannot be run end-to-end here: unit-test parsing, prompt building and result handling with a fake AI client; give manual on-device steps for the rest.

## Decided by the user

- Name is "Backtalk" (spoken as the prefix).
- Wanted features: fix/rewrite, translate, reply to a message, spoken results with undo.

## My calls (flag these when reviewing)

- "That" resolution order in goal 4.
- Works from any screen as well as inside dictation (goal 6).
- Spoken yes/no before applying any AI result, and one remembered spoken consent for sending text to the AI.
- AI service choice is whatever the user already configured in Backtalk.

## Left to the builder

Prompt wording, request phrase grammar (reuse `DictationCommandParser` patterns), how to read the on-screen message for replies (accessibility tree vs the existing screen-overview code), class layout, and error wording.
