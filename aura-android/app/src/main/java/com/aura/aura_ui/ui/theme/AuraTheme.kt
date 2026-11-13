package com.aura.aura_ui.ui.theme

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * AURA's look, app-wide, in two themes the user picks between ([StyleChoice]):
 * - Sky: MilkLogger's look. A muted gradient behind every page that follows the time of day
 *   ([SkyPalette]), frosted near-white glass cards with ink text in both modes, and one ink action
 *   colour. Dark mode changes only the sky. Text sitting on the sky uses [AuraColors.onSky].
 * - Classic: plain black and white, flat canvas, white or charcoal cards.
 *
 * - Contract: [AuraUITheme] provides [LocalAura]; every screen reads colours from it, directly or
 *   through `rememberMonoScheme`, `Capsule.scheme` and `rememberWarmScheme`, which are derived
 *   from it. Colour carries meaning only (the accent, danger, warning). Text inside a card uses
 *   [AuraColors.label]; text on the page itself uses [AuraColors.onSky].
 * - Reads/Writes: prefs file [AppearanceStore.PREFS] (mode, theme and accent).
 * - Change together: `Theme.kt` (Material colours), `Type.kt` (fonts), `README.md` in `presentation/`.
 */
data class AuraColors(
    /** True when cards are dark (Classic in dark mode). Sky cards are always light. */
    val isDark: Boolean,
    /** The page where no sky is drawn. */
    val canvas: Color,
    /** The sky on screen now; null in Classic. */
    val sky: SkyPalette?,
    /** Text drawn straight on the page (titles, the greeting), readable on [sky] or [canvas]. */
    val onSky: Color,
    val onSkyMuted: Color,
    /** Cards on the page. Translucent in Sky, so the sky tints them. */
    val surface: Color,
    /** Opaque card: dialogs, sheets, menus and the drawer, which sit over a scrim. */
    val surfaceStrong: Color,
    /** Wells inside a card, inactive controls. */
    val well: Color,
    /** Fill of a frosted panel over moving content (the composer). */
    val glass: Color,
    val glassEdge: Color,
    val hairline: Color,
    val label: Color,
    val secondary: Color,
    /** Values and hints. Never for a sentence the user must read. */
    val tertiary: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val success: Color,
    val danger: Color,
    val warning: Color,
)

/** How far down the sky's gradient runs; below it the page is the sky's last stop. */
val SKY_HEIGHT = 800.dp

private val INK = Color(0xFF0D1117)

/**
 * The sky behind every page in the Sky theme: a vertical gradient that follows the clock.
 * Palettes and hour keyframes are MilkLogger's (`lib/ui/theme/tokens.dart`), verbatim.
 *
 * - Contract: [at] interpolates between keyframes with an ease, so the sky drifts through the day;
 *   [header] snaps at the midpoint because half-grey text is unreadable on every sky.
 */
data class SkyPalette(val stops: List<Color>, val glow: Color, val header: Color) {
    val onSkyMuted: Color get() = header.copy(alpha = 0.72f)

    /** Lines drawn on the sky: the orbit rings. */
    val onSkyLine: Color get() = header.copy(alpha = if (header == INK) 0.16f else 0.3f)

    /** Whether text on this sky is ink, so the status bar icons should be dark too. */
    val darkText: Boolean get() = header == INK

