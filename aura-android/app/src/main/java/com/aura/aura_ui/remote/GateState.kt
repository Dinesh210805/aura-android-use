package com.aura.aura_ui.remote

/**
 * The outcome of checking the owner's remote controls against this build's version code.
 *
 * - Produced by [evaluateGate]; published by [RemoteGateManager.gateState].
 * - Blocking states (`KillSwitchBlocked`, `VersionBlocked`) replace the app UI with
 *   `presentation/navigation/BlockScreen.kt` and stop the overlay from opening.
 */
sealed interface GateState {
    data object Allowed : GateState
    data class KillSwitchBlocked(val message: String) : GateState
    data class VersionBlocked(val message: String, val updateUrl: String) : GateState
    data class UpdateAvailable(val updateUrl: String) : GateState
}

/**
 * Picks the [GateState] for the given inputs. Pure; covered by `GateStateTest`.
 *
 * - Contract: precedence is kill switch > version below `minSupportedVersionCode` >
 *   newer `latestVersionCode` available > allowed.
 * - `minSupportedVersionCode <= 0` disables the version floor.
 */
fun evaluateGate(
    currentVersionCode: Long,
    killSwitchEnabled: Boolean,
    killSwitchMessage: String,
    minSupportedVersionCode: Long,
    versionGateMessage: String,
    latestVersionCode: Long,
    updateUrl: String,
): GateState = when {
    killSwitchEnabled -> GateState.KillSwitchBlocked(killSwitchMessage)
    minSupportedVersionCode > 0L && currentVersionCode < minSupportedVersionCode ->
        GateState.VersionBlocked(versionGateMessage, updateUrl)
    latestVersionCode > currentVersionCode -> GateState.UpdateAvailable(updateUrl)
    else -> GateState.Allowed
}

/** True for states that must block the app: kill switch or version floor. */
fun GateState.isBlocking(): Boolean =
    this is GateState.KillSwitchBlocked || this is GateState.VersionBlocked

/** The message to show for a blocking state; null when the state doesn't block. */
fun GateState.blockMessageOrNull(): String? = when (this) {
    is GateState.KillSwitchBlocked -> message
    is GateState.VersionBlocked -> message
    else -> null
}

/**
 * The owner's Remote Config values that feed [evaluateGate], as last fetched.
 *
 * - Why a separate value: [RemoteGateManager] saves it after every refresh and restores it at
 *   process start ([RemoteGateManager.hydrate]), so a blocked install is blocked from its first
 *   instant instead of working until the first fetch completes.
 */
data class GateInputs(
    val killSwitchEnabled: Boolean = false,
    val killSwitchMessage: String = "",
    val minSupportedVersionCode: Long = 0L,
    val versionGateMessage: String = "",
    val latestVersionCode: Long = 0L,
    val updateUrl: String = "",
)

/**
 * [evaluateGate] for [inputs] plus the [Blocklist] verdict. Pure; covered by `GateStateTest`.
 *
 * - Contract: a block turns the kill switch on with its own message; the version is compared
 *   with [currentVersionCode] each time, so restoring an old verdict after an update can't keep
 *   a now-supported version blocked.
 */
fun evaluateGate(inputs: GateInputs, block: Blocklist.Verdict, currentVersionCode: Long): GateState =
    evaluateGate(
        currentVersionCode = currentVersionCode,
        killSwitchEnabled = inputs.killSwitchEnabled || block.blocked,
        killSwitchMessage = if (block.blocked) block.message ?: Blocklist.DEFAULT_MESSAGE else inputs.killSwitchMessage,
        minSupportedVersionCode = inputs.minSupportedVersionCode,
        versionGateMessage = inputs.versionGateMessage,
        latestVersionCode = inputs.latestVersionCode,
        updateUrl = inputs.updateUrl,
    )
