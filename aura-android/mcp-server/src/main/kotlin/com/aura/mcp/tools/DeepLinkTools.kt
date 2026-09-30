package com.aura.mcp.tools

import com.aura.mcp.bridge.AppLookupResult
import com.aura.mcp.bridge.DeepLinkBridge
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

/**
 * Deep-link tools — the "express lane" for the agent.
 *
 * A deep link collapses a fragile launch→wait→tap→type→tap gesture chain into
 * one deterministic `Intent`. These three tools mirror the perceive→act→verify
 * discipline the rest of the server already enforces:
 *
 *  - [registerListAppDeeplinks] — **perceive**: what links does this app expose?
 *  - [registerResolveDeeplink]  — **verify-before**: would this URI resolve?
 *  - [registerOpenDeeplink]     — **act**: fire it (guarded).
 *
 * Discovery (`list` / `resolve`) is READ; firing (`open`) is WRITE. See
 * [com.aura.mcp.server.McpToolScopes].
 */
internal fun Server.registerDeepLinkTools(
    bridge: DeepLinkBridge,
    // E3 — optional app-name→package lookup (DeviceBridge::lookupApp); null on
    // hosts without app lookup, where open_deeplink then requires package_name.
    lookupApp: ((String) -> AppLookupResult)? = null,
) {
    registerListAppDeeplinks(bridge)
    registerResolveDeeplink(bridge)
    registerOpenDeeplink(bridge, lookupApp)
}

private fun Server.registerListAppDeeplinks(bridge: DeepLinkBridge) {
    scopedTool(
        name = "list_app_deeplinks",
        description = "List the deep links and shortcuts an app offers, to jump straight to a screen. Each " +
            "entry has an example_uri for open_deeplink, sometimes a uri_template with {slots} to " +
            "fill, and a source, most trustworthy first: verified > shortcut > app_action > resolved " +
            "> catalog > discovered. Pass app-shortcut:// URIs unchanged. Entries marked " +
            "requires_confirmation can send, pay, delete or sign in — ask the user before opening " +
            "one.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "package_name" to stringSchema(
                        "Android package name to inspect (e.g. com.spotify.music).",
                    ),
                ),
            ),
            required = listOf("package_name"),
        ),
    ) { request ->
        val pkg = request.arguments?.stringArg("package_name")?.trim()
        if (pkg.isNullOrEmpty()) {
            return@scopedTool errorResult("list_app_deeplinks requires a non-empty 'package_name'")
        }

        val catalog = bridge.listDeepLinks(pkg)
        val payload = buildJsonObject {
            put("package_name", catalog.packageName)
            put("app_name", catalog.appName)
            put("entry_count", catalog.entries.size)
            put(
                "entries",
                buildJsonArray {
                    for (e in catalog.entries) {
                        add(
                            buildJsonObject {
                                put("example_uri", e.exampleUri)
                                put("uri_template", e.uriTemplate)
                                put("scheme", e.scheme)
                                put("host", e.host)
                                put("label", e.label)
                                put("source", e.source)
                                put("requires_confirmation", e.requiresConfirmation)
                                if (e.shortcutId != null) put("shortcut_id", e.shortcutId)
                            },
                        )
                    }
                },
            )
            put(
                "usage_hint",
                "Pick an entry whose label matches your goal. If it has a " +
                    "uri_template, fill the {slots} and call resolve_deeplink to " +
                    "confirm before open_deeplink. 'shortcut' entries take their " +
                    "example_uri verbatim — never edit an app-shortcut:// URI. " +
                    "Prefer higher-trust sources (verified > shortcut > resolved " +
                    "> catalog > discovered). After opening, read the " +
                    "post_action_observation, then perceive_screen before any " +
                    "som-targeted tap — the screen changed and old coordinates " +
                    "are stale.",
            )
        }
        CallToolResult(content = listOf(TextContent(payload.toString())), isError = false)
    }
}

private fun Server.registerResolveDeeplink(bridge: DeepLinkBridge) {
    scopedTool(
        name = "resolve_deeplink",
        description = "Check what a URI would open, without opening it. handler_kind: app (package_name says " +
            "which), browser_only (only a browser takes it — pick another entry to land inside the " +
            "app), none (nothing handles it).",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "uri" to stringSchema(
                        "Full URI to test, e.g. 'https://open.spotify.com/' or " +
                            "'android.settings.WIFI_SETTINGS'.",
                    ),
                ),
            ),
            required = listOf("uri"),
        ),
    ) { request ->
        val uri = request.arguments?.stringArg("uri")?.trim()
        if (uri.isNullOrEmpty()) {
            return@scopedTool errorResult("resolve_deeplink requires a non-empty 'uri'")
        }

        val r = bridge.resolveUri(uri)
        val payload = buildJsonObject {
            put("uri", uri)
            put("resolves", r.resolves)
            put("package_name", r.packageName)
            put("scheme_allowed", r.schemeAllowed)
            put("handler_kind", r.handlerKind)
            put("reason", r.reason)
        }
        // Not an error result even when it doesn't resolve — "no" is a valid,
        // useful answer to a dry-run, not a tool failure.
        CallToolResult(content = listOf(TextContent(payload.toString())), isError = false)
    }
}

