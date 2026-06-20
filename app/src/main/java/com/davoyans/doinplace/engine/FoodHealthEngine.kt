package com.davoyans.doinplace.engine

import com.davoyans.doinplace.data.db.AppDatabase
import com.davoyans.doinplace.data.model.FoodHealthTag

class FoodHealthEngine(private val db: AppDatabase) {

    enum class HealthTag { HEALTHIER, LESS_HEALTHY, NEUTRAL, UNKNOWN }

    data class HealthResult(
        val tag: HealthTag,
        val subcategory: String? = null,
        val suggestion: String? = null
    )

    suspend fun lookupItem(text: String, userId: String, language: String): HealthResult {
        val normalized = normalize(text)

        val override = runCatching { db.foodHealthDao().getOverride(userId, normalized) }.getOrNull()
        if (override != null) return HealthResult(tagOf(override.healthTag))

        val tag = runCatching {
            db.foodHealthDao().getTag(normalized, language)
                ?: db.foodHealthDao().getTag(normalized, "en")
                ?: db.foodHealthDao().getTagAnyLanguage(normalized)
        }.getOrNull()
        if (tag != null) return HealthResult(tagOf(tag.healthTag), tag.subcategory, tag.suggestion)

        val bundled = BUNDLED[normalized]
        if (bundled != null) return bundled

        return HealthResult(HealthTag.UNKNOWN)
    }

    fun normalize(text: String) = text.lowercase().trim().replace(Regex("\\s+"), " ")

    private fun tagOf(s: String) = when (s) {
        "HEALTHIER"    -> HealthTag.HEALTHIER
        "LESS_HEALTHY" -> HealthTag.LESS_HEALTHY
        "NEUTRAL"      -> HealthTag.NEUTRAL
        else           -> HealthTag.UNKNOWN
    }

