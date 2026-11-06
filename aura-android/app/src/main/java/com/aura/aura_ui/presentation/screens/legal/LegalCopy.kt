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
Last updated: 2026-11-06  (DRAFT — pending legal review)

Who we are
AURA is an on-device voice assistant that can see your screen and perform
actions for you when you ask it to. It is built and run by one person, Dinesh
Kumar C.

No AURA server ever receives your content
There is no AURA-operated server that receives, stores, or processes your voice,
screen content, messages, or files. Everything AURA does runs directly on your
phone, or is sent directly from your phone to the services listed below, mainly
an AI provider YOU choose and configure with YOUR OWN API key ("Bring Your Own
Key").

AURA does operate one small backend, on Google Firebase, and it holds no content.
It is not anonymous: it holds your Google sign-in, including your email address,
and a record of each install: which phone model and Android version it is running
on, which AURA version, and when it last ran. It exists so we know which devices
a fix has reached and which ones a change would break, and so abuse can be
blocked. It is described in full below. Most of it is on by default and you can
switch it off, but your sign-in stays until you ask us to delete it.

What AURA processes, and where it goes
• Voice: if you turn the wake word on, listening for it happens on your phone and
  that audio is not sent anywhere. With the on-device voice model, your speech is
  transcribed on your phone too. Without it, AURA uses Android's speech recognizer:
  on the phone where that is available, otherwise your phone's default speech
  service, which may send your audio to its maker (often Google). In live
  conversation mode your audio goes to your AI provider. Spoken replies use the
  on-device voice or your phone's text-to-speech service.
• Screen content: while running a task, AURA reads on-screen elements and can take
  screenshots to decide what to tap or type. Known banking, payment and
  authenticator apps are blocked. That list cannot cover every app, and you can
  add your own restricted apps.
• App inventory: the names and package IDs of all your installed apps are sent to
  your configured AI provider only when you run "Scan for sensitive apps", to
  suggest which ones to restrict. You review every suggestion before it takes
  effect. During a task, the names of apps that match your request can also be sent.
• Notifications & media (only if you grant access): read to answer questions and
  perform actions you request. Notifications from known banking and security apps
  are filtered out before they reach any AI.
• Contacts (only if you grant access): matched ON-DEVICE when you say "message
  <name>", and your contact list is never uploaded. The names and numbers of the
  contacts that match are sent to the AI doing the task, so it can fill in the
  call or message.
• Memory: what AURA remembers about you, and what it learns about apps, is stored
  encrypted on your phone, with obvious personal details removed on a best-effort
  basis. The relevant parts are sent to your AI provider during a task.
• Web research: to learn how a task is done on your phone, AURA may search the web
  (Google, or DuckDuckGo or Bing) in a hidden browser with a short "how do I…"
  question, and read the official help pages it finds. The question contains the
  task in general terms, your phone model, Android version, and the app's name
  and version. Quoted text and personal details (names, numbers, addresses,
  message text) are removed before it is sent, and screen content never is. The
  sites involved see your IP address, as they would for any visit.
• Web search (only if you add a Tavily key): search queries written by the AI go
  to Tavily. They can include details from your request.
• Computers you pair (only if you use the MCP server): they reach the phone
  directly over your local network (the same Wi-Fi, or a private VPN you set
  up), encrypted end to end. Nothing about that connection passes through any
  AURA server. A paired computer receives what its tools return, including screen
  content, notifications and contact matches, and so does whatever AI it runs.
• MCP servers you add (only if you add one): the requests the agent makes to that
  server go straight to it, along with the token you gave it.
• Voice model downloads (only if you choose them): the files come from Hugging
  Face, which sees your IP address.

Apart from the services named above, voice, screen content and app names go ONLY
to the AI provider (e.g. Google Gemini, Groq, OpenAI, Anthropic, OpenRouter, or a
custom endpoint) that YOU configured with YOUR OWN key, and only while carrying
out something you asked for. None of it is copied to any AURA server.

On your phone, AURA keeps session logs of what each task did, including the tool
calls, the screenshots taken and what was sent to the AI, for 30 days by default.
You can change that or delete them. They stay on your phone unless you choose to
share them.

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
configured — never to AURA or anyone else. If a phone's encrypted storage fails,
AURA falls back to private app storage that is not encrypted.

Diagnostics — the complete list
AURA uses Firebase Crashlytics and Analytics for crashes and feature usage, and
records one row per install so we can see the fleet. These are on by default.
Together they collect, exhaustively:
• the Google account email you sign in with, and the name you told AURA to call
  you. AURA requires you to sign in with Google; the email is how an install is
  tied to a real person, and it is the only thing here that is directly about you
  rather than about your phone.
• a random per-install ID that resets when you uninstall
• a one-way hash of your device's Android ID — never the ID itself. It is the
  only device identifier here that survives reinstalling AURA, and it exists for
  one reason: so a device that abuses AURA can be blocked and cannot simply
  reinstall or sign in with another account to get back in. It is salted so it
  cannot be matched against any other app's view of your device.
• phone make, model and Android version
• AURA's version, and whether it is a debug or release build
• your language and time zone
• when AURA was installed, how many times the app has been launched, and when
  it last ran
• usage counts: how AURA was opened (app, wake word, volume keys, assist
  gesture); for each task, whether it finished, failed or was cancelled, how many
  steps it took, how many tool calls failed, a rough duration (such as "10-30s"),
  a failure category (such as "network" or "rate limit"), and which built-in AI
  provider ran it ("custom" for one you added)
• computer connections: the computer's operating system type, a rough session
  length and how many tool calls it made; pairings approved or refused
• which onboarding step you reached
• crash stack traces, and per-tool metrics (which tool ran, how long it took,
  whether it succeeded)
• what Google Analytics adds by itself: standard events such as first open and
  session start, and a rough location (such as country or city) it works out from
  your IP address. Firebase services see your IP address, as any server does.

That is everything. Never your task content, screen content, tool arguments,
tool results, messages, contacts, files, or precise location. AURA asks Google
only for your identity — never for access to Gmail, Drive, Calendar or your
contacts. There is no advertising ID, no IMEI, no serial number, and no ID that
tracks you across other apps. The device hash above is the one device identifier
that survives a reinstall, and it is useless outside AURA.

Turn it off with Settings → Privacy & Data → "Share diagnostics". That stops the
install row, Analytics and crash reports. Three things keep running either way:
• Your sign-in: Firebase Authentication keeps your Google account's email, name
  and profile photo link, which is what signing in means.
• A block check: about once a day AURA asks our backend whether this device or
  account has been blocked. It sends the device hash above, a hash of your account
  ID, and the account ID itself to read your own row, and records nothing.
• A settings check: at most every 15 minutes AURA fetches shutdown, minimum
  version and update notices from Firebase Remote Config, which sends a Firebase
  installation ID, AURA's version and standard request details such as language,
  time zone and Android version.

How long we keep it
Install rows and sign-ins stay until you ask us to delete them, and the hash of a
blocked device or account stays on the blocklist. Analytics and crash data are
kept for the periods set in Firebase. To get a copy of your row, or to have it and
your sign-in deleted, email us — the app deliberately cannot delete rows itself,
because an app that could delete them could also be made to delete someone else's.

What we publish
The website's download page shows totals worked out from those rows: how many
phones run AURA and how many are in each country, judged from time zone or
language. It publishes only totals, never anything about one install, though a
country's total can be as low as one.

Data we sell
None. AURA does not sell your data, and never will.

Your controls
Revoke any permission in Android Settings at any time. Clear stored memory,
learnings and activity logs from within the app. Remove a provider key at any
time to stop that provider from receiving anything further.

This website
The AURA website uses Vercel Web Analytics to count page visits. Its fonts load
from Google Fonts, and its home and download pages ask GitHub for the latest
release and download numbers, so those services see your IP address.

Contact
Questions? Email $CONTACT_EMAIL.
""".trim()

    // DRAFT — legal review pending.
    val TERMS = """
AURA — Terms of Service
Last updated: 2026-11-06  (DRAFT — pending legal review)

Acceptable use
Use AURA only for lawful purposes and only to automate things you are allowed to
do on your own device and accounts. Do not use AURA to bypass security controls
or to access accounts that are not yours.

Google sign-in
AURA requires you to sign in with a Google account during setup. The privacy
policy says what is kept and how to have it deleted.

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
confirmation before proceeding, and known banking and payment apps are blocked by
AURA's own safety rules. Those rules are not an operating-system guarantee and
cannot cover every app.

Blocking and required updates
The developer can block a device or account that abuses AURA, stop AURA for
everyone, or require an update before it keeps working. While any of these
applies, AURA will not run.

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
