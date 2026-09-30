---
name: Text Fields And Forms
description: Reliably fill text fields — focus first, commit autocomplete suggestions, clear stale text, submit searches
whenToUse: when a task involves typing into any field (forms, search bars, logins, address fields) or a typed value seems to not register
---
# Text entry that actually lands

## 1. Focus before typing
type_text goes to the FOCUSED field. Tap the target field first and confirm focus took
(the observation shows the keyboard appeared / the field is active). Typing without focus
either goes nowhere or into the wrong field.

## 2. Autocomplete fields need a COMMIT tap
Many fields (email To rows, contact pickers, location/address fields, search-with-
suggestions, tag inputs) do not accept raw typed text — they show a suggestion list and
the value only registers when you TAP the matching suggestion:
- type the value → perceive_screen → tap the suggestion whose text matches what you
  typed (or the entity the user meant) → verify the value became a chip/filled entry.
- If you type and immediately move on, the field is still EMPTY as far as the app is
  concerned, and the failure only surfaces later (missing recipient, wrong location).
- No matching suggestion? Check your spelling first; then scroll the suggestion list.

## 3. Clearing a field that already has text
type_text usually APPENDS. To replace existing content: long_press the field → tap
"Select all" from the popup → type_text the new value (typing over a selection replaces
it). Verify the old value is gone before moving on.

## 4. Submitting
- Search bars: press_enter submits. Don't hunt for a search button that may not exist.
- Multi-field forms: fill top to bottom, perceiving when the observation doesn't confirm
  where focus went; scroll_down to reach fields below the keyboard.
- The keyboard may COVER the submit button — press_back once closes the keyboard
  without leaving the screen, then tap the button.

## 5. Verify what the field actually contains
After a critical entry (amounts, addresses, IDs), read the field text back from the next
perceive/observation. Autocorrect can silently mangle typed text — what you sent to
type_text is not guaranteed to be what the field holds.
