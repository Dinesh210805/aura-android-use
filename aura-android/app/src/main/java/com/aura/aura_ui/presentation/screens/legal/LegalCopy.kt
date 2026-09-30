package com.aura.aura_ui.presentation.screens.legal

/**
 * In-app privacy policy, terms and open-source licence list. DRAFT text, pending legal review.
 * - Change together: the website copy at `aura-android-use-website/privacy.html` is generated from this
 *   file. Run `node scripts/gen_legal_page.mjs` after any edit and commit both.
 */

object LegalCopy {

    const val CONTACT_EMAIL = "dinesh210805@gmail.com"
    const val PRIVACY_URL = "https://aura-android-use.vercel.app/privacy"
    const val TERMS_URL = "https://aura-android-use.vercel.app/privacy#terms"

    // DRAFT — legal review pending.
    val PRIVACY = """
AURA — Privacy Policy
Last updated: 2026-09-26  (DRAFT — pending legal review)

Who we are
AURA is an on-device voice assistant that can see your screen and perform
actions for you when you ask it to.

No AURA server ever receives your content
There is no AURA-operated server that receives, stores, or processes your voice,
screen content, messages, or files. Everything AURA does runs directly on your
phone, or is sent directly from your phone to an AI provider YOU choose and
configure with YOUR OWN API key ("Bring Your Own Key").

AURA does operate one small backend, and it holds no content: an anonymous
record of the app itself — which phone model and Android version it is running
on, which AURA version, and when it last ran. It exists so we know which
devices a fix has reached and which ones a change would break. It is described
in full below, and you can switch it off.

What AURA processes, and where it goes
• Voice: audio you speak is transcribed so AURA can understand commands.
• Screen content: while running a task, AURA reads on-screen elements to decide
  what to tap or type. Banking and payment apps are always blocked.
• App inventory (only when you run "Scan for sensitive apps"): the names and
  package IDs of your installed apps are sent to your configured AI provider
  once, to suggest which ones to restrict. You review every suggestion before
  it takes effect.
• Notifications & media (only if you grant access): read to answer questions and
  perform actions you request. Banking/security notifications are filtered out
  before AURA ever sees them.
• Contacts (only if you grant access): matched ON-DEVICE to find a number when
  you say "message <name>". Contact numbers are never sent anywhere.
• Web research: to learn how a task is done on your phone, AURA may search the web
  (Google, or DuckDuckGo or Bing) in a hidden browser with a short "how do I…"
  question, and read the official help pages it finds. The question contains the
  task in general terms, your phone model, Android version, and the app's name
  and version. Quoted text and personal details (names, numbers, addresses,
  message text) are removed before it is sent, and screen content never is.
• Computers you pair (only if you use the MCP server): they reach the phone
  directly over your local network (the same Wi-Fi, or a private VPN you set
  up), encrypted end to end. Nothing about that connection passes through any
  AURA server.

Apart from those web-research questions, everything above — voice, screen
content, app inventory — goes ONLY to the AI provider (e.g. Google Gemini, Groq,
OpenAI, Anthropic, OpenRouter, or a custom endpoint) that YOU configured with YOUR OWN key, and only the minimum needed to
complete the request you asked for. AURA does not see, log, or retain a copy of
it beyond what's needed to complete that request.

Third-party AI providers — your key, their responsibility
Entering an API key creates a DIRECT relationship between your device and that
provider; AURA is not a party to it. That provider's own privacy policy and
terms govern what they do with anything you send them (retention, training use,
regional storage, etc.), and AURA has no visibility into or control over their
practices. Choose your provider carefully and review their policy before use.
AURA is not responsible for a third-party provider's handling of your data —
that risk sits between you and the provider you selected, not with AURA.

Keys & secrets
Provider API keys you enter are stored in Android's encrypted, on-device
preferences (EncryptedSharedPreferences) and are sent only to the provider you
configured — never to AURA or anyone else.

Anonymous diagnostics — the complete list
AURA uses Firebase Crashlytics and Analytics for crashes and feature usage, and
records one row per install so we can see the fleet. Together they collect,
exhaustively:
• the Google account email you sign in with, and the name you told AURA to call
  you. AURA requires a signed-in account; the email is how an install is tied to
  a real person, and it is the only thing here that is directly about you rather
  than about your phone.
• a random per-install ID that resets when you uninstall
• a one-way hash of your device's Android ID — never the ID itself. It is the
  only thing here that survives reinstalling AURA, and it exists for one
  reason: so a device that abuses AURA can be blocked and cannot simply
  reinstall or sign in with another account to get back in. It is salted so it
  cannot be matched against any other app's view of your device.
• phone make, model and Android version
• AURA's version, and whether it is a debug or release build
• your language and time zone
• when AURA was installed, how many times the app has been launched, and when
  it last ran
• usage counts: how AURA was opened (app, wake word, volume keys, assist
  gesture); for each task, whether it finished, failed or was cancelled, how many
  steps it took, a rough duration (such as "10-30s"), a failure category (such as
  "network" or "rate limit"), and which built-in AI provider ran it ("custom" for
  one you added)
• computer connections: the computer's operating system type, a rough session
  length and how many tool calls it made; pairings approved or refused
• which onboarding step you reached
• crash stack traces, and per-tool metrics (which tool ran, how long it took,
  whether it succeeded)

That is everything. Never your task content, screen content, tool arguments,
tool results, messages, contacts, files, or location. AURA asks Google only for
your identity — never for access to Gmail, Drive, Calendar or your contacts. There is no advertising
ID, no IMEI, no serial number, and nothing that identifies you across apps.
The device hash above is the one value that survives a reinstall, and it is
useless outside AURA.

Turn it off with Settings → Privacy & Data → "Share anonymous diagnostics".
That stops all future reporting. One check keeps running either way: about once
a day AURA asks our backend whether this device or account has been blocked. It
sends only the device hash above and an anonymous account ID, and records
nothing. To have an already-recorded row deleted,
email us — the app deliberately cannot delete rows itself, because an app that
could delete them could also be made to delete someone else's.

Data we sell
None. AURA does not sell your data, and never will.

Your controls
Revoke any permission in Android Settings at any time. Clear stored memory,
learnings and activity logs from within the app. Remove a provider key at any
time to stop that provider from receiving anything further.

Contact
Questions? Email $CONTACT_EMAIL.
""".trim()

