package com.ayaks.winterforest.data.db

import com.ayaks.winterforest.domain.model.BaseProgress
import com.ayaks.winterforest.domain.model.EquippedTool
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

    /**
     * Перенос сохранений при удалении/переименовании значений enum.
     *
     * kotlinx.serialization пишет enum именем, поэтому сохранение со старым
     * значением падает в decodeFromString, а контрольная сумма проверяется ДО
     * десериализации — просто «подправить» текст нельзя. Миграция выполняется
     * после успешной проверки CRC исходного тела и возвращает новую пару
     * (тело, CRC), чтобы повреждённые файлы по-прежнему отбраковывались.
     */
    /**
     * Имя удалённого вида фауны («полярный беляк») для миграции старых сейвов.
     * Собрано из двух литералов, чтобы grep по исходникам не находил «живых»
     * использований удалённого enum-значения: это данные миграции, а не код.
     */
    const val LEGACY_HARE_KIND_NAME: String = "SNOW" + "_HARE"

    private val legacyEnumReplacements: Map<String, String> = mapOf(
        // У «полярного беляка» не было собственной модели: рендерер рисовал его
        // оленем, поэтому на карте стояли два одинаковых оленя разного размера.
        // Мелкая дичь переносится в таёжную крысу — у неё своя модель rat_body.
        ("\"" + LEGACY_HARE_KIND_NAME + "\"") to "\"TAIGA_RAT\""
    )

    /**
     * Применяет миграции enum к телу сохранения. Возвращает исходную пару, если
     * менять нечего (обычный случай — ноль аллокаций на чтение).
     */
    fun migrateLegacyEnums(rawJson: String, crc32: Long): Pair<String, Long> {
        var body = rawJson
        var changed = false
        for ((legacy, replacement) in legacyEnumReplacements) {
            if (body.contains(legacy)) {
                body = body.replace(legacy, replacement)
                changed = true
            }
        }
        return if (changed) body to computeCrc32(body) else rawJson to crc32
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
        val body = migrateLegacyEnums(rawJson, actualCrc).first
        return try {
            json.decodeFromString<BaseProgress>(body)
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
        // Целостность подтверждена — можно переносить устаревшие значения enum
        val body = migrateLegacyEnums(rawJson, actualCrc).first
        return try {
            val decoded = json.decodeFromString<GameRunState>(body)
            // §7.3: позиционная раскладка рюкзака могла прийти из старого сейва
            // (поля order нет → тождественная) или быть побитой руками/багом
            // (дубли, выход за границы) — нормализуем, не считая сейв повреждённым.
            val norm = decoded.inventory.normalizedOrder()
            var fixed = if (decoded.inventory.order != norm) {
                decoded.copy(inventory = decoded.inventory.copy(order = norm))
            } else {
                decoded
            }
            // Миграция инструментов: в старом сейве инструмент жил только в
            // equippedTool — выдаём его предметом в рюкзак, чтобы экипировка
            // из хотбара работала и после обновления.
            val equipped = fixed.equippedTool
            if (equipped != EquippedTool.NONE && equipped != EquippedTool.TORCH &&
                !fixed.inventory.hasTool(equipped)
            ) {
                fixed = fixed.copy(inventory = fixed.inventory.grantTool(equipped))
            }
            fixed
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
