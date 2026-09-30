package com.aura.aura_ui.agent

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * What phone this is, in the words a person — and a search engine — would use.
 *
 * ### Why this exists at all
 *
 * `get_device_status` already returns `device_model` and `android_api_level`, but two things make
 * it the wrong source for a search query. It costs a turn, so the model only has the facts if it
 * thought to ask for them — and by then it has usually already written the query. And its
 * `device_model` is `"$MANUFACTURER $MODEL"`, which on this project's own test device reads
 * **"OnePlus CPH2661"**: a code name that no help page, forum post or vendor article uses.
 * "How to do X on a OnePlus CPH2661" finds nothing; "on a OnePlus Nord 4" finds the answer.
 *
 * So the facts ride the system prompt instead, the way the user's name and languages already do.
 *
 * ### Where the friendly name comes from, and why in this order
 *
 * The discriminating property is **who can edit it**, not which one exists:
 *
 *  1. The OEM's marketing-name property. Read-only, set at the factory, and exactly the name on
 *     the box. Measured on the test device: `ro.vendor.oplus.market.name` = `OnePlus Nord 4`
 *     (while `ro.product.marketname` is empty there — hence a list, not one name).
 *  2. [Settings.Global.DEVICE_NAME]. Also `OnePlus Nord 4` out of the box, but it is the
 *     Bluetooth/hotspot name and the user can rename it. A phone called "Dinesh's Phone" would
 *     poison every query, which is why this ranks below the factory property rather than above it
 *     for being the easier read.
 *  3. `"$MANUFACTURER $MODEL"` — always available, never wrong, sometimes a code name.
 *
 * Every read is fail-soft: a blocked reflective call, a missing property or a broken Settings
 * provider falls through to the next candidate, and the last one cannot fail.
 */
data class DeviceFacts(
    /** The name on the box when the phone knows it — "OnePlus Nord 4". */
    val name: String,
    /** Who made it — "OnePlus". */
    val maker: String,
    /** The model code — "CPH2661". Kept because help pages and forums index by it too. */
    val model: String,
    /** Android version as a human says it — "16". */
    val androidRelease: String,
    /** The OEM build the user sees under About phone — "16.0.5". Null when nothing readable. */
    val softwareVersion: String?,
)

/**
 * Read the facts off this handset. Cheap enough for once-per-run; nothing here does I/O beyond a
 * Settings lookup and a few property reads.
 */
fun readDeviceFacts(context: Context): DeviceFacts {
    val maker = sanitizeDeviceField(Build.MANUFACTURER) ?: "unknown"
    val model = sanitizeDeviceField(Build.MODEL) ?: "unknown"
    val friendly = MARKETING_NAME_PROPS.firstNotNullOfOrNull { systemProperty(it) }
        ?: sanitizeDeviceField(runCatching {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull())
        ?: "$maker $model"
    return DeviceFacts(
        name = friendly,
        maker = maker,
        model = model,
        androidRelease = sanitizeDeviceField(Build.VERSION.RELEASE) ?: Build.VERSION.SDK_INT.toString(),
        softwareVersion = ROM_VERSION_PROPS.firstNotNullOfOrNull { systemProperty(it) }
            ?: sanitizeDeviceField(Build.DISPLAY),
    )
}

/**
 * The phone's identity, for the SYSTEM prompt.
 *
 * Pure — unit-tested in `DeviceFactsTest`. Rendered next to `ProfileBlock`, which carries the
 * other half of "who and what you are working with".
 *
 * The instruction line is here rather than in `Doctrine` on the one-home rule: it is only actionable
 * because these facts are on the page above it, and doctrine renders on devices where this block
 * may be empty. `assets/skills/research-the-web.md` stays the home for query shape in general.
 */
object DeviceBlock {
    fun render(facts: DeviceFacts): String = buildString {
        append("# This phone\n")
        append(facts.name)
        // The code name only earns its place when it is not already the friendly name.
        if (!facts.name.contains(facts.model, ignoreCase = true)) append(" (${facts.maker} ${facts.model})")
        append(", Android ${facts.androidRelease}")
        facts.softwareVersion?.let { append(", software $it") }
        append(".\n")
        append("For anything in this phone's own Settings or its maker's apps, name the phone and ")
        append("Android version in your search query — the steps differ by maker and version.")
    }
}

/** Where OEMs put the name printed on the box. First non-blank wins. */
private val MARKETING_NAME_PROPS = listOf(
    "ro.product.marketname",
    "ro.vendor.oplus.market.name",
    "ro.config.marketing_name",
)

/** Where OEMs put the skin build the user sees under About phone. First non-blank wins. */
private val ROM_VERSION_PROPS = listOf(
    "ro.build.version.oplusrom.display",
    "ro.miui.ui.version.name",
    "ro.build.version.emui",
    "ro.vivo.os.version",
)

/**
 * Read a system property, or null.
 *
 * `android.os.SystemProperties` is a hidden class, so this is reflective and may simply be denied
 * on a future release — `runCatching` is the whole error policy, because a denied read is not an
 * error here, it is the signal to fall through to the next candidate.
 */
private fun systemProperty(key: String): String? = runCatching {
    val cls = Class.forName("android.os.SystemProperties")
    cls.getMethod("get", String::class.java).invoke(null, key) as? String
}.getOrNull().let(::sanitizeDeviceField)

/**
 * Trim, drop blanks, strip control characters and cap the length.
 *
 * [Settings.Global.DEVICE_NAME] is user-typed free text landing in a SYSTEM-authority prompt —
 * the one field here that does not come from the factory — so a newline in it must not be able to
 * fake a second prompt line. Same treatment as `SkillDiscoveryListing.sanitize`, applied to every
 * source rather than just that one so nothing depends on remembering which is which.
 */
internal fun sanitizeDeviceField(raw: String?): String? = raw
    ?.replace(Regex("""[\p{Cntrl}]"""), " ")
    ?.replace(Regex(""" {2,}"""), " ")
    ?.trim()
    ?.take(64)
    ?.ifBlank { null }
