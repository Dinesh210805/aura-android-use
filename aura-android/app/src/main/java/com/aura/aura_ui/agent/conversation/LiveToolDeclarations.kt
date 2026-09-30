package com.aura.aura_ui.agent.conversation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The FIXED set of Gemini Live function declarations (declared at connect, never mutated
 * mid-session).
 *
 * ### Six tools, down from fifteen
 *
 * The Live model is a speech model — on the native-audio path it runs with thinking disabled
 * entirely, because native-audio rejects `thinkingConfig`. Asking it to choose between fifteen
 * tools was asking it to do the one thing it cannot do. The defensive prose that built up in the
 * old descriptions ("never reuse this phrasing for anything else", "call this rather than
 * guessing") is the fossil record of it getting those boundaries wrong.
 *
 * So the surface is now: four instant device controls it can dispatch itself, one universal
 * [ASK_AURA] for everything requiring judgment, and [END_CONVERSATION] — which is a control
 * signal, not a decision. Live expresses intent; [BrainLane] decides.
 *
 * **Nothing was removed from the product.** Memory, reminders, web search, notifications, system
 * intents and phone driving are all still reachable — through `ask_aura`, which escalates to the
 * action plane where every one of those bridges is already mounted on the in-process MCP server.
 */
object LiveToolDeclarations {

    /**
     * The universal tool: everything that is not an instant device knob.
     *
     * Declared NON_BLOCKING because it MIGHT go long — but unlike the old `drive_phone` the
     * acknowledgement is DEFERRED. Nothing is sent until the handler reports it is escalating, so
     * an ordinary question still behaves exactly like a blocking tool and answers in one turn.
     */
    const val ASK_AURA = "ask_aura"

    // The fast lane — dispatched directly by CompanionDirectTools, no agent hop, sub-100 ms.
    const val DEVICE_CONTEXT = "device_context"
    const val MEDIA_CONTROL = "media_control"
    const val DEVICE_SETTINGS = "device_settings"
    const val DEVICE_ACTION = "device_action"

    /** The user is done talking: close the voice session and dismiss the assistant. */
    const val END_CONVERSATION = "end_conversation"

    fun names(): List<String> = listOf(
        ASK_AURA, DEVICE_CONTEXT, MEDIA_CONTROL, DEVICE_SETTINGS, DEVICE_ACTION, END_CONVERSATION,
    )

    /**
     * Which tools change device state, for the misheard-batch guard. See [ToolBatchGate].
     *
     * [DEVICE_CONTEXT] is absent because a misheard clock reading costs nothing; [ASK_AURA] is
     * absent because it carries its own one-task-at-a-time guard and its own reasoning about what
     * the user meant.
     */
    val STATE_CHANGING = setOf(DEVICE_SETTINGS, DEVICE_ACTION, MEDIA_CONTROL)

    // NOTE: `isLongRunning(name)` used to live here and was DELETED rather than updated. With one
    // universal tool, how long a call takes is no longer a property of its NAME — ask_aura covers
    // both "what's the capital of France" (a second) and "order me a coffee" (ninety). Only the
    // handler knows, and only after the brain lane has read the request, which is why the decision
    // to acknowledge early moved there. See EscalationSink.

    fun functionDeclarations(): JsonArray = buildJsonArray {
        addJsonObject {
            put("name", ASK_AURA)
            put("behavior", "NON_BLOCKING")
            put(
                "description",
                "Ask AURA's reasoning brain to handle anything that is not one of the instant " +
                    "device controls above: questions, remembering things, reminders, searching " +
                    "the web, reading notifications, calling or messaging someone, and anything " +
                    "at all involving apps or the screen. This is also how you SEE the phone — " +
                    "if the user asks what is on screen, or to read, check or look something up, " +
                    "send it here rather than guessing or saying you cannot see. While a task is " +
                    "running, send progress questions and any change of mind here too. Pass what " +
                    "the user wants in their own words; do not decide how it will be done.",
            )
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("request") {
                        put("type", "STRING")
                        put(
                            "description",
                            "What the user wants, in plain language. Include anything from the " +
                                "conversation needed to make sense of it.",
                        )
                    }
                }
                putJsonArray("required") { add("request") }
            }
        }
        addJsonObject {
            put("name", DEVICE_CONTEXT)
            put("description", "Current phone state: time, battery, foreground app, what's playing, wifi/bluetooth/DND. Read-only.")
            putJsonObject("parameters") { put("type", "OBJECT"); putJsonObject("properties") {} }
        }
        addJsonObject {
            put("name", MEDIA_CONTROL)
            put("description", "Control media playback in any app without the screen: play, pause, play_pause, next, previous, stop.")
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("command") { put("type", "STRING"); put("description", "play, pause, play_pause, next, previous, or stop.") }
                    putJsonObject("package_name") { put("type", "STRING"); put("description", "Target app package (optional; defaults to the active session).") }
                }
                putJsonArray("required") { add("command") }
            }
        }
        addJsonObject {
            put("name", DEVICE_SETTINGS)
            put(
                "description",
                "Change a device setting: flashlight, dnd, volume, brightness (applied directly); " +
                    "wifi, bluetooth, airplane, battery_saver, nfc (opens the settings panel to " +
                    "toggle). One setting per turn — if the user asked for several, do the first " +
                    "and confirm the rest with them.",
            )
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("setting") { put("type", "STRING"); put("description", "flashlight, dnd, volume, brightness, wifi, bluetooth, airplane, battery_saver, or nfc.") }
                    putJsonObject("state") { put("type", "STRING"); put("description", "on/off/toggle; volume: up/down/mute; brightness: dim/bright.") }
                }
                putJsonArray("required") { add("setting") }
            }
        }
        addJsonObject {
            put("name", DEVICE_ACTION)
            put("description", "A device navigation action: home, back, recents, or take_photo.")
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("action") { put("type", "STRING"); put("description", "home, back, recents, or take_photo.") }
                }
                putJsonArray("required") { add("action") }
            }
        }
        addJsonObject {
            put("name", END_CONVERSATION)
            // A tool, not phrase-matching on "bye": phrase detection fires on "ok bye for now,
            // anyway can you also…" and misses every goodbye phrased in another language — which
            // matters here, because the persona deliberately follows a code-mixed speaker.
            put(
                "description",
                "End the conversation and close the assistant. Call this when the user says " +
                    "goodbye, says they are done, or otherwise clearly wants to stop talking. " +
                    "Say your short farewell in the SAME turn as this call. A phone task already " +
                    "running is NOT cancelled — it finishes on its own, so you can sign off " +
                    "without abandoning their work.",
            )
            putJsonObject("parameters") {
                put("type", "OBJECT")
                putJsonObject("properties") {
                    putJsonObject("farewell") {
                        put("type", "STRING")
                        put("description", "Optional short parting line, for the log.")
                    }
                }
            }
        }
    }
}