private fun Server.registerOpenDeeplink(
    bridge: DeepLinkBridge,
    lookupApp: ((String) -> AppLookupResult)?,
) {
    scopedTool(
        name = "open_deeplink",
        description = "Open a URI: http(s), an app's own scheme, or an app-shortcut:// URI from " +
            "list_app_deeplinks. Pass package_name or app_name so the right app handles it. intent:, " +
            "file:, content: and javascript: are refused.",
        inputSchema = ToolSchema(
            properties = JsonObject(
                mapOf(
                    "uri" to stringSchema(
                        "Full URI to open, e.g. 'https://open.spotify.com/search/jazz'.",
                    ),
                    "package_name" to stringSchema(
                        "Optional target package to pin the intent to (recommended " +
                            "when known, e.g. com.spotify.music).",
                    ),
                    "app_name" to stringSchema(
                        "Optional human-readable app name (e.g. 'Spotify') to pin " +
                            "the intent to — resolved on-device; no lookup_app round " +
                            "trip needed. Ignored when package_name is given.",
                    ),
                ),
            ),
            required = listOf("uri"),
        ),
    ) { request ->
        val args = request.arguments
        val uri = args?.stringArg("uri")?.trim()
        if (uri.isNullOrEmpty()) {
            return@scopedTool errorResult("open_deeplink requires a non-empty 'uri'")
        }
        val rawPkg = args.stringArg("package_name")?.takeIf { it.isNotBlank() }
        val appName = args.stringArg("app_name")?.takeIf { it.isNotBlank() }

        // E3 — resolve an app_name to a pin package at dispatch time (like
        // launch_app); the RESOLVED package is screened inside the resolution.
        val pkg: String? = when (
            val target = OpenDeeplinkTarget.resolve(
                rawPkg,
                appName,
                lookupApp ?: { _ ->
                    AppLookupResult(
                        found = false,
                        packageName = "",
                        appName = "",
                        candidates = emptyList(),
                        error = "app lookup not available on this host",
                    )
                },
            )
        ) {
            null -> null
            is LaunchAppResolution.Outcome.Launch -> target.packageName
            is LaunchAppResolution.Outcome.Blocked -> return@scopedTool jsonOkPayload(
                buildJsonObject {
                    put("success", false)
                    put("error", "policy_blocked")
                    put("tool", "open_deeplink")
                    put("category", target.category)
                    put("message", target.message)
                    put(
                        "hint",
                        "This is a hard safety block enforced on-device. Do not retry or attempt " +
                            "a workaround — tell the user to perform this action themselves.",
                    )
                },
                success = false,
            )
            is LaunchAppResolution.Outcome.Ambiguous -> return@scopedTool jsonOkPayload(
                buildJsonObject {
                    put("success", false)
                    put("error", "ambiguous_app_name")
                    put("query", target.query)
                    put(
                        "candidates",
                        buildJsonArray {
                            for (c in target.candidates) {
                                add(
                                    buildJsonObject {
                                        put("app_name", c.appName)
                                        put("package_name", c.packageName)
                                    },
                                )
                            }
                        },
                    )
                    put("hint", "Call open_deeplink again with the exact package_name of the intended app.")
                },
                success = false,
            )
            is LaunchAppResolution.Outcome.Invalid -> return@scopedTool errorResult(target.message)
        }

        // GP2 — resolve the target FIRST and screen the RESOLVED package before
        // firing. The pre-dispatch policy gate only saw the raw URI + explicit
        // package_name; a benign-looking custom scheme can still resolve to a
        // payment/banking app. resolveUri fires nothing (dry run).
        val screening = DeepLinkOpenScreening.screen(bridge.resolveUri(uri))
        if (screening is DeepLinkOpenScreening.Outcome.Blocked) {
            return@scopedTool jsonOkPayload(
                buildJsonObject {
                    put("success", false)
                    put("error", "policy_blocked")
                    put("tool", "open_deeplink")
                    put("category", screening.category)
                    put("message", screening.message)
                    put(
                        "hint",
                        "This is a hard safety block enforced on-device. Do not retry or attempt " +
                            "a workaround — tell the user to perform this action themselves.",
                    )
                },
                success = false,
            )
        }

        val r = bridge.openUri(uri, pkg)
        jsonOkPayload(
            buildJsonObject {
                put("success", r.success)
                put("resolved_package", r.resolvedPackage)
                put("error", r.error)
                if (r.success) {
                    put(
                        "next_step",
                        "Screen changed — call wait_for then perceive_screen before acting.",
                    )
                }
            },
            success = r.success,
        )
    }
}