    companion object {
        val POSITIONS = listOf(0f, 0.32f, 0.62f, 1f)
        private val W = Color.White

        private val dawn = SkyPalette(listOf(Color(0xFF8EA6C2), Color(0xFFC9B8C4), Color(0xFFEBD3C2), Color(0xFFF4EFEA)), Color(0xBFFFD6AA), INK)
        private val day = SkyPalette(listOf(Color(0xFF5C84A6), Color(0xFF7E9DBA), Color(0xFFB5C6D6), Color(0xFFEDF1F5)), Color(0x73FFFFFF), W)
        private val dusk = SkyPalette(listOf(Color(0xFF26324F), Color(0xFF4D5579), Color(0xFF9C8DAE), Color(0xFFECE7EF)), Color(0x59FFAA8C), W)
        private val nightDawn = SkyPalette(listOf(Color(0xFF05070C), Color(0xFF1A2238), Color(0xFF6A5A73), Color(0xFFCFC3C8)), Color(0x38FFBE96), W)
        private val nightDay = SkyPalette(listOf(Color(0xFF03050A), Color(0xFF0B1630), Color(0xFF3F5DB8), Color(0xFFC9D3EE)), Color(0x407896FF), W)
        private val nightDusk = SkyPalette(listOf(Color(0xFF040408), Color(0xFF171433), Color(0xFF5B3F86), Color(0xFFD9CDE8)), Color(0x33C88CFF), W)

        private val lightFrames = listOf(0.0 to dusk, 4.5 to dusk, 6.5 to dawn, 8.0 to dawn, 9.5 to day, 16.0 to day, 18.5 to dusk)
        private val darkFrames = listOf(0.0 to nightDusk, 4.5 to nightDusk, 6.5 to nightDawn, 8.0 to nightDawn, 9.5 to nightDay, 16.0 to nightDay, 18.5 to nightDusk)

        /** Every keyframe, for tests. */
        val ALL: List<SkyPalette> get() = listOf(dawn, day, dusk, nightDawn, nightDay, nightDusk)

        fun lerp(a: SkyPalette, b: SkyPalette, t: Float) = SkyPalette(
            a.stops.indices.map { androidx.compose.ui.graphics.lerp(a.stops[it], b.stops[it], t) },
            androidx.compose.ui.graphics.lerp(a.glow, b.glow, t),
            if (t < 0.5f) a.header else b.header,
        )

        /** The sky at [hour] (0..24, fractional) in light or dark mode. */
        fun at(hour: Double, dark: Boolean): SkyPalette {
            val frames = if (dark) darkFrames else lightFrames
            for (i in frames.indices) {
                val (h0, p0) = frames[i]
                val (h1, p1) = frames.getOrNull(i + 1) ?: (24.0 to frames.first().second)
                if (hour >= h0 && hour < h1) {
                    val t = (hour - h0) / (h1 - h0)
                    return lerp(p0, p1, (0.5 - kotlin.math.cos(t * Math.PI) / 2).toFloat())
                }
            }
            return frames.first().second
        }
    }
}

/** Which look the app uses. */
enum class StyleChoice(val title: String) { SKY("Sky"), CLASSIC("Classic") }

/** The accent the user picked. [DEFAULT] is ink on light cards and white on dark cards. */
enum class AccentChoice(val title: String, private val light: Color, private val dark: Color, val darkText: Boolean = false) {
    DEFAULT("Default", INK, Color(0xFFFFFFFF)),
    BLUE("Blue", Color(0xFF2F5D8A), Color(0xFF7FA7D0), darkText = true),
    GREEN("Green", Color(0xFF2E7059), Color(0xFF79C2A3), darkText = true),
    YELLOW("Yellow", Color(0xFF9A6A08), Color(0xFFF0C25A), darkText = true),
    ORANGE("Orange", Color(0xFFB4541E), Color(0xFFF29A62), darkText = true),
    PINK("Pink", Color(0xFFA83E6B), Color(0xFFEE93B7), darkText = true),
    PURPLE("Purple", Color(0xFF5B4596), Color(0xFFB3A2EC), darkText = true),
    ;

    fun color(dark: Boolean): Color = if (dark) this.dark else light

    /** The text colour that reads on [color]. */
    fun on(dark: Boolean): Color = when {
        this == DEFAULT -> if (dark) Color.Black else Color.White
        darkText && dark -> INK
        else -> Color.White
    }
}

