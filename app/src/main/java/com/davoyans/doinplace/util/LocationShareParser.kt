package com.davoyans.doinplace.util

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Parses GPS coordinates from text shared by Google Maps or Yandex Maps.
 *
 * Fast path (no network): extracts coordinates directly from long-form URLs or bare text.
 * Slow path: follows short URLs (maps.app.goo.gl etc.) with a hard 8-second ceiling.
 *   - On HTTP 301/302: follows the Location header (Yandex short URLs work this way)
 *   - On HTTP 200: reads the first 8 KB of the response body and scans for an embedded
 *                  Google Maps URL (maps.app.goo.gl returns 200 + HTML on Android clients)
 *
 * [resolve] may block up to 8 seconds — call on a background thread.
 */
object LocationShareParser {

    data class ParsedLocation(val lat: Double, val lng: Double, val name: String, val nameOnly: Boolean = false)

    private const val TAG           = "LocationShareParser"
    private const val DEADLINE_MS   = 8_000L  // hard cap on all network work combined
    private const val CONNECT_MS    = 6_000   // per-hop TCP connect timeout
    private const val READ_MS       = 6_000   // per-hop read timeout
    private const val MAX_HOPS      = 5       // max redirect hops (FDL chains can be longer)
    private const val BODY_PEEK     = 20_000  // bytes to read from HTML body

    // Matches any Google Maps or Yandex Maps full URL (www optional, maps.google.com variant included)
    private val MAPS_URL_RE = Regex(
        """https://(?:(?:www\.)?google\.com/maps|maps\.google\.com|yandex\.[a-z]{2,3}/maps)[^\s"'<>\\]+"""
    )

    // ── Public entry point ─────────────────────────────────────────────────

    fun resolve(sharedText: String): ParsedLocation? {
        val text = sharedText.trim()
        val url  = findUrl(text)
        val name = extractCandidateName(text, url)

        Log.d(TAG, "resolve: url=$url name=$name")
        DiagLog.d("LOCATION", "resolve url=${url?.take(80)} name=$name")

        // Fast path — no network needed
        if (url != null) parseFromUrl(url, name)?.let {
            DiagLog.d("LOCATION", "fast-path result lat=${it.lat} lng=${it.lng}")
            return it
        }
        parseCoordinatesFromText(text)?.let { (lat, lng) ->
            DiagLog.d("LOCATION", "text-coords result lat=$lat lng=$lng")
            return ParsedLocation(lat, lng, name)
        }

        // Slow path — follow short URL
        if (url == null || !isShortUrl(url)) {
            DiagLog.d("LOCATION", "no short URL — giving up url=$url")
            Log.d(TAG, "No short URL to follow — giving up")
            return null
        }
        DiagLog.d("LOCATION", "short URL detected — network resolve start")
        Log.d(TAG, "Short URL detected — resolving with network")
        return resolveWithDeadline(url, name)
    }

    // ── Deadline wrapper ───────────────────────────────────────────────────

    private fun resolveWithDeadline(url: String, name: String): ParsedLocation? {
        val holder = AtomicReference<ParsedLocation?>()
        val done   = CountDownLatch(1)
        Thread {
            try { holder.set(fetchAndParse(url, name)) }
            catch (e: Exception) {
                DiagLog.e("LOCATION", "fetchAndParse threw", e)
                Log.d(TAG, "fetchAndParse threw: ${e.message}")
            }
            finally { done.countDown() }
        }.apply { isDaemon = true }.start()
        val finished = done.await(DEADLINE_MS, TimeUnit.MILLISECONDS)
        DiagLog.d("LOCATION", "resolveWithDeadline finished=$finished result=${holder.get()}")
        Log.d(TAG, "resolveWithDeadline finished=$finished result=${holder.get()}")
        return holder.get()
    }

    // ── HTTP hop follower ──────────────────────────────────────────────────

