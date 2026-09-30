package com.aura.mcp.tools

import com.aura.mcp.bridge.DeviceBridge
import com.aura.mcp.cache.PerceptionCache
import com.aura.mcp.server.ResourceRequired
import com.aura.mcp.server.scopedTool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Phase 3 — app-management tools: launch_app, lookup_app, type_text.
 */
internal fun Server.registerAppTools(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    registerLaunchApp(bridge)
    registerLookupApp(bridge)
    registerTypeText(bridge, perceptionCache)
}

private fun Server.registerLaunchApp(bridge: DeviceBridge) {
    scopedTool(
        name = "launch_app",
        // Cut: "prefer a deep link over launching then navigating" (Doctrine route_by_determinism)
        // and "banking apps are refused for safety" (Doctrine safety_blocks). Kept the
        // already_foreground contract, which nothing else states and which reads as a failure.
        description = "Open an installed app by name (app_name, e.g. \"Amazon\") or package_name. If the name " +
            "is ambiguous, the error lists candidates — call again with the right package_name. If " +
            "the app is already on screen it returns already_foreground and leaves the screen as it " +
            "is; that is success. force restarts it at its home screen — only when the user asked.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "app_name" to stringSchema(
                        "Human-readable app name (e.g. \"Amazon\", \"Spotify\") — resolved server-side",
                    ),
                    "package_name" to stringSchema(
                        "Android package name (e.g. com.android.chrome) — use when you already know it",
                    ),
                    "force" to booleanSchema(
                        "Relaunch even if already foreground, resetting it to its home screen. " +
                            "Default false; pass true only if the user asked to restart the app.",
                    ),
                ),
            ),
            required = emptyList(),
        ),
    ) { request ->
        val outcome = LaunchAppResolution.resolve(
            packageName = request.arguments?.stringArg("package_name"),
            appName = request.arguments?.stringArg("app_name"),
            lookup = bridge::lookupApp,
        )
        val force = request.arguments?.boolArg("force") ?: false
        when (outcome) {
            is LaunchAppResolution.Outcome.Launch -> {
                val extra = buildMap {
                    put("action", "launch_app")
                    put("package_name", outcome.packageName)
                    outcome.resolvedFromAppName?.let { put("resolved_app_name", it) }
                }
                // F7 — the app is already on screen. Relaunching would fire the
                // launcher intent and throw away wherever the user actually is
                // (the reported "I'm already on the target screen but it starts
                // over" bug). Report success without acting; `force` is the
                // deliberate escape hatch for a real restart.
                val foreground = bridge.getDeviceStatus().foregroundPackage
                if (!force && foreground != null && foreground == outcome.packageName) {
                    jsonOkPayload(
                        buildJsonObject {
                            put("success", true)
                            put("action", "launch_app")
                            put("package_name", outcome.packageName)
                            put("already_foreground", true)
                            put(
                                "note",
                                "App was already in the foreground — nothing was launched and the " +
                                    "user's current screen is untouched. Call perceive_screen to see " +
                                    "where you are and continue from there. Only pass force=true if " +
                                    "the user explicitly wants the app restarted.",
                            )
                        },
                        success = true,
                    )
                } else {
                    jsonOk(bridge.launchApp(outcome.packageName), extra)
                }
            }
            is LaunchAppResolution.Outcome.Blocked -> jsonOkPayload(
                buildJsonObject {
                    put("success", false)
                    put("error", "policy_blocked")
                    put("tool", "launch_app")
                    put("category", outcome.category)
                    put("message", outcome.message)
                    put(
                        "hint",
                        "This is a hard safety block enforced on-device. Do not retry or attempt " +
                            "a workaround — tell the user to perform this action themselves.",
                    )
                },
                success = false,
            )
            is LaunchAppResolution.Outcome.Ambiguous -> jsonOkPayload(
                buildJsonObject {
                    put("success", false)
                    put("error", "ambiguous_app_name")
                    put("query", outcome.query)
                    putJsonArray("candidates") {
                        for (c in outcome.candidates) {
                            add(
                                buildJsonObject {
                                    put("package_name", c.packageName)
                                    put("app_name", c.appName)
                                },
                            )
                        }
                    }
                    put("hint", "Pick the right candidate and call launch_app again with its package_name.")
                },
                success = false,
            )
            is LaunchAppResolution.Outcome.Invalid -> errorResult(outcome.message)
        }
    }
}

