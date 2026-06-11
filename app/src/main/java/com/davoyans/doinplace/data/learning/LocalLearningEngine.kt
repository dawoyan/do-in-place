package com.davoyans.doinplace.data.learning

import com.davoyans.doinplace.data.db.TaskLearningProfileDao
import com.davoyans.doinplace.data.model.Task
import com.davoyans.doinplace.data.model.TaskLearningProfile
import com.davoyans.doinplace.data.model.TaskPriority
import java.util.UUID

class LocalLearningEngine(private val dao: TaskLearningProfileDao) {

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
        "of", "with", "by", "is", "it", "this", "that", "i", "me", "my",
        "we", "you", "be", "am", "are", "was", "were", "get", "got", "new"
    )
    private val URGENT_WORDS = setOf(
        "urgent", "asap", "today", "important", "medicine", "doctor",
        "hospital", "payment", "document", "deadline", "emergency"
    )

    fun buildPlaceKey(task: Task): String =
        task.placeId ?: "${task.placeName.trim().lowercase()}_${(task.latitude * 1000).toInt()}_${(task.longitude * 1000).toInt()}"

    private fun extractKeywords(title: String): List<String> =
        title.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .distinct()
            .take(10)

    suspend fun suggestPriority(uid: String, task: Task): Pair<TaskPriority, String>? {
        val placeKey = buildPlaceKey(task)
        val titleLower = task.title.lowercase()

        // Quick keyword rules — highest precedence
        if (URGENT_WORDS.any { titleLower.contains(it) })
            return TaskPriority.URGENT to "Contains urgent keyword"

        // DB history
        val keywords = extractKeywords(task.title)
        if (keywords.isEmpty()) return null

        var totalHigh = 0
        var totalNormal = 0
        var totalLow = 0

        for (kw in keywords) {
            val row = dao.get(uid, placeKey, kw) ?: continue
            totalHigh += row.highCount
            totalNormal += row.normalCount
            totalLow += row.lowCount
        }
        val totalSignals = totalHigh + totalNormal + totalLow
        if (totalSignals < 3) return null

        return when {
            totalHigh > totalNormal + totalLow ->
                TaskPriority.URGENT to "You often use Urgent for similar tasks here"
            else -> null
        }
    }

    suspend fun recordTaskCreated(uid: String, task: Task) {
        val placeKey = buildPlaceKey(task)
        val keywords = extractKeywords(task.title)
        val now = System.currentTimeMillis()
        for (kw in keywords) {
            val existing = dao.get(uid, placeKey, kw)
            val updated = if (existing != null) {
                when (task.priority) {
                    TaskPriority.URGENT -> existing.copy(highCount   = existing.highCount + 1,   lastUsedAt = now)
                    TaskPriority.NO_RUSH   -> existing.copy(normalCount = existing.normalCount + 1, lastUsedAt = now)
                }
            } else {
                TaskLearningProfile(
                    id = UUID.randomUUID().toString(),
                    userId = uid,
                    placeKey = placeKey,
                    normalizedPlaceName = task.placeName.trim().lowercase(),
                    keyword = kw,
                    highCount   = if (task.priority == TaskPriority.URGENT) 1 else 0,
                    normalCount = if (task.priority == TaskPriority.NO_RUSH)   1 else 0,
                    lowCount    = 0,
                    lastUsedAt = now
                )
            }
            dao.upsert(updated)
        }
    }

    suspend fun recordTaskCompleted(uid: String, task: Task) {
        val placeKey = buildPlaceKey(task)
        val keywords = extractKeywords(task.title)
        val now = System.currentTimeMillis()
        for (kw in keywords) {
            val existing = dao.get(uid, placeKey, kw) ?: continue
            dao.upsert(existing.copy(completedCount = existing.completedCount + 1, lastUsedAt = now))
        }
    }

    suspend fun recordTaskCancelled(uid: String, task: Task) {
        val placeKey = buildPlaceKey(task)
        val keywords = extractKeywords(task.title)
        val now = System.currentTimeMillis()
        for (kw in keywords) {
            val existing = dao.get(uid, placeKey, kw) ?: continue
            dao.upsert(existing.copy(cancelledCount = existing.cancelledCount + 1, lastUsedAt = now))
        }
    }

    suspend fun clearAll(uid: String) = dao.clearAll(uid)
}
