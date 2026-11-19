> **Status:** in progress. **Written:** 2026-11-18. **Branch:** feat/agentcore.

# AURA's cursor moves and touches like a hand, and its pill says more

Interview and spec review skipped at the user's request (concrete ask, shipping soon); this file
is the record.

## Goal
The user compared AURA's on-screen cursor with cua-driver's: there the cursor travels smoothly to
where it will act, a tap has its own animation, and a swipe starts from where the cursor is. In
AURA the tap was injected the moment the arrow set off, so the arrow arrived ~240 ms after the
touch; a swipe teleported the arrow to its start; a tap was only a small squish; there were no
haptics. The pill under the cursor showed only the last phone action, so while AURA read the
screen or thought, it showed a stale line.

## End product
On a run: the arrow glides on a gentle arc to each target (180–520 ms by distance), and the tap
lands as it arrives: the arrow squishes and springs back, a white ring spreads from the tip, and
the phone gives a click. A swipe glides to its start, presses (small ring, tick haptic), drags
with its trail while slightly pressed, and springs back at the end. Between actions the pill says
"Looking at the screen", "Waiting for the app", "Searching the web" and similar.

## Out of scope
- The older fire-and-forget gesture paths (`dispatchGestureCommand`, `performTap` from the
  WebSocket and `MCPCommandRouter`): the arrow still sets off as they inject. Only the awaited
  paths the agents use wait for it.
- Showing the model's reasoning on the pill: it is not reliable text for the screen (see
  `AgentStatusRegistry`).

## Steps
- [ ] **1. The arrow arrives before the touch, on an arc, with a tap and a press.**
  - **What changes:** `accessibility/AgentCursorOverlay.kt` (`pointAt` and `trace` return the
    glide's ms; `glideMs`, `glidePath`, `tap`, `press`, `lift`, `ripple` (new));
    `accessibility/AuraAccessibilityService.kt` (`dispatchGestureAwait` and `strokesAwait` delay
    by that lead; `flashGestureTarget` returns it); `presentation/utils/HapticUtils.kt`
    (`performAgentTouchHaptic` (new)); `accessibility/TapFlashOverlay.kt` deleted (its ring is now
    the cursor's).
  - **Done when:** `AgentCursorPathTest` passes, and on the phone a run's taps land as the arrow
    arrives with ring and click, and a scroll glides to its start before dragging.
- [ ] **2. The pill says what happens between actions.**
  - **What changes:** `mcp-server` `ToolStatus.readSink` (new), called by `scopedTool` for READ
    tools; `services/StepPill.kt` (`onRead` (new)).
  - **Done when:** `StepPillTest` passes (reads show but stay out of the trail; unlisted reads
    leave the line), and on the phone the pill shows "Looking at the screen" between taps.

## Left out on purpose
- Rotating the arrow toward its direction of travel: cua-driver's default arrow does not.
- A spring physics library: an arc plus an overshoot interpolator reads the same.
- The plan step ("Step 2 of 4 · …") as a second pill line: built, and removed because it looked
  cluttered on the cursor.