private fun Server.registerLookupApp(bridge: DeviceBridge) {
    scopedTool(
        name = "lookup_app",
        description = "Find the package name for an app name: the best match and up to 10 candidates. " +
            "launch_app and open_deeplink already accept a name, so this is rarely needed.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf("app_name" to stringSchema("Human-readable app name to search for (case-insensitive)")),
            ),
            required = listOf("app_name"),
        ),
    ) { request ->
        val query = request.arguments?.stringArg("app_name")
        if (query.isNullOrBlank()) {
            errorResult("lookup_app requires non-empty 'app_name'")
        } else {
            val r = bridge.lookupApp(query)
            val payload = buildJsonObject {
                put("found", r.found)
                put("package_name", r.packageName)
                put("app_name", r.appName)
                putJsonArray("candidates") {
                    for (c in r.candidates) {
                        add(buildJsonObject {
                            put("package_name", c.packageName)
                            put("app_name", c.appName)
                        })
                    }
                }
                if (r.error != null) put("error", r.error)
            }
            CallToolResult(content = listOf(TextContent(payload.toString())), isError = !r.found)
        }
    }
}

private fun Server.registerTypeText(bridge: DeviceBridge, perceptionCache: PerceptionCache) {
    scopedTool(
        name = "type_text",
        // Cut: the works-in-every-app / keyboard_not_enabled paragraph (Doctrine typing_anywhere)
        // and the refused-content list (Doctrine safety_blocks). Kept the two things only this
        // tool knows: a search BAR som_id is auto-tapped, and a hidden keyboard is not a failure.
        description = "Type text into a field. Pass the field's som_id to type there; omit it to type into the " +
            "focused field. If the som_id is a search bar that opens a separate search screen, this " +
            "opens it and types there. After a search box or address bar, call press_enter. In fields " +
            "that show suggestions as you type (recipients, people, places), tap the matching " +
            "suggestion — typed text alone does not count. A hidden keyboard afterwards is normal. " +
            "Works in apps without a standard text field via the AURA Keyboard; error " +
            "keyboard_not_enabled means the user must turn it on in Settings — tell them, do not " +
            "retry.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "text" to stringSchema("Text to type into the field"),
                    "som_id" to numberSchema(
                        "Optional: som_id of the target field from the latest screen. When given, types " +
                            "into that exact field; when omitted, types into the currently focused field.",
                    ),
                ),
            ),
            required = listOf("text"),
        ),
    ) { request ->
        val text = request.arguments?.stringArg("text")
        val somId = request.arguments?.intArg("som_id")
        if (text == null) {
            errorResult("type_text requires 'text' argument")
        } else {
            val ok = if (somId != null) {
                when (val res = perceptionCache.resolveForGesture(somId)) {
                    is SomResolveOutcome.Error -> return@scopedTool res.result
                    is SomResolveOutcome.Coords -> bridge.typeTextAt(text, res.x, res.y)
                }
            } else {
                bridge.typeText(text)
            }
            // Decision-time reinforcement (logged Gmail run: typed the recipient,
            // then re-tapped fields instead of committing the suggestion): the
            // hint rides the result the model reads while deciding its next call.
            // Success and failure get DIFFERENT guidance — the old code shipped the
            // "press_enter NOW" hint on failure too, steering the agent to commit
            // text that was never typed.
            jsonOk(
                ok,
                if (ok) {
                    mapOf(
                        "action" to "type_text",
                        "length" to text.length.toString(),
                        // F15: the old "press_enter NOW, do not tap the suggestion" was wrong on
                        // Maps, where the top suggestion is often not the place the user named.
                        "hint" to "If a suggestion list appeared, commit the entry that matches what " +
                            "the user asked: tap it, or press_enter when the top one is that match. " +
                            "Do not re-tap the field.",
                    )
                } else if (!bridge.isTextInjectionKeyboardEnabled()) {
                    // The app has no editable accessibility node (React Native / Flutter /
                    // canvas) so typing needs AURA's own keyboard — which the user hasn't
                    // enabled yet. This is a one-time setup, not a per-field problem.
                    // Same family as `resource_required` (see [ResourceRequired]) but reported
                    // through this tool's own map shape, which callers already parse. The two
                    // fields that matter are carried verbatim: `resource` for the app side to
                    // branch on, and `fixable_by: user` because no tool can enable an IME —
                    // only the person holding the phone can, once, in system settings.
                    mapOf(
                        "action" to "type_text",
                        "error" to ResourceRequired.ERROR_CODE,
                        "resource" to ResourceRequired.Resource.AURA_KEYBOARD,
                        "fixable_by" to ResourceRequired.Fixer.USER.id,
                        "hint" to "This app has no standard text field, so AURA must type with its " +
                            "own keyboard — but the AURA keyboard is not enabled yet. Only the user " +
                            "can turn it on, so do not retry. Tell them to enable it once: " +
                            "Settings → Permissions/Setup → AURA Keyboard (or system Languages & " +
                            "input → On-screen keyboards → enable AURA Keyboard). " +
                            "After that, type_text will work in every app.",
                    )
                } else {
                    mapOf(
                        "action" to "type_text",
                        "error" to "no_editable_field",
                        "hint" to "No focused editable field accepted the text. Tap the input " +
                            "field first to focus it, then retry type_text. If the tree shows " +
                            "no editable elements, perceive_screen and tap the field by som_id.",
                    )
                },
            )
        }
    }
}
