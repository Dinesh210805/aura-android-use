---
name: Send WhatsApp Message
description: Send a WhatsApp message to a contact — deterministic wa.me route first, UI fallback second
whenToUse: when the user asks to WhatsApp, message, or text someone on WhatsApp
---
1. The user gave a NAME? Call resolve_contact first to get the phone number.
   - status "auto": use its phone_number directly.
   - status "disambiguate": ask_user which contact — never guess between people.
   - status "none" or "permission_denied": fall back to the UI route (step 3), searching
     the name inside WhatsApp instead.

2. DETERMINISTIC ROUTE (preferred when you have a number): open_deeplink with
   `https://wa.me/<number>?text=<message>` (number in international digits, no + or
   spaces; message URL-encoded). This lands directly in the right chat with the message
   pre-filled — verify via the observation, then tap the Send button. One perceive, one tap.

3. UI FALLBACK (no number, or deep link failed): launch_app WhatsApp → tap the search
   icon → type_text the contact/group name → tap the matching result from the list
   (like email recipients, you must TAP the result — typing alone selects nothing) →
   tap the message field → type_text the message → tap Send.

4. Verify the message actually left: the sent bubble appears in the chat with a clock
   or tick status. If the bubble is missing, the send did not happen — do not claim it did.

5. Sending to the WRONG person is the worst failure mode here. If the chat title shown
   on screen does not match the person the user named, stop and ask_user before sending.
