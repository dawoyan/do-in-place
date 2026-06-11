package com.davoyans.doinplace.data.places

import android.util.Log
import com.davoyans.doinplace.data.db.UserTaskSuggestionDao
import com.davoyans.doinplace.data.model.UserTaskSuggestion
import java.util.UUID

class TaskSuggestionEngine(private val dao: UserTaskSuggestionDao) {

    // Unicode-safe: lowercase + collapse whitespace, remove punctuation/symbols but keep letters from any script
    fun normalize(text: String): String =
        text.lowercase().trim()
            .replace(Regex("[\\p{P}\\p{S}]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

    // ── Task-title suggestions ────────────────────────────────────────────────

    suspend fun getSuggestions(
        uid: String,
        placeKey: String,
        placeTypeId: String?,
        placeName: String,
        maxCount: Int = 6
    ): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun tryAdd(text: String) {
            val norm = normalize(text)
            if (norm.isNotBlank() && norm !in seen) {
                seen += norm
                result += text
            }
        }

        if (placeKey.isNotBlank()) {
            for (s in dao.getForPlaceKey(uid, placeKey)) {
                tryAdd(s.displayText)
                if (result.size >= maxCount) return result
            }
        }

        if (placeTypeId != null) {
            for (s in dao.getForPlaceType(uid, placeTypeId)) {
                tryAdd(s.displayText)
                if (result.size >= maxCount) return result
            }
        }

        if (PlaceTypeEngine.isOnexOrGlobbing(placeName)) {
            for (s in PlaceTypeEngine.ONEX_SUGGESTIONS) {
                tryAdd(s)
                if (result.size >= maxCount) return result
            }
        }

        return result
    }

    suspend fun recordAccepted(uid: String, placeKey: String, placeTypeId: String?, text: String) {
        val norm = normalize(text)
        if (norm.isBlank()) return
        val now = System.currentTimeMillis()

        if (placeKey.isNotBlank()) {
            val existing = dao.getByPlaceKeyAndText(uid, placeKey, norm)
            if (existing != null) {
                dao.upsert(existing.copy(acceptedCount = existing.acceptedCount + 1,
                    useCount = existing.useCount + 1, lastUsedAt = now))
            } else {
                dao.upsert(UserTaskSuggestion(
                    id = UUID.randomUUID().toString(), userId = uid, placeKey = placeKey,
                    placeTypeId = placeTypeId, normalizedText = norm, displayText = text,
                    useCount = 1, acceptedCount = 1, lastUsedAt = now,
                    category = UserTaskSuggestion.CAT_TASK
                ))
            }
        }

        if (placeTypeId != null) {
            val existing = dao.getByPlaceTypeAndText(uid, placeTypeId, norm)
            if (existing != null) {
                dao.upsert(existing.copy(acceptedCount = existing.acceptedCount + 1,
                    useCount = existing.useCount + 1, lastUsedAt = now))
            } else {
                dao.upsert(UserTaskSuggestion(
                    id = UUID.randomUUID().toString(), userId = uid, placeKey = "",
                    placeTypeId = placeTypeId, normalizedText = norm, displayText = text,
                    useCount = 1, acceptedCount = 1, lastUsedAt = now,
                    category = UserTaskSuggestion.CAT_TASK
                ))
            }
        }
    }

    suspend fun recordTaskCreated(uid: String, placeKey: String, placeTypeId: String?, text: String) {
        val norm = normalize(text)
        if (norm.isBlank()) return
        val now = System.currentTimeMillis()
        if (placeKey.isNotBlank()) {
            val existing = dao.getByPlaceKeyAndText(uid, placeKey, norm)
            if (existing != null) {
                dao.upsert(existing.copy(useCount = existing.useCount + 1, lastUsedAt = now))
            } else {
                dao.upsert(UserTaskSuggestion(
                    id = UUID.randomUUID().toString(), userId = uid, placeKey = placeKey,
                    placeTypeId = placeTypeId, normalizedText = norm, displayText = text,
                    useCount = 1, acceptedCount = 0, lastUsedAt = now,
                    category = UserTaskSuggestion.CAT_TASK
                ))
            }
        }
    }

    // ── Shopping-item suggestions ─────────────────────────────────────────────

    suspend fun getShoppingSuggestions(
        uid: String,
        placeKey: String,
        placeTypeId: String?,
        maxCount: Int = 8
    ): List<String> {
        val result = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun tryAdd(text: String, source: String) {
            val norm = normalize(text)
            if (norm.isNotBlank() && norm !in seen) {
                seen += norm
                result += text
                Log.d("ShoppingSuggest", "suggestion source=$source item=$text")
            }
        }

        // 1. Exact place (highest relevance)
        if (placeKey.isNotBlank()) {
            for (s in dao.getShoppingForPlace(uid, placeKey)) {
                tryAdd(s.displayText, "place")
                if (result.size >= maxCount) return result
            }
        }

        // 2. Same place type
        if (placeTypeId != null) {
            for (s in dao.getShoppingForType(uid, placeTypeId)) {
                tryAdd(s.displayText, "type")
                if (result.size >= maxCount) return result
            }
        }

        // 3. Frequent items across all places
        for (s in dao.getShoppingFrequent(uid)) {
            tryAdd(s.displayText, "frequent")
            if (result.size >= maxCount) return result
        }

        return result
    }

    suspend fun recordShoppingItemAdded(
        uid: String,
        placeKey: String,
        placeTypeId: String?,
        text: String
    ) {
        val norm = normalize(text)
        if (norm.isBlank()) return
        val now = System.currentTimeMillis()
        Log.d("ShoppingSuggest", "shopping suggestion learned item=$text norm=$norm")

        // Store per exact place
        if (placeKey.isNotBlank()) {
            val existing = dao.getShoppingByPlaceAndText(uid, placeKey, norm)
            if (existing != null) {
                dao.upsert(existing.copy(useCount = existing.useCount + 1,
                    acceptedCount = existing.acceptedCount + 1, lastUsedAt = now))
            } else {
                dao.upsert(UserTaskSuggestion(
                    id = UUID.randomUUID().toString(), userId = uid, placeKey = placeKey,
                    placeTypeId = placeTypeId, normalizedText = norm, displayText = text,
                    useCount = 1, acceptedCount = 1, lastUsedAt = now,
                    category = UserTaskSuggestion.CAT_SHOPPING
                ))
            }
        }

        // Also store per type (no specific place key) for cross-place type matching
        if (placeTypeId != null) {
            val existing = dao.getShoppingByTypeAndText(uid, placeTypeId, norm)
            if (existing != null) {
                dao.upsert(existing.copy(useCount = existing.useCount + 1, lastUsedAt = now))
            } else {
                dao.upsert(UserTaskSuggestion(
                    id = UUID.randomUUID().toString(), userId = uid, placeKey = "",
                    placeTypeId = placeTypeId, normalizedText = norm, displayText = text,
                    useCount = 1, acceptedCount = 0, lastUsedAt = now,
                    category = UserTaskSuggestion.CAT_SHOPPING
                ))
            }
        }

        // Prune if over limit
        val count = dao.countShopping(uid)
        if (count > 400) dao.pruneShopping(uid, keep = 300)
    }

    suspend fun clearAll(uid: String) = dao.clearAll(uid)
}