enum class ThemeChoice(val title: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/**
 * The colours for [style] in light or [dark] mode. [sky] is the palette on screen now (Sky only;
 * see [SkyPalette.at]).
 */
fun auraColors(dark: Boolean, accent: AccentChoice, style: StyleChoice = StyleChoice.CLASSIC, sky: SkyPalette? = null): AuraColors {
    if (style == StyleChoice.SKY) {
        val palette = sky ?: SkyPalette.at(12.0, dark)
        val tone = accent.color(false)
        return AuraColors(
            isDark = false,
            canvas = palette.stops.last(),
            sky = palette,
            onSky = palette.header,
            onSkyMuted = palette.onSkyMuted,
            surface = Color(0xD9FAFBFD),
            surfaceStrong = Color(0xFFF8F9FB),
            well = Color(0x0F0D1117),
            glass = Color(0xBDF8F9FB),
            glassEdge = Color(0xE6FFFFFF),
            hairline = Color(0x140D1117),
            label = INK,
            secondary = Color(0xFF3F4856),
            tertiary = Color(0xFF6B7483),
            accent = tone,
            onAccent = accent.on(false),
            accentSoft = tone.copy(alpha = 0.10f),
            success = INK,
            danger = Color(0xFFC4372B),
            warning = Color(0xFFB86A0C),
        )
    }
    val tone = accent.color(dark)
    return if (dark) {
        AuraColors(
            isDark = true,
            canvas = Color(0xFF000000),
            sky = null,
            onSky = Color.White,
            onSkyMuted = Color(0xFFC2C2C2),
            surface = Color(0xFF1C1C1C),
            surfaceStrong = Color(0xFF1C1C1C),
            well = Color(0xFF2A2A2A),
            glass = Color(0xD9333333),
            glassEdge = Color(0x40FFFFFF),
            hairline = Color(0xFF333333),
            label = Color.White,
            secondary = Color(0xFFC2C2C2),
            tertiary = Color(0xFF8E8E8E),
            accent = tone,
            onAccent = accent.on(true),
            accentSoft = tone.copy(alpha = 0.18f),
            success = Color.White,
            danger = Color(0xFFFF6961),
            warning = Color(0xFFFFB340),
        )
    } else {
        AuraColors(
            isDark = false,
            canvas = Color(0xFFF9F9F9),
            sky = null,
            onSky = Color(0xFF0D0D0D),
            onSkyMuted = Color(0xFF5D5D5D),
            surface = Color.White,
            surfaceStrong = Color.White,
            well = Color(0xFFF0F0F0),
            glass = Color(0xE6FFFFFF),
            glassEdge = Color(0xFFE3E3E3),
            hairline = Color(0xFFE3E3E3),
            label = Color(0xFF0D0D0D),
            secondary = Color(0xFF5D5D5D),
            tertiary = Color(0xFF8F8F8F),
            accent = tone,
            onAccent = accent.on(false),
            accentSoft = tone.copy(alpha = 0.10f),
            success = Color(0xFF0D0D0D),
            danger = Color(0xFFDC2626),
            warning = Color(0xFFD97706),
        )
    }
}

val LocalAura = staticCompositionLocalOf { auraColors(dark = false, accent = AccentChoice.DEFAULT) }

/**
 * Whether the overlay is dark: the user's Light, Dark or System choice. Readable outside Compose,
 * for the overlay windows a service builds from plain views.
 */
fun overlayIsDark(context: Context): Boolean = when (AppearanceStore.theme.value) {
    ThemeChoice.LIGHT -> false
    ThemeChoice.DARK -> true
    ThemeChoice.SYSTEM ->
        (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
}

/**
 * The overlay's colours: the Classic look in the user's light or dark mode, never the sky. The
 * overlay floats over other apps, where a sky gradient would be a second wallpaper.
 */
fun overlayColors(dark: Boolean): AuraColors = auraColors(dark, AppearanceStore.accent.value, StyleChoice.CLASSIC)

/** [overlayIsDark] for Compose: follows the setting and the system mode as they change. */
@Composable
fun rememberOverlayDark(): Boolean {
    val choice = AppearanceStore.theme.collectAsState().value
    val system = androidx.compose.foundation.isSystemInDarkTheme()
    return when (choice) {
        ThemeChoice.SYSTEM -> system
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
}

/** What glass panels blur: the page content, marked with `hazeSource`. Null draws plain translucent glass. */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/**
 * The user's theme and accent, readable outside Compose.
 * - Contract: [hydrate] once at process start; until then both are the defaults.
 */
object AppearanceStore {
    const val PREFS = "aura_appearance"
    private const val KEY_THEME = "theme"
    private const val KEY_ACCENT = "accent"
    private const val KEY_STYLE = "style"

    private val _theme = MutableStateFlow(ThemeChoice.SYSTEM)
    private val _accent = MutableStateFlow(AccentChoice.DEFAULT)
    val theme: StateFlow<ThemeChoice> = _theme.asStateFlow()
    val accent: StateFlow<AccentChoice> = _accent.asStateFlow()
    private val _style = MutableStateFlow(StyleChoice.SKY)
    val style: StateFlow<StyleChoice> = _style.asStateFlow()

    fun hydrate(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _theme.value = prefs.getString(KEY_THEME, null)?.let { name -> ThemeChoice.entries.firstOrNull { it.name == name } } ?: ThemeChoice.SYSTEM
        _accent.value = prefs.getString(KEY_ACCENT, null)?.let { name -> AccentChoice.entries.firstOrNull { it.name == name } } ?: AccentChoice.DEFAULT
        _style.value = prefs.getString(KEY_STYLE, null)?.let { name -> StyleChoice.entries.firstOrNull { it.name == name } } ?: StyleChoice.SKY
    }

    fun setTheme(context: Context, choice: ThemeChoice) {
        _theme.value = choice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_THEME, choice.name).apply()
    }

    fun setStyle(context: Context, choice: StyleChoice) {
        _style.value = choice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_STYLE, choice.name).apply()
    }

    fun setAccent(context: Context, choice: AccentChoice) {
        _accent.value = choice
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACCENT, choice.name).apply()
    }
}

