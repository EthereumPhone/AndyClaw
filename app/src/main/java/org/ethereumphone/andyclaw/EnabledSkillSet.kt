package org.ethereumphone.andyclaw

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The set of enabled skill ids, changed atomically (SET-07).
 *
 * Every change was a read-modify-write of the whole set, and binder calls arrive on concurrent
 * threads: the launcher's YOLO switch fired one `toggleSkill` per skill at once, two threads read
 * the same set, and the later write dropped the other's skill — a random part of the set was
 * lost, and showed only when YOLO went off again. Each change now reads, persists and publishes
 * under one lock, so the stored set and the published one are always the same and none is lost.
 */
class EnabledSkillSet(initial: Set<String>, private val persist: (Set<String>) -> Unit) {

    private val lock = Any()
    private val state = MutableStateFlow(initial)

    val flow: StateFlow<Set<String>> = state.asStateFlow()

    fun set(skillId: String, enabled: Boolean) = update { if (enabled) it + skillId else it - skillId }

    fun setAll(skillIds: Set<String>) = update { skillIds }

    /** The set as the store now holds it (after an import rewrote it); not written back. */
    fun reload(skillIds: Set<String>) {
        synchronized(lock) { state.value = skillIds }
    }

    private fun update(change: (Set<String>) -> Set<String>) {
        synchronized(lock) {
            val updated = change(state.value)
            persist(updated)
            state.value = updated
        }
    }
}
