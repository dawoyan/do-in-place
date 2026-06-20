package com.davoyans.doinplace.util

import android.content.Context
import android.content.Intent
import android.net.Uri

object MapIntentHelper {
    fun buildIntent(
        latitude: Double? = null,
        longitude: Double? = null,
        name: String? = null,
        address: String? = null
    ): Intent {
        val query = buildSearchQuery(name, address)
        val uri = if (latitude != null && longitude != null) {
            Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(query)})")
        } else {
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
        }
        return Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun buildWebFallbackIntent(name: String?, address: String?): Intent =
        Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(buildSearchQuery(name, address))}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun buildSearchQuery(name: String?, address: String?): String =
        listOfNotNull(name?.trim(), address?.trim())
            .filter { it.isNotBlank() }
            .joinToString(", ")
            .ifBlank { "place" }

    fun open(
        context: Context,
        latitude: Double? = null,
        longitude: Double? = null,
        name: String? = null,
        address: String? = null
    ): Boolean {
        val primaryIntent = buildIntent(latitude, longitude, name, address)
        val webFallbackIntent = buildWebFallbackIntent(name, address)
        return runCatching {
            when {
                primaryIntent.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(primaryIntent)
                    true
                }
                webFallbackIntent.resolveActivity(context.packageManager) != null -> {
                    context.startActivity(webFallbackIntent)
                    true
                }
                else -> false
            }
        }.getOrDefault(false)
    }
}
