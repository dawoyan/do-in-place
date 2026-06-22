package com.davoyans.doinplace.engine

import com.davoyans.doinplace.util.DiagLog

object ShoppingItemCanonicalizer {
    enum class Confidence { HIGH, LOW }

    data class Result(
        val rawText: String,
        val canonicalName: String,
        val confidence: Confidence,
        val removed: List<String>
    ) {
        val shouldAutoLearn: Boolean get() = confidence == Confidence.HIGH && canonicalName.isNotBlank()
    }

    private data class Rule(
        val alias: String,
        val canonical: String
    ) {
        val normalizedAlias = normalize(alias)
    }

    private val directRules = listOf(
        Rule("dog food", "dog food"),
        Rule("cat food", "cat food"),
        Rule("dishwashing liquid", "detergent"),
        Rule("dish soap", "detergent"),
        Rule("laundry detergent", "detergent"),
        Rule("washing powder", "detergent"),
        Rule("coca cola", "cola"),
        Rule("coke", "cola"),
        Rule("cola", "cola"),
        Rule("кола", "кола"),
        Rule("կոլա", "կոլա"),
        Rule("fairy", "detergent"),
        Rule("milk", "milk"),
        Rule("молоко", "молоко"),
        Rule("կաթ", "կաթ"),
        Rule("yogurt", "yogurt"),
        Rule("йогурт", "йогурт"),
        Rule("յոգուրտ", "յոգուրտ"),
        Rule("lavash", "lavash"),
        Rule("лаваш", "лаваш"),
        Rule("լավաշ", "լավաշ"),
        Rule("water", "water"),
        Rule("вода", "вода"),
        Rule("ջուր", "ջուր"),
        Rule("corn", "corn"),
        Rule("кукуруза", "кукуруза"),
        Rule("եգիպտացորեն", "եգիպտացորեն"),
        Rule("detergent", "detergent"),
        Rule("средство", "средство"),
        Rule("լվացող", "լվացող"),
        Rule("bread", "bread"),
        Rule("хлеб", "хлеб"),
        Rule("հաց", "հաց"),
        Rule("juice", "juice"),
        Rule("сок", "сок"),
        Rule("հյութ", "հյութ"),
        Rule("cheese", "cheese"),
        Rule("сыр", "сыр"),
        Rule("պանիր", "պանիր"),
        Rule("rice", "rice"),
        Rule("рис", "рис"),
        Rule("բրինձ", "բրինձ"),
        Rule("pasta", "pasta"),
        Rule("макароны", "макароны"),
        Rule("մակարոն", "մակարոն")
    ).sortedByDescending { it.normalizedAlias.length }

    private val knownBrands = listOf(
        "ararat",
        "coca cola",
        "bonduelle",
        "fairy",
        "pedigree",
        "простоквашино"
    )

    private val promoWords = setOf(
        "promo", "sale", "discount", "offer", "free",
        "акция", "скидка", "подарок",
        "զեղչ", "ակցիա", "նվեր"
    )

    private val packagingWords = setOf(
        "pack", "packet", "bottle", "box", "bag", "jar", "can", "carton", "tray", "bundle",
        "уп", "упак", "бутылка", "банка", "пачка", "коробка",
        "տուփ", "շիշ", "փաթեթ"
    )

    private val descriptorWords = setOf(
        "zero", "light", "classic", "fresh", "lemon", "original", "soft", "maxi", "mini",
        "без", "классик", "лимон", "оригинал",
        "կլասիկ", "կիտրոն", "օրիգինալ"
    )

