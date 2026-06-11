package com.davoyans.doinplace.data.model

import org.json.JSONArray
import org.json.JSONObject

data class ChecklistItem(val text: String, val done: Boolean)

// Matches: - * + . • · followed by optional space,
//          numbered  1.  1)  2.  2)  etc.,
//          lettered  a.  a)  b.  b)  etc.
private val LIST_MARKER = Regex("""^(?:[+\-.*•·]\s*|\d+[.)]\s*|[a-zA-Z][.)]\s*)""")

fun String.parseChecklistItems(): List<String>? {
    val nonBlank = lines().map { it.trim() }.filter { it.isNotBlank() }
    if (nonBlank.size < 2) return null
    val items = nonBlank.mapNotNull { line ->
        val match = LIST_MARKER.find(line) ?: return@mapNotNull null
        line.substring(match.value.length).trim().takeIf { it.isNotBlank() }
    }
    // Every non-blank line must carry a marker
    return if (items.size == nonBlank.size) items else null
}

fun List<ChecklistItem>.toChecklistJson(): String {
    val arr = JSONArray()
    forEach { item ->
        arr.put(JSONObject().apply {
            put("text", item.text)
            put("done", item.done)
        })
    }
    return arr.toString()
}

fun String.parseChecklistJson(): List<ChecklistItem> = runCatching {
    val arr = JSONArray(this)
    (0 until arr.length()).map { i ->
        val obj = arr.getJSONObject(i)
        ChecklistItem(obj.getString("text"), obj.optBoolean("done", false))
    }
}.getOrElse { emptyList() }
