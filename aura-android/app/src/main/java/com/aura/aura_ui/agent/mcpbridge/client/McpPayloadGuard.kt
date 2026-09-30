package com.aura.aura_ui.agent.mcpbridge.client

import com.aura.aura_ui.agent.mcpbridge.McpToolInfo

/** Bounds + sanitizes untrusted third-party MCP payloads (parent spec §4). */
object McpPayloadGuard {
    const val MAX_TOOLS = 128
    const val MAX_DESC_CHARS = 1024
    const val MAX_INSTRUCTION_CHARS = 4000

    fun capTools(tools: List<McpToolInfo>): List<McpToolInfo> =
        tools.take(MAX_TOOLS).map { it.copy(description = capDescription(it.description)) }

    fun capDescription(s: String): String = if (s.length <= MAX_DESC_CHARS) s else s.take(MAX_DESC_CHARS)

    /** Strip C0 control chars (keep \n, \t), collapse, cap. Returns null if effectively empty. */
    fun sanitizeInstructions(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.filterNot { it.isISOControl() && it != '\n' && it != '\t' }.trim()
        if (cleaned.isBlank()) return null
        return if (cleaned.length <= MAX_INSTRUCTION_CHARS) cleaned else cleaned.take(MAX_INSTRUCTION_CHARS)
    }
}
