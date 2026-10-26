package com.ayaks.winterforest.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class InventorySlot(
    val type: ResourceType,
    val count: Int
) {
    init {
        require(count in 1..Inventory.MAX_STACK_SIZE) {
            "Размер стака должен быть от 1 до ${Inventory.MAX_STACK_SIZE}, получено: $count"
        }
    }
}

@Serializable
data class AddItemResult(
    val inventory: Inventory,
    val addedAmount: Int,
    val overflowAmount: Int
) {
    val isFullBlocked: Boolean get() = addedAmount == 0 && overflowAmount > 0
}

@Serializable
data class Inventory(
    val slots: List<InventorySlot> = emptyList(),
    val specialItems: Map<SpecialLootId, Int> = emptyMap(),
    /**
     * Инструменты (топор/кирка/копьё) — ОТДЕЛЬНЫЕ предметы рюкзака: их можно
     * переносить в хотбар, экипировать и убирать. Пустая карта в старых сейвах
     * означает «инструментов нет» (миграция выдаёт инструмент по equippedTool).
     */
    val tools: Map<EquippedTool, Int> = emptyMap(),
    /**
     * §7.3: позиционная раскладка рюкзака (сетка MAX_SLOTS): order[i] — индекс
     * слота в [slots], стоящего в ячейке i, либо null — пустая ячейка. Позволяет
     * перетаскиванием сортировать предметы и оставлять «дырки». Пустой список
     * (старые сохранения) означает тождественную раскладку без дырок.
     */
    val order: List<Int?> = emptyList()
) {
    val occupiedSlots: Int get() = slots.size

    /**
     * Нормализованная раскладка длины [MAX_SLOTS]: битые/дублирующиеся индексы
     * отбрасываются, неназначенные слоты дописываются в первые пустые ячейки.
     * Идемпотентна — повторный вызов ничего не меняет.
     */
    fun normalizedOrder(): List<Int?> {
        val grid = ArrayList<Int?>(MAX_SLOTS)
        for (i in 0 until MAX_SLOTS) grid += null
        val used = HashSet<Int>()
        for (i in order.indices) {
            if (i >= MAX_SLOTS) break
            val v = order[i]
            if (v != null && v in slots.indices && used.add(v)) {
                grid[i] = v
            }
        }
        var nextFree = 0
        for (slotIdx in slots.indices) {
            if (used.add(slotIdx)) {
                while (nextFree < MAX_SLOTS && grid[nextFree] != null) nextFree++
                if (nextFree < MAX_SLOTS) {
                    grid[nextFree] = slotIdx
                    nextFree++
                }
            }
        }
        return grid
    }

    /** Содержимое ячейки сетки i (null — пустая ячейка). */
    fun gridSlot(index: Int): InventorySlot? =
        normalizedOrder().getOrNull(index)?.let { slots[it] }

    /** Перестановка содержимого двух ячеек сетки (сортировка перетаскиванием). */
    fun withSwappedCells(a: Int, b: Int): Inventory {
        if (a == b || a !in 0 until MAX_SLOTS || b !in 0 until MAX_SLOTS) return this
        val grid = normalizedOrder().toMutableList()
        val tmp = grid[a]
        grid[a] = grid[b]
        grid[b] = tmp
        return copy(order = grid)
    }
    val isFull: Boolean get() = slots.size >= MAX_SLOTS && slots.all { it.count >= MAX_STACK_SIZE }

    fun countOf(type: ResourceType): Int =
        slots.filter { it.type == type }.sumOf { it.count }

    fun specialCount(id: SpecialLootId): Int =
        specialItems[id] ?: 0

    fun hasSpecial(id: SpecialLootId): Boolean =
        specialCount(id) > 0

    fun remainingCapacityFor(type: ResourceType): Int {
        val spaceInExisting = slots
            .filter { it.type == type && it.count < MAX_STACK_SIZE }
            .sumOf { MAX_STACK_SIZE - it.count }
        val freeSlots = (MAX_SLOTS - slots.size).coerceAtLeast(0)
        return spaceInExisting + freeSlots * MAX_STACK_SIZE
    }

    fun tryAddResource(type: ResourceType, amount: Int): AddItemResult {
        if (amount <= 0) return AddItemResult(this, addedAmount = 0, overflowAmount = 0)
        val mutable = slots.toMutableList()
        var remaining = amount

        for (i in mutable.indices) {
            if (remaining == 0) break
            val slot = mutable[i]
            if (slot.type == type && slot.count < MAX_STACK_SIZE) {
                val delta = minOf(remaining, MAX_STACK_SIZE - slot.count)
                mutable[i] = slot.copy(count = slot.count + delta)
                remaining -= delta
            }
        }

        while (remaining > 0 && mutable.size < MAX_SLOTS) {
            val delta = minOf(remaining, MAX_STACK_SIZE)
            mutable += InventorySlot(type = type, count = delta)
            remaining -= delta
        }

        return AddItemResult(
            inventory = copy(slots = mutable),
            addedAmount = amount - remaining,
            overflowAmount = remaining
        )
    }

    fun tryConsumeResource(type: ResourceType, amount: Int): Inventory? {
        if (amount <= 0) return this
        if (countOf(type) < amount) return null

        val mutable = slots.toMutableList()
        var toRemove = amount
        for (i in mutable.indices.reversed()) {
            if (toRemove == 0) break
            val slot = mutable[i]
            if (slot.type == type) {
                if (slot.count <= toRemove) {
                    toRemove -= slot.count
                    mutable.removeAt(i)
                } else {
                    mutable[i] = slot.copy(count = slot.count - toRemove)
                    toRemove = 0
                }
            }
        }
        return copy(slots = mutable)
    }

    /**
     * Добавление специального предмета:
     * - Постоянные предметы существуют строго в единственном экземпляре (макс. 1).
     * - Расходуемый «Смоляной факел» может стакаться до MAX_CONSUMABLE_SPECIAL.
     */
    fun grantSpecialLoot(id: SpecialLootId, count: Int = 1): Inventory {
        if (count <= 0) return this
        val current = specialCount(id)
        val cap = if (id.isConsumable) MAX_CONSUMABLE_SPECIAL else 1
        val next = (current + count).coerceAtMost(cap)
        val updated = specialItems.toMutableMap().apply { put(id, next) }
        return copy(specialItems = updated)
    }

    fun toolCount(tool: EquippedTool): Int = tools[tool] ?: 0

    fun hasTool(tool: EquippedTool): Boolean = toolCount(tool) > 0

    /** Положить инструмент в рюкзак (NONE/TORCH предметами не являются). */
    fun grantTool(tool: EquippedTool, count: Int = 1): Inventory {
        if (count <= 0 || tool == EquippedTool.NONE || tool == EquippedTool.TORCH) return this
        val updated = tools.toMutableMap().apply { put(tool, (this[tool] ?: 0) + count) }
        return copy(tools = updated)
    }

    /** Забрать один инструмент из рюкзака (null — инструмента нет). */
    fun consumeTool(tool: EquippedTool): Inventory? {
        val current = toolCount(tool)
        if (current <= 0) return null
        val updated = tools.toMutableMap().apply {
            if (current <= 1) remove(tool) else put(tool, current - 1)
        }
        return copy(tools = updated)
    }

    fun consumeSpecialLoot(id: SpecialLootId, amount: Int = 1): Inventory? {
        if (!id.isConsumable) return null
        val current = specialCount(id)
        if (current < amount) return null
        val next = current - amount
        val updated = specialItems.toMutableMap().apply {
            if (next <= 0) remove(id) else put(id, next)
        }
        return copy(specialItems = updated)
    }

    /**
     * При смерти расходуемые спецпредметы (Смоляной факел) сбрасываются,
     * а постоянные находки (карта, термос, кирка, шуба) сохраняются.
     */
    fun retainPersistentSpecialsOnly(): Map<SpecialLootId, Int> =
        specialItems.filter { (id, qty) -> id.persistsAcrossRuns && qty > 0 }
            .mapValues { 1 }

    companion object {
        const val MAX_SLOTS = 20
        const val MAX_STACK_SIZE = 10
        const val MAX_CONSUMABLE_SPECIAL = 5
    }
}