    private val percentPattern = Regex("""\b\d+(?:[.,]\d+)?\s*%""")
    private val pricePattern = Regex("""\b\d+(?:[.,]\d+)?\s*(?:amd|dram|rub|руб|usd|eur|֏|\$|€|₽)\b""")
    private val volumePattern = Regex("""\b\d+(?:[.,]\d+)?\s*(?:ml|l|մլ|լ|мл|л)\b""")
    private val weightPattern = Regex("""\b\d+(?:[.,]\d+)?\s*(?:kg|g|gr|կգ|գ|кг|гр)\b""")
    private val countPattern = Regex("""\b\d+\s*(?:pcs|pc|pack|x|шт|հատ)\b""")
    private val codePattern = Regex("""\b(?:\d{8,}|[a-zа-яա-ֆ0-9]{6,}\d[a-zа-яա-ֆ0-9]*)\b""")

    fun normalize(text: String): String =
        text.lowercase()
            .replace('×', 'x')
            .replace(Regex("[\\p{P}\\p{S}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun looksLikeShoppingImport(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (lines.size < 2) return false
        val candidateLines = lines.map { canonicalize(it, emitLog = false) }
        val shortLines = lines.count { it.length <= 48 }
        return shortLines >= 2 &&
            candidateLines.count { it.canonicalName.isNotBlank() } >= 2 &&
            candidateLines.any { it.confidence == Confidence.HIGH }
    }

    fun canonicalize(rawText: String, emitLog: Boolean = true): Result {
        val trimmed = rawText.trim()
        if (trimmed.isBlank()) {
            return Result(rawText = rawText, canonicalName = "", confidence = Confidence.LOW, removed = emptyList())
        }

        val removed = linkedSetOf<String>()
        val lowered = trimmed.lowercase().replace('×', 'x')
        val normalized = normalize(lowered)

        if (knownBrands.any { containsPhrase(normalized, it) }) removed += "brand"
        if (percentPattern.containsMatchIn(lowered)) removed += "percent"
        if (volumePattern.containsMatchIn(lowered)) removed += "volume"
        if (weightPattern.containsMatchIn(lowered)) removed += "weight"
        if (countPattern.containsMatchIn(lowered)) removed += "count"
        if (pricePattern.containsMatchIn(lowered)) removed += "price"
        if (codePattern.containsMatchIn(normalized)) removed += "code"

        if (promoWords.any { containsWord(normalized, it) }) removed += "promo"
        if (packagingWords.any { containsWord(normalized, it) }) removed += "packaging"
        if (descriptorWords.any { containsWord(normalized, it) }) removed += "descriptor"

        val directMatch = directRules.firstOrNull { containsPhrase(normalized, it.normalizedAlias) }
        val canonical = directMatch?.canonical ?: fallbackCanonicalName(normalized)
        val confidence = if (directMatch != null) Confidence.HIGH else Confidence.LOW
        val result = Result(trimmed, canonical, confidence, removed.toList())

        if (emitLog) {
            val removedText = if (result.removed.isEmpty()) "none" else result.removed.joinToString(",")
            DiagLog.d(
                "ITEM_CANONICALIZE",
                "raw=\"${escapeForLog(result.rawText)}\" canonical=\"${escapeForLog(result.canonicalName)}\" removed=\"$removedText\""
            )
        }

        return result
    }

    private fun fallbackCanonicalName(normalized: String): String {
        if (normalized.isBlank()) return ""

        var stripped = normalized
        stripped = percentPattern.replace(stripped, " ")
        stripped = volumePattern.replace(stripped, " ")
        stripped = weightPattern.replace(stripped, " ")
        stripped = countPattern.replace(stripped, " ")
        stripped = pricePattern.replace(stripped, " ")
        stripped = codePattern.replace(stripped, " ")

        val tokens = normalize(stripped)
            .split(" ")
            .filter { token ->
                token.isNotBlank() &&
                    token.none(Char::isDigit) &&
                    knownBrands.none { brand -> brand == token } &&
                    token !in promoWords &&
                    token !in packagingWords &&
                    token !in descriptorWords
            }

        return tokens.take(2).joinToString(" ")
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        val normalizedPhrase = normalize(phrase)
        return (" $text ").contains(" $normalizedPhrase ")
    }

    private fun containsWord(text: String, word: String): Boolean =
        containsPhrase(text, word)

    private fun escapeForLog(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")
}
