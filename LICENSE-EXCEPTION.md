# Additional permission under GNU AGPL version 3, section 7

Copyright (C) 2026 Dinesh Kumar C

AURA is licensed under the GNU Affero General Public License, version 3 or (at your option) any
later version. The full licence text is in [`LICENSE`](LICENSE). This file grants one extra
permission on top of that licence, as section 7 of the AGPL allows.

## Why this exists

AURA runs on Android. The Android app links against some libraries that are **not** free software:
Google distributes them in binary form only, or under its own terms. The AGPL alone doesn't clearly
allow a work that combines AGPL code with closed libraries to be distributed, so without this file
nobody could legally share an AURA APK, including AURA's own releases. This permission covers
exactly those libraries and nothing more.

## The permission

If you modify this Program, or any covered work, by linking or combining it with any of the
libraries listed below (or a modified version of those libraries), containing parts covered by the
terms of those libraries' own licences, the licensors of this Program grant you additional
permission to convey the resulting work.

The libraries this permission covers:

1. **Google Play services** client libraries (`com.google.android.gms:*`), the
   `androidx.credentials:credentials-play-services-auth` bridge to them, and the Sign in with
   Google library (`com.google.android.libraries.identity.googleid:*`).
2. **Firebase** SDKs (`com.google.firebase:*`) and the parts of Google Play services they depend on.
3. **Google ML Kit** (`com.google.mlkit:*`) and the models it downloads or bundles.

Only the libraries listed above are covered. Any other library linked with AURA must be under a
licence compatible with the GNU AGPL version 3.

## What this does not change

- Everything else in the AGPL still applies to AURA's own code. If you distribute a modified AURA,
  or let users interact with it over a network, you must offer them its complete corresponding
  source under the AGPL.
- The permission doesn't cover the listed libraries' source code, and doesn't relicense them. They
  stay under their own terms, which you have to follow separately.
- If you modify AURA you may keep this permission in your version, or remove it. Section 7 of the
  AGPL allows either.
