# Security policy

AURA can read your screen and act in your apps, so security bugs matter here.

## Report a vulnerability

Please **don't open a public issue.** Use GitHub's private vulnerability reporting instead:
the **Security** tab of this repository → **Report a vulnerability**.

Include what an attacker could do, the steps to reproduce it, and the AURA (and `aura-mcp-connect`)
version. You'll get a reply within a week. Once a fix ships, you're credited in the release notes
unless you'd rather not be.

## Supported versions

Only the [latest release](https://github.com/Dinesh210805/aura-android-use/releases/latest) of the
app and the latest `aura-mcp-connect` on npm get security fixes.

## In scope

- The Android app and its in-process MCP server (`aura-android/`).
- The desktop bridge (`aura-mcp-connect/`) and phone pairing.
- Ways around the safety gate: opening blocked apps, typing card numbers or passwords, sending data
  off the phone without the approval card.

Bugs in an AI provider or in a third-party app AURA drives are out of scope; report those to their owners.
