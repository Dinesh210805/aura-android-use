package com.aura.mcp.server

/**
 * Tools the on-device agent is not given, even though the server registers them.
 *
 * ### Why a tool can be worth registering and not worth offering
 *
 * The server has two audiences. **External MCP clients** — Claude Code, Cursor, a PC agent —
 * arrive with no system prompt of ours and need to discover what this device does. **The
 * on-device agent** arrives with `Doctrine` already in its context and pays for every tool
 * description on every single request, forever.
 *
 * A tool whose whole job is to tell a stranger how the device works is valuable to the first
 * audience and pure overhead to the second. Removing it from the agent's surface costs the
 * external lane nothing.
 *
 * ### The two costs, and the second one is the larger
 *
 * Tokens are the obvious cost: measured 2026-08-26, tool descriptions were 7,328 tokens on every
 * request, about twice the system prompt. The subtler cost is **choice**. Every tool in the list
 * is a candidate the model weighs each turn, and long-context retrieval at this tier runs about
 * 21.3% — so a tool that will never be the right answer does not merely cost its own bytes, it
 * makes the right tool marginally harder to find.
 *
 * ### The bar for removal
 *
 * Not "rarely used". Measured across the 2026-08-26 eval traces, 25 of 54 tools were ever called
 * — but `press_back` is the failure ladder's second rung and `media_control` is the deterministic
 * route for playback. Rare-but-essential is the normal shape of a good tool.
 *
 * The bar is: **calling it can never be the right move for this lane.** Each entry below states
 * why, and each reason is checkable rather than a judgement call.
 */
object AgentLaneTools {

    /**
     * Registered, offered to external MCP clients, withheld from the on-device agent.
     *
     * Keep this list short and keep every entry's justification falsifiable. A tool removed
     * because someone thought it looked unnecessary is a regression waiting for the one task
     * that needed it.
     */
    val EXCLUDED_FROM_AGENT: Set<String> = setOf(
        // 197 tok describing a playbook the agent already has. `Doctrine` is pushed into its
        // context every turn, so this can only ever return doctrine it is already holding —
        // and the measurement agrees: 0 calls in 850. Pull-based doctrine is dead at this tier.
        // An external client, which gets no Doctrine, still needs it.
        "get_usage_guide",

        // 149 tok for a pre-check that changes nothing. Its own description concedes the point:
        // "The same policy is hard-enforced on every call, so a blocked action is refused even
        // without this check." SensitivePolicy runs at the dispatch chokepoint before every
        // tool, so asking first can only cost a turn. An external client may still want to
        // check before committing to a plan.
        "validate_action",

        // Region scroll in raw pixels. scroll_up/down/left/right take a som_id to scroll inside
        // an element, which covers it without asking the model for coordinates it is told never
        // to pass. Outside clients keep it.
        "scroll_to",

        // A connectivity ping. The agent is already connected, in-process, by construction.
        "echo",

        // Pairing a REMOTE client to this device over WebRTC. The on-device agent is not a
        // remote client and has nothing to pair.
        "connect_device",
    )

    /** True when [toolName] should be offered to the on-device agent. */
    fun availableToAgent(toolName: String): Boolean = toolName !in EXCLUDED_FROM_AGENT
}