/** The type scale: Instrument Sans for UI text, Instrument Serif for the greeting and page titles. Both ship in `res/font`. */
object AuraType {
    /** The big greeting on Home. */
    val hero = TextStyle(fontFamily = AuraSerifFamily, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.4).sp)
    val heroSub = TextStyle(fontFamily = AuraBodyFamily, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp)
    val greeting = hero

    /** A page's title, on the page itself. */
    val largeTitle = TextStyle(fontFamily = AuraSerifFamily, fontSize = 38.sp, lineHeight = 42.sp, letterSpacing = (-0.3).sp)
    val title = TextStyle(fontFamily = AuraBodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontFamily = AuraBodyFamily, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = (-0.1).sp)
    val body = TextStyle(fontFamily = AuraBodyFamily, fontSize = 16.sp, lineHeight = 24.sp)
    val callout = TextStyle(fontFamily = AuraBodyFamily, fontSize = 15.sp, lineHeight = 21.sp)
    val footnote = TextStyle(fontFamily = AuraBodyFamily, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.05.sp)
    val caption = TextStyle(fontFamily = AuraBodyFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.1.sp)
    val code = TextStyle(fontFamily = AuraMonoFamily, fontSize = 12.sp, lineHeight = 17.sp)
}

object AuraShape {
    /** Grouped lists and cards, as in the ChatGPT and iOS settings tables. */
    val Card = RoundedCornerShape(26.dp)
    val Inner = RoundedCornerShape(14.dp)
    val Bubble = RoundedCornerShape(22.dp)
    val Composer = RoundedCornerShape(28.dp)
    val Pill = RoundedCornerShape(percent = 50)
}

/**
 * A frosted panel: what scrolls behind it blurred and tinted, a light edge, and a soft shadow.
 * - Contract: blurs [LocalHaze]'s content when one is provided; otherwise, and on phones where
 *   Haze cannot blur (before Android 12), it is a translucent fill.
 */