    private fun fetchAndParse(startUrl: String, name: String): ParsedLocation? {
        var current = startUrl
        for (hop in 0 until MAX_HOPS) {
            // Try to extract coordinates from the current URL before making a network call.
            // Catches the common case where the 302 redirect already contains /@lat,lng,zoom.
            DiagLog.d("LOCATION", "hop$hop start url=${current.take(150)}")
            parseFromUrl(current, name)?.let {
                DiagLog.d("LOCATION", "hop$hop coords in URL — skip fetch lat=${it.lat} lng=${it.lng}")
                return it
            }

            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = CONNECT_MS
                readTimeout    = READ_MS
                // Mobile UA causes Firebase Dynamic Links (maps.app.goo.gl) to redirect
                // via intent:// or directly to the full Maps URL, rather than an HTML page.
                setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36")
                setRequestProperty("Accept", "text/html,*/*")
            }
            try {
                val code     = conn.responseCode
                val location = conn.getHeaderField("Location")
                Log.d(TAG, "Hop $hop: HTTP $code url=${current.take(80)} → Location=${location?.take(80)}")
                DiagLog.d("LOCATION", "hop$hop HTTP $code → loc=${location?.take(100)}")

                when {
                    code in 300..399 && !location.isNullOrBlank() -> {
                        // Standard redirect — follow it
                        current = when {
                            location.startsWith("http") -> location
                            location.startsWith("/") ->
                                "${URL(current).let { "${it.protocol}://${it.host}" }}$location"
                            location.startsWith("intent://") -> {
                                // Firebase Dynamic Link on Android redirects via intent:// scheme.
                                // Extract the embedded https URL:
                                //   intent://maps.google.com/maps/place/...#Intent;scheme=https;...;end
                                // → https://maps.google.com/maps/place/...
                                extractFromIntentUrl(location) ?: return null
                            }
                            else -> return null
                        }
                        // continue to next hop
                    }
                    code == 200 -> {
                        // Google short URLs return 200 with HTML containing the real Maps URL
                        val body = conn.inputStream.bufferedReader()
                            .use { it.readText().take(BODY_PEEK) }
                        Log.d(TAG, "Got HTML body (${body.length} chars), scanning for Maps URL")

                        val embedded = MAPS_URL_RE.find(body)?.value
                        if (embedded != null) {
                            Log.d(TAG, "Found embedded URL: ${embedded.take(100)}")
                            parseFromUrl(embedded, name)?.let { return it }
                        }
                        // No coordinates in body — try place name from current or embedded URL
                        val resolvedName = extractCandidateName("", embedded ?: current)
                        if (resolvedName.isNotBlank()) {
                            DiagLog.d("LOCATION", "hop$hop place name extracted: '$resolvedName'")
                            return ParsedLocation(0.0, 0.0, resolvedName, nameOnly = true)
                        }
                        return null
                    }
                    else -> {
                        // Unexpected status — try to parse the current URL as-is
                        return parseFromUrl(current, name)
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Hop $hop error: ${e.message}")
                return null
            } finally {
                conn.disconnect()
            }
        }
        parseFromUrl(current, name)?.let { return it }
        val resolvedName = extractCandidateName("", current)
        return if (resolvedName.isNotBlank()) ParsedLocation(0.0, 0.0, resolvedName, nameOnly = true) else null
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private fun findUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.')

    private fun isShortUrl(url: String): Boolean =
        url.contains("maps.app.goo.gl") ||
        url.contains("goo.gl/maps") ||
        url.contains("yandex.ru/maps/-/") ||
        url.contains("yandex.com/maps/-/") ||
        url.contains("go.yandex") ||
        url.contains("yndx.net") ||
        url.matches(Regex("""https://goo\.gl/\S+"""))

    /**
     * Firebase Dynamic Links on Android redirect via intent:// scheme.
     * Example: intent://maps.google.com/maps/place/...#Intent;scheme=https;package=com.google.android.apps.maps;end
     * This extracts the embedded https URL.
     */
    private fun extractFromIntentUrl(intentUrl: String): String? = runCatching {
        val hostAndPath = intentUrl.removePrefix("intent://").substringBefore("#")
        val schemeMatch = Regex("""scheme=([a-z]+)""").find(intentUrl)
        val scheme = schemeMatch?.groupValues?.get(1) ?: "https"
        "$scheme://$hostAndPath"
    }.getOrNull()

    /**
     * Extracts a searchable place name from shared text when coordinate parsing fails.
     * Returns null if no useful name can be found.
     */
    fun extractPlaceNameForGeocoding(text: String): String? {
        val url = findUrl(text.trim())
        val name = extractCandidateName(text.trim(), url)
        return name.ifBlank { null }
    }

    private fun extractCandidateName(text: String, url: String?): String {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        val first = lines.firstOrNull()
        if (first != null && !first.startsWith("http") && first != url)
            return first
        if (url != null) {
            Regex("""/maps/place/([^/@?]+)""").find(url)?.groupValues?.get(1)?.let {
                return runCatching { URLDecoder.decode(it.replace("+", " "), "UTF-8") }
                    .getOrDefault("")
            }
        }
        return ""
    }

    // ── Coordinate patterns ────────────────────────────────────────────────

    private fun parseFromUrl(url: String, name: String): ParsedLocation? {
        // Google Maps: /@lat,lng,zoom or /@lat,lng/
        Regex("""/@(-?\d+\.?\d*),(-?\d+\.?\d*)[,/z]""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = false, name)?.let { return it }
        }

        // Google Maps: ?q=lat,lng  or &q=lat,lng
        Regex("""[?&]q=(-?\d+\.?\d*)[,+](-?\d+\.?\d*)""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = false, name)?.let { return it }
        }

        // Google Maps: ?center=lat,lng or &center=lat,lng
        Regex("""[?&]center=(-?\d+\.?\d*),(-?\d+\.?\d*)""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = false, name)?.let { return it }
        }

        // Google Maps: /search/?api=1&query=lat,lng
        Regex("""[?&]query=(-?\d+\.?\d*)[,+](-?\d+\.?\d*)""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = false, name)?.let { return it }
        }

        // Yandex Maps: ?ll=lng,lat  (Yandex puts longitude FIRST)
        Regex("""[?&]ll=(-?\d+\.?\d*),(-?\d+\.?\d*)""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = true, name)?.let { return it }
        }

        // Yandex Maps: ?pt=lng,lat
        Regex("""[?&]pt=(-?\d+\.?\d*),(-?\d+\.?\d*)""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = true, name)?.let { return it }
        }

