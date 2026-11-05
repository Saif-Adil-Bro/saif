package com.saif.mosque.data.invitations

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

/** An address, not an authorization token. The verified email and rules grant access. */
data class InvitationAddress(val mosqueId: String, val invitationId: String)

/** A versioned, copyable reference; no external domain or Firebase link is required. */
object InvitationCode {
    private const val PREFIX = "MSH1"
    private const val MAX_ID_BYTES = 1500
    private const val MAX_CODE_LENGTH = 4010
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    private val alphabet = Regex("^[A-Za-z0-9_-]+$")

    fun encode(address: InvitationAddress): String {
        require(validId(address.mosqueId) && validId(address.invitationId))
        return "$PREFIX.${part(address.mosqueId)}.${part(address.invitationId)}"
    }

    /** Strictly reject corrupt, noncanonical and unknown versions rather than guessing. */
    fun decode(code: String): InvitationAddress? {
        val input = code.trim()
        if (input.length > MAX_CODE_LENGTH) return null
        val parts = input.split('.')
        if (parts.size != 3 || parts[0] != PREFIX) return null
        val mosque = decodePart(parts[1]) ?: return null
        val invitation = decodePart(parts[2]) ?: return null
        if (!validId(mosque) || !validId(invitation)) return null
        return InvitationAddress(mosque, invitation)
    }

    private fun part(value: String) = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    private fun decodePart(value: String): String? {
        if (!alphabet.matches(value) || value.length > 2000) return null
        return try {
            val bytes = decoder.decode(value)
            if (bytes.size > MAX_ID_BYTES || encoder.encodeToString(bytes) != value) return null
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { null }
    }
    private fun validId(value: String): Boolean {
        if (value.isEmpty() || value == "." || value == ".." || value.contains('/') ||
            value.any { it.isISOControl() } || (value.startsWith("__") && value.endsWith("__"))) return false
        return try {
            StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value)).remaining() <= MAX_ID_BYTES
        } catch (_: Exception) { false }
    }
}