    // DRAFT — legal review pending.
    val TERMS = """
AURA — Terms of Service
Last updated: 2026-08-04  (DRAFT — pending legal review)

Acceptable use
Use AURA only for lawful purposes and only to automate things you are allowed to
do on your own device and accounts. Do not use AURA to bypass security controls
or to access accounts that are not yours.

You bring your own AI provider
AURA requires you to configure your own API key with a third-party AI provider
(e.g. Google Gemini). That provider's terms of service and privacy policy apply
to everything they receive from your device through AURA, in addition to these
terms. You are solely responsible for choosing a provider, complying with their
terms, and any cost, quota, or data-handling consequences of that choice. AURA
has no relationship with, and exercises no control over, your chosen provider.

AI can make mistakes
AURA is powered by AI and can misunderstand instructions or act incorrectly.
Always review important or irreversible actions. Sensitive actions ask for your
confirmation before proceeding, and banking/payment apps are hard-blocked at the
OS level.

No warranty, limitation of liability
AURA is provided "AS IS", without warranties of any kind, express or implied. To
the maximum extent permitted by law, the developer is not liable for any direct,
indirect, incidental, or consequential damages arising from your use of the app,
from any action AURA takes on your instruction, or from any third-party AI
provider's handling of data you sent them. You use AURA, and any provider you
configure, entirely at your own risk.

Changes
These terms may be updated; continued use after an update means you accept the
new version.

Contact
$CONTACT_EMAIL.
""".trim()

    /** library name -> license. Seeded with the app's key dependencies. */
    val LICENSES: List<Pair<String, String>> = listOf(
        "Jetpack Compose" to "Apache License 2.0",
        "AndroidX Navigation Compose" to "Apache License 2.0",
        "Material Components (Material 3)" to "Apache License 2.0",
        "Kotlin & Coroutines" to "Apache License 2.0",
        "MCP Kotlin SDK" to "MIT License",
        "Ktor" to "Apache License 2.0",
        "kotlinx.serialization" to "Apache License 2.0",
        "ONNX Runtime (Android)" to "MIT License",
        "AndroidX Lifecycle" to "Apache License 2.0",
    )
}