        // Google Maps path coords: /maps/place/Name/lat,lng (no @)
        Regex("""/maps/(?:place/[^/]+/)?(-?\d{1,3}\.\d{4,}),(-?\d{1,3}\.\d{4,})""").find(url)?.also { m ->
            build(m.groupValues[1], m.groupValues[2], yandex = false, name)?.let { return it }
        }

        return null
    }

    private fun parseCoordinatesFromText(text: String): Pair<Double, Double>? {
        Regex("""(-?\d{1,3}\.\d{4,})[,\s]+(-?\d{1,3}\.\d{4,})""").find(text)?.also { m ->
            val a = m.groupValues[1].toDoubleOrNull() ?: return@also
            val b = m.groupValues[2].toDoubleOrNull() ?: return@also
            if (isValidLatLng(a, b)) return a to b
        }
        return null
    }

    /** [a],[b] are the regex capture groups; for Yandex, [a]=lng and [b]=lat. */
    private fun build(a: String, b: String, yandex: Boolean, name: String): ParsedLocation? {
        val first  = a.toDoubleOrNull() ?: return null
        val second = b.toDoubleOrNull() ?: return null
        val lat = if (yandex) second else first
        val lng = if (yandex) first  else second
        return if (isValidLatLng(lat, lng)) ParsedLocation(lat, lng, name) else null
    }

    private fun isValidLatLng(lat: Double, lng: Double): Boolean =
        lat in -90.0..90.0 && lng in -180.0..180.0 && (lat != 0.0 || lng != 0.0)
}
