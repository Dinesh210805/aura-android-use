---
name: Act Without The Screen
description: Deterministic one-call routes — system_intent verbs, notification actions, media control, deep links — that beat opening apps and tapping
whenToUse: for alarms, timers, calls, SMS, calendar events, sharing, navigation, replying to notifications, or controlling music/media
---
# Prefer a typed call over gesture navigation

A typed tool call is deterministic — it cannot mis-tap, needs no perceive, and finishes
in one step. Before you launch an app and start tapping, check whether one of these
covers the goal:

## system_intent — Android's standard verbs
- set_alarm (hour, minute, label) and set_timer (seconds, label): applied DIRECTLY by
  the clock app. "Wake me at 7" is one call, not a Clock-app expedition.
- dial (phone_number): opens the dialer pre-filled — the USER places the call.
- compose_sms (body, phone_number): opens the SMS composer pre-filled — the user sends.
- add_calendar_event (title, start, end, location, notes): opens the calendar editor
  pre-filled with ISO datetimes like 2026-07-10T15:00.
- share_text (text, subject): opens the share sheet.
- navigate (destination, mode drive/walk/bike/transit): opens maps routing.
Use resolve_contact first when the user gave a name instead of a number.

## Notifications — read and act without opening any app
- read_notifications lists what's on the shade (sender, text, available actions).
- notification_action can tap a notification's own buttons — reply to a message,
  archive a mail, pause navigation — with zero app navigation.
- dismiss_notification clears one. "What did X message me?" or "reply to that" should
  start here, not with launch_app.

## Media — control playback directly
- get_media_sessions shows what's playing and where.
- media_control (play/pause/next/previous/stop) acts on it in one call — never open the
  music app to tap the pause button. volume_up/volume_down/mute are also one-call tools.

## Deep links — jump to the exact screen
- list_app_deeplinks / resolve_deeplink discover an app's registered entry points;
  open_deeplink jumps straight there (including app-shortcut:// shortcuts, mailto:,
  wa.me). One deep link can replace an open-app-search-navigate chain.

## When NOT to use these
If the goal needs content the verb can't carry (rich text, attachments, in-app-only
settings), or the deterministic route fails, fall back to normal perceive-and-act —
and say so in your plan so the ledger records why.
