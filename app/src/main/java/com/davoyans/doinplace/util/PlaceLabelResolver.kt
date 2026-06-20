package com.davoyans.doinplace.util

data class ResolvedPlaceLabel(
    val primaryName: String,
    val address: String?,
    val notificationLine: String
)

object PlaceLabelResolver {
    fun resolve(
        exactPlaceName: String? = null,
        exactPlaceAddress: String? = null,
        savedPlaceName: String? = null,
        providerPlaceName: String? = null,
        placeTypeName: String? = null
    ): ResolvedPlaceLabel {
        val resolvedName = sequenceOf(
            exactPlaceName?.trim(),
            savedPlaceName?.trim(),
            providerPlaceName?.trim(),
            placeTypeName?.trim()
        ).firstOrNull { !it.isNullOrBlank() }.orEmpty()

        val resolvedAddress = exactPlaceAddress
            ?.trim()
            ?.takeIf { it.isNotBlank() && !it.equals(resolvedName, ignoreCase = true) }

        val notificationLine = buildString {
            append("You're next to ")
            append(resolvedName.ifBlank { placeTypeName?.trim().orEmpty().ifBlank { "this place" } })
            if (!resolvedAddress.isNullOrBlank()) {
                append(", ")
                append(resolvedAddress)
            }
        }

        return ResolvedPlaceLabel(
            primaryName = resolvedName.ifBlank { placeTypeName?.trim().orEmpty().ifBlank { "Place" } },
            address = resolvedAddress,
            notificationLine = notificationLine
        )
    }
}
