package com.davoyans.doinplace.data.location

data class PlaceSearchResult(
    val id: String,
    val title: String,
    val formattedAddress: String,
    val latitude: Double,
    val longitude: Double,
    val provider: String = "geoapify",
    val rawCategory: String? = null,
    val distanceMeters: Float? = null
)
