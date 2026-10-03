package com.agoraaiassistant

import android.util.Base64
import org.json.JSONObject

data class TranscriptLine(val key: String, val fromUser: Boolean, val text: String)

/**
 * Reassembles transcript messages the Conversational AI agent sends over the RTC data
 * stream. Each message arrives as one or more chunks shaped "messageId|partIndex|partCount|base64Json".
 */
class TranscriptParser {
    private val pending = mutableMapOf<String, Array<String?>>()

    /** Returns a transcript line once all chunks of a message have arrived, else null. */
    fun onChunk(raw: String): TranscriptLine? {
        val parts = raw.split("|", limit = 4)
        if (parts.size != 4) return null
        val (messageId, indexStr, countStr, data) = parts
        val count = countStr.toIntOrNull() ?: return null
        val index = indexStr.toIntOrNull() ?: return null
        if (count < 1 || index !in 1..count) return null

        val chunks = pending.getOrPut(messageId) { arrayOfNulls(count) }
        if (chunks.size != count) return null
        chunks[index - 1] = data
        if (chunks.any { it == null }) return null
        pending.remove(messageId)

        return try {
            val json = JSONObject(String(Base64.decode(chunks.joinToString(""), Base64.DEFAULT)))
            parse(json)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: JSONObject): TranscriptLine? {
        val text = json.optString("text").trim()
        if (text.isEmpty()) return null
        val kind = json.optString("object")
        val fromUser = when {
            kind.startsWith("user") -> true
            kind.startsWith("assistant") -> false
            else -> json.optInt("stream_id", -1) == 0
        }
        // Partial transcripts share a turn id, so later updates replace earlier ones
        val turn = json.optString("turn_id").ifEmpty { json.optString("message_id") }
        return TranscriptLine(key = "${if (fromUser) "u" else "a"}-$turn", fromUser = fromUser, text = text)
    }
}
