package com.aura.mcp.lan

/**
 * Decides whether a `POST /aura/connect` may start a WebRTC connection at all.
 *
 * - Contract: pure apart from [PinGate]'s state. [authorize] never shows UI.
 *   - `pin` → [Decision.Pin] when [PinGate] accepts it. Only this path may later open the
 *     approval dialog for a new PC.
 *   - `tokenHash` → [Decision.Token] when [isTrusted] knows it. The PC must still prove it holds
 *     the token over the DataChannel (`PairingCrypto.proofMac`); a leaked hash gets nothing more
 *     than a failed proof.
 *   - anything else → [Decision.Refused] with the HTTP response to send.
 * - Why the split: without it anyone on the same Wi-Fi could pop an approval dialog on the phone
 *   whenever they liked. Now a new-PC dialog needs the PIN, which is only on the phone's screen.
 */
class ConnectAuthorizer(
    private val pinGate: PinGate,
    private val isTrusted: (tokenHash: String) -> Boolean,
) {
    sealed interface Decision {
        data object Pin : Decision
        data class Token(val tokenHash: String) : Decision
        data class Refused(val response: LanSignalingServer.Response) : Decision
    }

    fun authorize(request: LanSignalingServer.ConnectRequest): Decision {
        request.tokenHash?.let { hash ->
            return if (isTrusted(hash)) {
                Decision.Token(hash)
            } else {
                Decision.Refused(
                    LanSignalingServer.Response.error(
                        403, "not_paired",
                        "This phone doesn't know this computer any more. Pair again: aura-mcp pair <PIN from AURA>",
                    ),
                )
            }
        }
        return when (val r = pinGate.tryPin(request.pin.orEmpty())) {
            PinGate.Result.Accepted -> Decision.Pin
            is PinGate.Result.Wrong -> Decision.Refused(
                LanSignalingServer.Response.error(403, "bad_pin", "Wrong PIN. Check AURA → MCP Center on the phone."),
            )
            is PinGate.Result.Locked -> {
                val sec = (r.retryAfterMs + 999) / 1000
                Decision.Refused(
                    LanSignalingServer.Response.error(
                        429, "locked", "Too many wrong PINs. Wait $sec s, then use the new PIN shown on the phone.", sec,
                    ),
                )
            }
        }
    }
}
