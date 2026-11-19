> **Status:** in progress. **Written:** 2026-11-18. **Branch:** feat/agentcore.

# Connected apps stay on, and changed connector tools are not hidden silently

Interview and spec review skipped at the user's request (a bug report, shipping soon); this file
is the record.

## Goal
The user turns a Composio app on, closes AURA, opens it again, and the switch is off: the app
"seems dropped". And the agent does not use connectors for real work.

**Root cause.** `ConnectorsPage` calls `AgentPagesViewModel.recheck()` on every resume, which
asks Composio (`COMPOSIO_MANAGE_CONNECTIONS`) about each connected app. `ComposioAccounts.parse`
treated any `https` link anywhere in the reply as a sign-in link, and Composio's reply carries
links outside the toolkit's entry (`data.message`, `data.session.instructions`). A connected app
was read as "needs sign-in"; `check` then removed it from `connector_apps_<id>` and `recheck`
removed its row, so the switch showed off. The same set feeds the agent's toolset note
(`AgentCore` `toolsetNote`, "signed in: Gmail, …"), so the agent also stopped knowing the app was
connected and fell back to tapping the app's screens.

Separately, `ConnectorPins` hides any connector tool whose description or schema changed since
approval, and `needsReview` was never shown anywhere: when Composio updated its meta-tools, every
Composio tool vanished from the agent with no message, and only removing and re-adding the
connector brought them back.

## End product
An app the user turned on stays on across restarts. If Composio really wants a fresh sign-in, the
row stays on and says "Composio asks you to sign in again. Tap to do it." If a connector's tools
changed, the Connectors page shows "Composio updated 3 tools — AURA can't use them until you
allow the new version" with Allow. The agent prefers any signed-in app (mail, calendar, files,
docs, chat, notes, tasks, issues) over driving that app's screen.

## Out of scope
- Dropping the description from the pin fingerprint: a changed description is how a server could
  slip new instructions to the agent; the user approves it instead.

## Steps
- [x] **1. `parse` reads only the toolkit's own entry.** (2026-11-19: `ComposioAppsTest` 18/18 pass, the three named cases included.)
  - **What changes:** `agentcore/ComposioApps.kt` (`parse`, `statusOf`, `linkIn`, `summaryOf` (new)).
  - **Logic:** JSON reply: the entry's status at any depth (`status`, `connection_status`,
    `state`) decides active/failed; else a link under a link-named key of the entry (not logo,
    icon, image, docs); else `data.summary` when it covers one toolkit; else Failed. Plain text:
    a link means sign-in.
  - **Done when:** `ComposioAppsTest` passes, including "a link elsewhere in the reply does not
    make a connected app a sign-in", "the summary decides a one-app reply", "a logo is never
    taken for a sign-in link" (all three failed before the fix).
- [x] **2. Only the user's switch turns an app off.** (2026-11-19: on the phone, the app stayed on after closing AURA from recents and reopening Connectors.)
  - **What changes:** `ComposioAccounts.check` (adds on Active only), `AgentPagesViewModel.recheck`,
    `AppState.Waiting.again` (new), `ConnectorsUi.AppRow` wording.
  - **Done when:** on the phone, turn Gmail on, close AURA from recents, open Connectors again:
    Gmail is still on.
- [ ] **3. Changed connector tools wait for the user's Allow.**
  - **What changes:** `ConnectorStore.changedTools`, `ConnectorStore.approveCurrent` (new);
    `AgentPagesViewModel.changedTools`, `allowChangedTools` (new); `ConnectorsUi.ChangedTools` (new).
  - **Done when:** on the phone, a connector whose tools changed shows the notice, and after Allow
    the agent's toolset menu lists its tools again.
- [ ] **4. The agent prefers any signed-in app.**
  - **What changes:** route 4 in `ContextAssembler` `SystemPrompt`.
  - **Done when:** with Gmail signed in, "find my last email from Swiggy" runs
    `composio__COMPOSIO_SEARCH_TOOLS` / `MULTI_EXECUTE`, not the Gmail app's screen.

## Left out on purpose
- Re-checking connected apps less often: one call per app on resume is cheap, and the check is
  now harmless.
