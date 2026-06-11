package com.davoyans.doinplace.data.location

import com.davoyans.doinplace.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class GeoapifyPlaceSearchProvider : PlaceSearchProvider {

    // In-memory cache keyed by query (session-lived, reduces API calls)
    private val cache = mutableMapOf<String, List<PlaceSearchResult>>()

    override suspend fun search(query: String): Result<List<PlaceSearchResult>> =
        search(query, userLat = null, userLng = null)

    suspend fun search(
        query: String,
        userLat: Double?,
        userLng: Double?
    ): Result<List<PlaceSearchResult>> {
        val key = "${query.trim().lowercase()}|${userLat?.let { "%.3f".format(it) }}|${userLng?.let { "%.3f".format(it) }}"
        cache[key]?.let { return Result.success(it) }

        val apiKey = BuildConfig.GEOAPIFY_API_KEY
        if (apiKey.isBlank() || apiKey == "your_geoapify_api_key_here") {
            if (BuildConfig.DEBUG) {
                return Result.failure(
                    IllegalStateException(
                        "GEOAPIFY_API_KEY is not set. Add it to local.properties and rebuild."
                    )
                )
            }
            return Result.success(emptyList())
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
                val biasParam = if (userLat != null && userLng != null)
                    "&bias=proximity:$userLng,$userLat"
                else ""
                val url = URL(
                    "https://api.geoapify.com/v1/geocode/autocomplete" +
                    "?text=$encoded&limit=10$biasParam&apiKey=$apiKey"
                )
                val results = fetch(url)
                // Tag each result with distance from user and sort closest first
                val sorted = if (userLat != null && userLng != null) {
                    results.map { r ->
                        val d = FloatArray(1)
                        android.location.Location.distanceBetween(userLat, userLng, r.latitude, r.longitude, d)
                        r.copy(distanceMeters = d[0])
                    }.sortedBy { it.distanceMeters }
                } else results
                sorted.also { cache[key] = it }
            }
        }
    }

    /**
     * Searches for places of a given Geoapify category within radiusMeters of the given point.
     * Used by TypeTaskCheckWorker to detect whether the user is near a matching place type.
     *
     * Uses the Geoapify Places API (/v2/places), not the geocoding endpoint.
     * filter=circle uses lon,lat order (not lat,lon).
     */
    suspend fun searchNearby(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        category: String
    ): Result<List<PlaceSearchResult>> {
        val cacheKey = "nearby:$category:${(lat * 100).toInt()}:${(lon * 100).toInt()}:$radiusMeters"
        cache[cacheKey]?.let { return Result.success(it) }

        val apiKey = BuildConfig.GEOAPIFY_API_KEY
        if (apiKey.isBlank() || apiKey == "your_geoapify_api_key_here") {
            return Result.success(emptyList())
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val catEncoded = java.net.URLEncoder.encode(category, "UTF-8")
                // Geoapify filter=circle expects lon,lat (not lat,lon)
                val url = URL(
                    "https://api.geoapify.com/v2/places" +
                    "?categories=$catEncoded" +
                    "&filter=circle:$lon,$lat,$radiusMeters" +
                    "&limit=10" +
                    "&apiKey=$apiKey"
                )
                fetch(url).also { cache[cacheKey] = it }
            }
        }
    }

    private fun fetch(url: URL): List<PlaceSearchResult> {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.setRequestProperty("Accept", "application/json")
        return try {
            val body = conn.inputStream.bufferedReader().readText()
            parseFeatureCollection(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseFeatureCollection(json: String): List<PlaceSearchResult> {
        val root = JSONObject(json)
        val features = root.optJSONArray("features") ?: return emptyList()
        return (0 until features.length()).mapNotNull { i ->
            runCatching {
                val feature = features.getJSONObject(i)
                val props = feature.getJSONObject("properties")
                val lat = props.optDouble("lat", Double.NaN)
                val lon = props.optDouble("lon", Double.NaN)
                if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                val name = props.optString("name").ifBlank {
                    props.optString("formatted").ifBlank { return@mapNotNull null }
                }
                PlaceSearchResult(
                    id = props.optString("place_id", "$i"),
                    title = name,
                    formattedAddress = props.optString("formatted", name),
                    latitude = lat,
                    longitude = lon,
                    provider = "geoapify",
                    rawCategory = props.optString("category").takeIf { it.isNotBlank() }
                )
            }.getOrNull()
        }
    }
}
