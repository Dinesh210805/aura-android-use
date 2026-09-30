---
name: Aura Skill
description: Core operating doctrine — research a task before acting, keep the world state fresh, and never lose context mid-run
whenToUse: before starting any multi-step, unfamiliar, or error-prone automation; or after two consecutive failed/blocked actions
---
# How to run a task well

## 1. Research BEFORE you act (web_search first)
For any in-app flow you are not 100% sure of, your FIRST tool call is web_search —
not launch_app, not perceive_screen.

- User says "create a WhatsApp group" → web_search("how to create a group in WhatsApp android")
- User says "enable dark mode in Instagram" → web_search("how to enable dark mode in Instagram app")

Phrase queries as "how to <task> in <app> android". Read the returned steps, keep
them as your plan, then execute step by step — verifying each against the live
screen. The search result is a MAP; the screen is the TERRITORY. When they
disagree, trust the screen (apps change their UI often).

Skip research only for trivial flows: open an app, tap one obvious control,
press back/home.

## 2. World state: the screen is ground truth, and it EXPIRES
Your model of the phone is only as good as your last observation.

- Every screen-mutating gesture SETTLES before returning and carries a
  `post_action_observation` block (foreground app, element count, keyboard,
  top labels, screen_changed). That block is your verification AND your
  planning input — read it instead of spending a wait_for or an extra
  perceive_screen to check what happened.
- som_ids are SINGLE-USE. They describe one captured screen. After any gesture,
  launch, deep link, scroll, or key press the server marks them stale and a
  som-targeted tap returns an error. Perceive when you need to TAP something
  new — the loop is: act → read observation → (perceive only to target) → act.
- wait_for is for LONG waits only (downloads, uploads, processing) or when the
  observation shows loading_indicator_present.
- Opening an app by name? launch_app(app_name="…") directly — no lookup_app
  round trip; ambiguity comes back as ranked candidates.
- Never act from memory. If you think "the button was at the bottom", you are
  about to make a mistake — perceive and read the fresh som_ids.
- Confirm consequential actions landed: the observation block usually proves
  it; verify_action(expected="...") is for genuinely ambiguous cases (sends,
  deletes, submissions).

## 3. Keep your context — the RUN LEDGER is your memory, use it
The annotated screenshot is REPLACED every turn, and older tool results are
compacted away on long runs. The harness keeps a RUN LEDGER block in your
context every turn (goal, your plan checklist, recent steps, facts, dead ends)
— it survives compaction; your recollection of earlier turns does not.

- Multi-step goal? Call set_plan with your step list FIRST (e.g. the steps
  web_search returned), then mark_step(index, status) as each completes.
  status=done needs evidence from the screen you are on (it is checked);
  a step you cannot do gets status=failed with why, and you move on.
- Key facts read off the screen (a name, a price, a setting's value) — record
  them via mark_step's note parameter; the pixels are gone next turn.
- Asked for several things ("check 10 posts", "the 3 cheapest", "read my
  unread mail")? Declare it in set_plan (deliverable + target_count), then call
  record_finding(item, quote) for EACH one as you read it — quote text copied
  exactly off that screen. Success is REFUSED while you have fewer findings
  than the target, and you write your answer from the findings, never from
  memory. Short of the target and out of options: end with outcome="partial"
  and say how many you actually covered.
- When your memory of earlier turns conflicts with the RUN LEDGER, trust the
  ledger.

Keep tool results small: ask narrow perceive descriptions, and prefer
get_ui_tree when you only need structure — smaller results mean more of your
history survives a long task.

## 4. When something fails, change strategy — never repeat
A failed or blocked action will fail again if repeated. Climb this ladder:
1. Re-perceive — the screen may have changed under you.
2. Scroll to reveal off-screen targets, then re-perceive.
3. press_back and try a different path (deep link, search bar, another tab).
4. web_search the specific problem ("where is X in <app> new UI").
5. Ask the user. Never invent a workaround for a policy_blocked error — those
   are hard safety blocks; tell the user to do that part themselves.

## 5. Finish honestly
Call end_session only after a final perceive_screen shows the goal achieved.
If you could not finish, say exactly what worked and what did not — never claim
an unverified success.