    companion object {
        val BUNDLED: Map<String, HealthResult> = mapOf(
            // ── English — healthier ──────────────────────────────────────────
            "milk"               to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "plain yogurt"       to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "yogurt"             to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "kefir"              to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "cottage cheese"     to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "cheese"             to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "eggs"               to HealthResult(HealthTag.HEALTHIER, "EGG"),
            "egg"                to HealthResult(HealthTag.HEALTHIER, "EGG"),
            "chicken"            to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "turkey"             to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "fish"               to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "salmon"             to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "tuna"               to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "oats"               to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "buckwheat"          to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "rice"               to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "lavash"             to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "whole-grain bread"  to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "bread"              to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "beans"              to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "lentils"            to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "chickpeas"          to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "nuts"               to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "walnuts"            to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "almonds"            to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "apples"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "apple"              to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "bananas"            to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "banana"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "orange"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "oranges"            to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "grapes"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "strawberries"       to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "tomatoes"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "tomato"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "cucumbers"          to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "cucumber"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "carrots"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "carrot"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "spinach"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "broccoli"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "lettuce"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "cabbage"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "greens"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "potatoes"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "potato"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "mineral water"      to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "water"              to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "green tea"          to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "tea"                to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "olive oil"          to HealthResult(HealthTag.HEALTHIER, "OIL"),
            "honey"              to HealthResult(HealthTag.HEALTHIER, "HONEY"),
            // ── English — less healthy ───────────────────────────────────────
            "chips"              to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK", "Maybe add fruit, nuts, or yogurt too."),
            "fried snacks"       to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK", "Maybe add fruit, nuts, or yogurt too."),
            "popcorn"            to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK"),
            "candy"              to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Maybe add fruit too."),
            "chocolate bar"      to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Dark chocolate in small amounts is fine."),
            "chocolate"          to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Dark chocolate in small amounts is fine."),
            "cookies"            to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Maybe add fruit or nuts too."),
            "cake"               to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "donuts"             to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "ice cream"          to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "sweet pastries"     to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "sweet cereal"       to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "cola"               to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Maybe add water or mineral water too."),
            "soda"               to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Maybe add water or mineral water too."),
            "energy drink"       to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Maybe add water or tea too."),
            "processed sausage"  to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Maybe add chicken, beans, or fish too."),
            "sausage"            to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Maybe add chicken, beans, or fish too."),
            "hot dogs"           to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Maybe add chicken, beans, or fish too."),
            "hot dog"            to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Maybe add chicken, beans, or fish too."),
            "bacon"              to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT"),
            "instant noodles"    to HealthResult(HealthTag.LESS_HEALTHY, "FAST_FOOD", "Maybe add eggs or vegetables too."),
            "frozen pizza"       to HealthResult(HealthTag.LESS_HEALTHY, "FAST_FOOD"),
            "fast food burger"   to HealthResult(HealthTag.LESS_HEALTHY, "FAST_FOOD"),
            "white sugar"        to HealthResult(HealthTag.LESS_HEALTHY, "REFINED", "Maybe try honey in small amounts."),
            "sugar"              to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
            "margarine"          to HealthResult(HealthTag.LESS_HEALTHY, "REFINED", "Maybe try butter or olive oil."),
            "mayonnaise"         to HealthResult(HealthTag.LESS_HEALTHY, "REFINED", "Maybe try olive oil or yogurt dressing."),
            "ketchup with sugar" to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
            // ── Russian — healthier ─────────────────────────────────────────
            "молоко"             to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "кефир"              to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "йогурт"             to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "творог"             to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "сыр"                to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "яйца"               to HealthResult(HealthTag.HEALTHIER, "EGG"),
            "яйцо"               to HealthResult(HealthTag.HEALTHIER, "EGG"),
            "курица"             to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "рыба"               to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "овсянка"            to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "гречка"             to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "рис"                to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "лаваш"              to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "хлеб"               to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "фасоль"             to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "чечевица"           to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "орехи"              to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "грецкие орехи"      to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "миндаль"            to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "яблоки"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "бананы"             to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "апельсины"          to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "помидоры"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "огурцы"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "морковь"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "шпинат"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "брокколи"           to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "минералка"          to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "вода"               to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "зелёный чай"        to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "чай"                to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "оливковое масло"    to HealthResult(HealthTag.HEALTHIER, "OIL"),
            "мёд"                to HealthResult(HealthTag.HEALTHIER, "HONEY"),
            // ── Russian — less healthy ──────────────────────────────────────
            "чипсы"              to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK", "Можно добавить фрукты, орехи или йогурт."),
            "конфеты"            to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Можно добавить фрукты."),
            "шоколад"            to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "печенье"            to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Можно добавить фрукты или орехи."),
            "торт"               to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "мороженое"          to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "кола"               to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Можно добавить воду или минералку."),
            "газировка"          to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Можно добавить воду или минералку."),
            "энергетик"          to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Можно добавить воду или чай."),
            "колбаса"            to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Можно добавить курицу, бобовые или рыбу."),
            "сосиски"            to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Можно добавить курицу, бобовые или рыбу."),
            "доширак"            to HealthResult(HealthTag.LESS_HEALTHY, "FAST_FOOD", "Можно добавить яйца или овощи."),
            "майонез"            to HealthResult(HealthTag.LESS_HEALTHY, "REFINED", "Попробуйте оливковое масло или йогурт."),
            "маргарин"           to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
            "сахар"              to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
            // ── Armenian — healthier ────────────────────────────────────────
            "կաթ"                to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "կեֆիր"              to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "յոգուրտ"            to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "կաթնաշոռ"           to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "պանիր"              to HealthResult(HealthTag.HEALTHIER, "DAIRY"),
            "ձու"                to HealthResult(HealthTag.HEALTHIER, "EGG"),
            "հավ"                to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "ձուկ"               to HealthResult(HealthTag.HEALTHIER, "PROTEIN"),
            "վարսակ"             to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "հնդկաձավար"        to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "բրինձ"              to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "լավաշ"              to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "հաց"                to HealthResult(HealthTag.HEALTHIER, "GRAIN"),
            "լոբի"               to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "ոսպ"                to HealthResult(HealthTag.HEALTHIER, "LEGUME"),
            "ընկույզ"            to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "նուշ"               to HealthResult(HealthTag.HEALTHIER, "NUT"),
            "խնձոր"              to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "բանան"              to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "նարնջ"              to HealthResult(HealthTag.HEALTHIER, "FRUIT"),
            "լոլիկ"              to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "վարունգ"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "գազար"              to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "սպանախ"             to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "բրոկոլի"            to HealthResult(HealthTag.HEALTHIER, "VEGETABLE"),
            "հանքային ջուր"      to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "ջուր"               to HealthResult(HealthTag.HEALTHIER, "WATER"),
            "կանաչ թեյ"          to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "թեյ"                to HealthResult(HealthTag.HEALTHIER, "TEA"),
            "ձիթապտղի յուղ"     to HealthResult(HealthTag.HEALTHIER, "OIL"),
            "մեղր"               to HealthResult(HealthTag.HEALTHIER, "HONEY"),
            // ── Armenian — less healthy ─────────────────────────────────────
            "չիպս"               to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK", "Կարելի է նաև միրգ, ընկույզ կամ յոգուրտ ավելացնել։"),
            "չիփս"               to HealthResult(HealthTag.LESS_HEALTHY, "SALTY_SNACK", "Կարելի է նաև միրգ, ընկույզ կամ յոգուրտ ավելացնել։"),
            "կոնֆետ"             to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Կարելի է նաև միրգ ավելացնել։"),
            "թխվածքաբլիթ"       to HealthResult(HealthTag.LESS_HEALTHY, "SWEET", "Կարելի է նաև միրգ կամ ընկույզ ավելացնել։"),
            "տորթ"               to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "պաղպաղակ"           to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "շոկոլադ"            to HealthResult(HealthTag.LESS_HEALTHY, "SWEET"),
            "կոլա"               to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Կարելի է նաև ջուր կամ հանքային ջուր ավելացնել։"),
            "գազավորված ըմպելիք" to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Կարելի է նաև ջուր կամ հանքային ջուր ավելացնել։"),
            "էներգետիկ ըմպելիք" to HealthResult(HealthTag.LESS_HEALTHY, "SUGARY_DRINK", "Կարելի է նաև ջուր կամ թեյ ավելացնել։"),
            "երշիկ"              to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Կարելի է նաև հավ, լոբազգիներ կամ ձուկ ավելացնել։"),
            "սոսիս"              to HealthResult(HealthTag.LESS_HEALTHY, "PROCESSED_MEAT", "Կարելի է նաև հավ, լոբազգիներ կամ ձուկ ավելացնել։"),
            "արագ լապշա"        to HealthResult(HealthTag.LESS_HEALTHY, "FAST_FOOD", "Կարելի է նաև ձու կամ բանջարեղեն ավելացնել։"),
            "մայոնեզ"            to HealthResult(HealthTag.LESS_HEALTHY, "REFINED", "Փորձեք ձիթապտղի յուղ կամ յոգուրտ։"),
            "մարգարին"           to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
            "շաքար"              to HealthResult(HealthTag.LESS_HEALTHY, "REFINED"),
        )
    }
}
