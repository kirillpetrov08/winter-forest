package com.ayaks.winterforest.data.db

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.GameRunState
import java.util.zip.CRC32
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class CorruptedSaveException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class EnvelopePayload(
    val formatVersion: Int = CURRENT_FORMAT_VERSION,
    val crc32: Long,
    val bodyJson: String
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
    }
}

object SavePayloadCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encodeBaseProgress(progress: BaseProgress): Pair<String, Long> {
        val body = json.encodeToString(progress)
        return body to computeCrc32(body)
    }

    fun decodeBaseProgress(rawJson: String, expectedCrc32: Long): BaseProgress {
        if (rawJson.isBlank()) {
            throw CorruptedSaveException("Пустые данные сохранения базы.")
        }
        val actualCrc = computeCrc32(rawJson)
        if (actualCrc != expectedCrc32) {
            throw CorruptedSaveException(
                "Контрольная сумма прогресса базы не совпадает ($actualCrc != $expectedCrc32)."
            )
        }
        return try {
            json.decodeFromString<BaseProgress>(rawJson)
        } catch (t: Throwable) {
            throw CorruptedSaveException("Ошибка чтения JSON прогресса базы: ${t.message}", t)
        }
    }

    fun encodeRunState(run: GameRunState): Pair<String, Long> {
        val body = json.encodeToString(run)
        return body to computeCrc32(body)
    }

    fun decodeRunState(rawJson: String, expectedCrc32: Long): GameRunState {
        if (rawJson.isBlank()) {
            throw CorruptedSaveException("Пустые данные активного забега.")
        }
        val actualCrc = computeCrc32(rawJson)
        if (actualCrc != expectedCrc32) {
            throw CorruptedSaveException(
                "Контрольная сумма сохранения забега не совпадает ($actualCrc != $expectedCrc32)."
            )
        }
        return try {
            json.decodeFromString<GameRunState>(rawJson)
        } catch (t: Throwable) {
            throw CorruptedSaveException("Ошибка чтения JSON сохранения забега: ${t.message}", t)
        }
    }

    fun encodeEnvelope(bodyJson: String, crc32: Long): String =
        json.encodeToString(EnvelopePayload(crc32 = crc32, bodyJson = bodyJson))

    fun decodeEnvelope(rawEnvelope: String): EnvelopePayload =
        try {
            json.decodeFromString<EnvelopePayload>(rawEnvelope)
        } catch (t: Throwable) {
            throw CorruptedSaveException("Файл сохранения повреждён: ${t.message}", t)
        }

    fun computeCrc32(text: String): Long {
        val crc = CRC32()
        val bytes = text.toByteArray(Charsets.UTF_8)
        crc.update(bytes, 0, bytes.size)
        return crc.value
    }
}
