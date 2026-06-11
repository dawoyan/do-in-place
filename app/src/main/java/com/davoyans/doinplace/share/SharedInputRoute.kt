package com.davoyans.doinplace.share

import android.net.Uri
import com.davoyans.doinplace.util.DiagLog

sealed class SharedInputRoute {
    data class PlaceLink(val rawText: String) : SharedInputRoute()
    data class ArticleUrl(val rawText: String, val url: String) : SharedInputRoute()
    data class PlainTextNote(val text: String) : SharedInputRoute()
    data object Unsupported : SharedInputRoute()
}

object SharedInputRouter {

    fun routeText(text: String): SharedInputRoute {
        val url = findFirstUrl(text)
        return when {
            url != null && isMapOrPlaceUrl(url) -> {
                DiagLog.d("SHARE_ROUTER", "route=PlaceLink url=${safeHost(url)}")
                SharedInputRoute.PlaceLink(text)
            }
            url != null -> {
                DiagLog.d("SHARE_ROUTER", "route=ArticleUrl url=${safeHost(url)}")
                SharedInputRoute.ArticleUrl(text, url)
            }
            text.isNotBlank() -> {
                DiagLog.d("SHARE_ROUTER", "route=PlainTextNote")
                SharedInputRoute.PlainTextNote(text)
            }
            else -> {
                DiagLog.d("SHARE_ROUTER", "route=Unsupported")
                SharedInputRoute.Unsupported
            }
        }
    }

    fun isMapOrPlaceUrl(url: String): Boolean {
        val host = runCatching { Uri.parse(url).host?.lowercase() }.getOrNull().orEmpty()
        val lower = url.lowercase()
        return host.contains("maps.app.goo.gl") ||
               (host.contains("google.com") && lower.contains("/maps")) ||
               (host.contains("goo.gl") && lower.contains("maps")) ||
               (host.contains("yandex.") && lower.contains("/maps")) ||
               host.contains("2gis.") ||
               lower.startsWith("geo:")
    }

    private fun findFirstUrl(text: String): String? =
        Regex("""https?://\S+""").find(text)?.value?.trimEnd('.')

    private fun safeHost(url: String): String =
        runCatching { Uri.parse(url).host ?: url.take(40) }.getOrDefault(url.take(40))
}
