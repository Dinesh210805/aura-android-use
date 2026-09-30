---
name: Send Email
description: Compose and send an email from the Gmail app — including the recipient-suggestion commit step that trips up naive automation
whenToUse: when the user asks to email someone or send a message/file by mail
---
1. If you already know the full address, subject, and body, prefer the deterministic route:
   open_deeplink with `mailto:<address>?subject=<subject>&body=<body>` (URL-encoded) opens a
   pre-filled compose screen in one step. Otherwise launch_app Gmail and tap Compose
   (perceive_screen first to get its som_id).

2. THE TO FIELD — the step that silently fails if done wrong:
   - Tap the To field, then type_text the address (or the contact's name — Gmail's
     suggestions search the user's contacts as you type).
   - Typing alone does NOT select the recipient. A suggestion list appears under the field,
     and you MUST tap the suggestion that shows the SAME address (or the intended contact).
     Only after that tap does the recipient turn into a chip — and only then do the Subject
     and body fields behave normally for the rest of the compose flow.
   - So: type → perceive_screen → tap the matching suggestion → confirm via the
     post_action_observation (or a quick perceive) that the address is now a chip in the
     To row. If you skip the suggestion tap, Send will go out with no recipient or the
     draft will refuse to send.
   - No suggestion matches what you typed? Re-check the spelling character by character —
     a typo in the address means the intended suggestion can never appear.

3. Tap the Subject field and type_text the subject. Then tap the body ("Compose email")
   and type_text the message. Perceive between fields if the observation doesn't confirm
   focus moved where you expected.

4. Attachments: tap the attach (paperclip) icon, then pick the file from the picker
   (find_files/open_file can locate it first if the user named a file).

5. Review before sending — sending is consequential. The draft must show the recipient
   CHIP, the subject, and the body. If anything is missing, fix it; if the user's intent
   was ambiguous, ask_user. Then tap Send.

6. perceive_screen once more to verify the message was sent (compose screen gone,
   "Sent" toast or the inbox visible) before end_session.