@Composable
fun Modifier.glass(shape: Shape, elevation: Dp = 12.dp): Modifier {
    val aura = LocalAura.current
    val haze = LocalHaze.current
    val frosted = if (haze != null) {
        Modifier.hazeEffect(
            haze,
            HazeStyle(
                backgroundColor = aura.canvas,
                tint = HazeTint(aura.glass.copy(alpha = if (aura.sky != null) SKY_GLASS_TINT else GLASS_TINT)),
                blurRadius = 28.dp,
                noiseFactor = 0f,
                fallbackTint = HazeTint(aura.glass),
            ),
        )
    } else {
        Modifier.background(aura.glass)
    }
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = 0.06f), spotColor = Color.Black.copy(alpha = 0.10f))
        .clip(shape)
        .then(frosted)
        .border(1.dp, Brush.verticalGradient(listOf(aura.glassEdge, aura.glassEdge.copy(alpha = aura.glassEdge.alpha * 0.4f))), shape)
}

/**
 * Frosted full-width bar behind a page's title and the status bar: content scrolling under it
 * blurs instead of colliding with the title. Plain canvas when no [LocalHaze] is provided.
 */
@Composable
fun Modifier.glassBar(): Modifier {
    val aura = LocalAura.current
    val haze = LocalHaze.current ?: return background(aura.canvas)
    return hazeEffect(
        haze,
        HazeStyle(
            backgroundColor = aura.canvas,
            tint = HazeTint(aura.canvas.copy(alpha = 0.78f)),
            blurRadius = 24.dp,
            noiseFactor = 0f,
            fallbackTint = HazeTint(aura.canvas),
        ),
    )
}

/** How much of the glass colour sits over the blur. Lower shows more of what is behind. */
private const val GLASS_TINT = 0.7f

/** Over the sky the glass is nearly opaque: ink text on it must read even on a night sky. */
private const val SKY_GLASS_TINT = 0.93f

/** A glass card on the sky with a hairline edge: one group of a settings table. */
@Composable
fun Modifier.auraCard(shape: Shape = AuraShape.Card): Modifier {
    val aura = LocalAura.current
    return this.clip(shape).background(aura.surface).border(1.dp, aura.hairline, shape)
}

/** An opaque card for dialogs and sheets, which sit over a scrim rather than the sky. */
@Composable
fun Modifier.auraCardStrong(shape: Shape = AuraShape.Card): Modifier {
    val aura = LocalAura.current
    return this.clip(shape).background(aura.surfaceStrong).border(1.dp, aura.hairline, shape)
}

/**
 * The page background. Classic: the flat canvas. Sky: the gradient over [SKY_HEIGHT], then its
 * last stop, the soft "sun" glow near the top right, and a faint film grain over all of it, as
 * MilkLogger paints it (`lib/ui/widgets/sky.dart`).
 * - Contract: put it inside the `hazeSource` layer, so glass above blurs the sky, and before any
 *   scroll modifier, so the sky stays still while content moves.
 */
@Composable
fun Modifier.auraBackground(): Modifier {
    val aura = LocalAura.current
    val sky = aura.sky ?: return background(aura.canvas)
    val grain = remember { ShaderBrush(ImageShader(Grain.tile, TileMode.Repeated, TileMode.Repeated)) }
    return drawBehind {
        val skyPx = minOf(size.height, SKY_HEIGHT.toPx())
        drawRect(sky.stops.last())
        drawRect(
            Brush.verticalGradient(*SkyPalette.POSITIONS.zip(sky.stops).toTypedArray(), endY = skyPx),
            size = Size(size.width, skyPx),
        )
        // The "sun": an ellipse 40% of the width by 30% of the sky's height at (85%, 12%),
        // fading out by 70% of its radius.
        val rx = size.width * 0.4f
        withTransform({
            translate(size.width * 0.85f, skyPx * 0.12f)
            scale(1f, skyPx * 0.3f / rx, pivot = Offset.Zero)
        }) {
            drawCircle(
                Brush.radialGradient(0f to sky.glow, 0.7f to sky.glow.copy(alpha = 0f), center = Offset.Zero, radius = rx),
                radius = rx,
                center = Offset.Zero,
            )
        }
        drawRect(grain)
    }
}

