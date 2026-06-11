package com.davoyans.doinplace.article

import com.davoyans.doinplace.util.DiagLog
import org.jsoup.Jsoup
import java.net.URL

data class ArticleSummaryResult(
    val title: String?,
    val summary: String?,
    val url: String
)

class ArticleSummaryExtractor {

    fun summarize(url: String): ArticleSummaryResult {
        val host = runCatching { URL(url).host }.getOrDefault("?")
        DiagLog.d("ARTICLE", "fetch start host=$host")

        if (shouldSkip(url)) {
            DiagLog.d("ARTICLE", "skip non-article url")
            return ArticleSummaryResult(null, null, url)
        }

        return try {
            val doc = Jsoup.connect(url)
                .timeout(7_000)
                .followRedirects(true)
                .userAgent("Mozilla/5.0 (compatible; DoInPlace/1.0)")
                .maxBodySize(200_000)
                .get()

            val title = (doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?: doc.selectFirst("title")?.text())
                ?.trim()?.takeIf { it.isNotBlank() }

            val summary = (doc.selectFirst("meta[name=description]")?.attr("content")
                ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
                ?: doc.select("p").firstOrNull { it.text().length > 50 }?.text())
                ?.trim()?.takeIf { it.isNotBlank() }
                ?.take(400)

            DiagLog.d("ARTICLE", "summary success titlePresent=${title != null} summaryChars=${summary?.length ?: 0}")
            ArticleSummaryResult(title, summary, url)
        } catch (e: Exception) {
            DiagLog.d("ARTICLE", "fetch failed reason=${e.javaClass.simpleName}")
            ArticleSummaryResult(null, null, url)
        }
    }

    private fun shouldSkip(url: String): Boolean {
        val lower = url.lowercase()
        return lower.endsWith(".pdf") || lower.contains(".pdf?") ||
               lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") ||
               lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".avi") ||
               lower.endsWith(".apk") || lower.endsWith(".zip")
    }
}