/** One film-grain tile per process: light and dark specks at a few percent alpha, fixed seed. */
private object Grain {
    private const val SIZE = 96
    private const val GRAIN_SCALE = 3
    val tile: ImageBitmap by lazy {
        val random = java.util.Random(7)
        val pixels = IntArray(SIZE * SIZE) {
            val alpha = random.nextInt(12)
            val shade = if (random.nextBoolean()) 255 else 0
            android.graphics.Color.argb(alpha, shade, shade, shade)
        }
        val tile = Bitmap.createBitmap(pixels, SIZE, SIZE, Bitmap.Config.ARGB_8888)
        // Why scale: MilkLogger's tile is laid out in logical pixels, so each speck is about 3
        // device pixels wide. Drawn 1:1 here it would vanish on a dense screen.
        Bitmap.createScaledBitmap(tile, SIZE * GRAIN_SCALE, SIZE * GRAIN_SCALE, false).asImageBitmap()
    }
}

/**
 * Loads a picture off the main thread.
 * - Contract: the value is null until [load] returns, and stays null when it fails or throws.
 */
@Composable
fun rememberBitmap(key: Any?, load: suspend () -> Bitmap?): State<ImageBitmap?> =
    produceState<ImageBitmap?>(initialValue = null, key) {
        value = withContext(Dispatchers.IO) { runCatching { load() }.getOrNull()?.asImageBitmap() }
    }

/** The older palettes, derived from [AuraColors] so screens written against them follow the theme. */
/**
 * The page colour for screens built on the older palettes. They draw ink text straight on their
 * page, so in Sky that page is opaque paper rather than the sky.
 */
val AuraColors.paper: Color get() = if (sky != null) Color(0xFFF2F4F7) else canvas

fun AuraColors.toMono(): MonoScheme = MonoScheme(
    canvas = paper,
    card = surface,
    chip = well,
    chipContent = label,
    textPrimary = label,
    textSecondary = secondary,
    divider = hairline,
    outline = hairline,
    bloodContainer = danger.copy(alpha = 0.12f),
    isDark = isDark,
)

fun AuraColors.toCapsule(): CapsuleScheme = CapsuleScheme(
    isDark = isDark,
    canvas = paper,
    card = surface,
    cardSoft = well,
    line = hairline,
    char = if (isDark) well else Color(0xFF0D0D0D),
    charDeep = if (isDark) surface else Color(0xFF0D0D0D),
    onChar = if (isDark) label else Color.White,
    onCharDim = if (isDark) secondary else Color(0xFFB4B4B4),
    charLine = if (isDark) hairline else Color(0x26FFFFFF),
    ink = label,
    sub = secondary,
    accent = accent,
    accentSoft = accentSoft,
    onAccent = onAccent,
    heroCore = surface,
    heroMid = surface,
    heroEdge = paper,
    capsule = label,
    onCapsule = canvas,
    onCapsuleDim = tertiary,
    ok = label,
)

fun AuraColors.toWarm(): WarmScheme = WarmScheme(
    isDark = isDark,
    canvasTop = paper,
    canvasBottom = paper,
    homeTop = paper,
    homeMid = paper,
    homeBottom = paper,
    heroA = if (isDark) well else Color(0xFF0D0D0D),
    heroB = if (isDark) well else Color(0xFF0D0D0D),
    onHero = if (isDark) label else Color.White,
    onHeroDim = if (isDark) secondary else Color(0xFFB4B4B4),
    card = surface,
    cardBorder = hairline,
    textPrimary = label,
    textSecondary = secondary,
    iconButton = surface,
    iconButtonContent = label,
    blood = danger,
)
