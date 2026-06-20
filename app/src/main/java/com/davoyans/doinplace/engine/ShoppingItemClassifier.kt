package com.davoyans.doinplace.engine

import com.davoyans.doinplace.util.DiagLog

object ShoppingItemClassifier {
    private val keywordCategories: List<Pair<String, ShoppingItemCategory>> = listOf(
        "milk" to ShoppingItemCategory.DAIRY,
        "молоко" to ShoppingItemCategory.DAIRY,
        "կաթ" to ShoppingItemCategory.DAIRY,
        "yogurt" to ShoppingItemCategory.DAIRY,
        "йогурт" to ShoppingItemCategory.DAIRY,
        "յոգուրտ" to ShoppingItemCategory.DAIRY,
        "cheese" to ShoppingItemCategory.DAIRY,
        "сыр" to ShoppingItemCategory.DAIRY,
        "պանիր" to ShoppingItemCategory.DAIRY,
        "bread" to ShoppingItemCategory.BREAD_BAKERY,
        "хлеб" to ShoppingItemCategory.BREAD_BAKERY,
        "հաց" to ShoppingItemCategory.BREAD_BAKERY,
        "lavash" to ShoppingItemCategory.BREAD_BAKERY,
        "лаваш" to ShoppingItemCategory.BREAD_BAKERY,
        "լավաշ" to ShoppingItemCategory.BREAD_BAKERY,
        "bun" to ShoppingItemCategory.BREAD_BAKERY,
        "булочка" to ShoppingItemCategory.BREAD_BAKERY,
        "water" to ShoppingItemCategory.BEVERAGES,
        "вода" to ShoppingItemCategory.BEVERAGES,
        "ջուր" to ShoppingItemCategory.BEVERAGES,
        "juice" to ShoppingItemCategory.BEVERAGES,
        "сок" to ShoppingItemCategory.BEVERAGES,
        "հյութ" to ShoppingItemCategory.BEVERAGES,
        "coffee" to ShoppingItemCategory.BEVERAGES,
        "кофе" to ShoppingItemCategory.BEVERAGES,
        "սուրճ" to ShoppingItemCategory.BEVERAGES,
        "tomato" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "помидор" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "помидоры" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "լոլիկ" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "cucumber" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "огурец" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "огурцы" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "վարունգ" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "apple" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "яблоко" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "խնձոր" to ShoppingItemCategory.FRUITS_VEGETABLES,
        "chicken" to ShoppingItemCategory.MEAT_FISH,
        "курица" to ShoppingItemCategory.MEAT_FISH,
        "հավ" to ShoppingItemCategory.MEAT_FISH,
        "fish" to ShoppingItemCategory.MEAT_FISH,
        "рыба" to ShoppingItemCategory.MEAT_FISH,
        "ձուկ" to ShoppingItemCategory.MEAT_FISH,
        "soap" to ShoppingItemCategory.HOUSEHOLD,
        "мыло" to ShoppingItemCategory.HOUSEHOLD,
        "օճառ" to ShoppingItemCategory.HOUSEHOLD,
        "detergent" to ShoppingItemCategory.HOUSEHOLD,
        "порошок" to ShoppingItemCategory.HOUSEHOLD,
        "средство" to ShoppingItemCategory.HOUSEHOLD,
        "լվացող" to ShoppingItemCategory.HOUSEHOLD,
        "shampoo" to ShoppingItemCategory.HOUSEHOLD,
        "шампунь" to ShoppingItemCategory.HOUSEHOLD,
        "շամպուն" to ShoppingItemCategory.HOUSEHOLD,
        "toothpaste" to ShoppingItemCategory.PHARMACY_HEALTH,
        "зубная паста" to ShoppingItemCategory.PHARMACY_HEALTH,
        "ատամի մածուկ" to ShoppingItemCategory.PHARMACY_HEALTH,
        "chips" to ShoppingItemCategory.SWEETS_SNACKS,
        "crisps" to ShoppingItemCategory.SWEETS_SNACKS,
        "чипсы" to ShoppingItemCategory.SWEETS_SNACKS,
        "չիպս" to ShoppingItemCategory.SWEETS_SNACKS,
        "ice cream" to ShoppingItemCategory.FROZEN,
        "мороженое" to ShoppingItemCategory.FROZEN,
        "պաղպաղակ" to ShoppingItemCategory.FROZEN,
        "rice" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "рис" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "բրինձ" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "pasta" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "макароны" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "մակարոն" to ShoppingItemCategory.CANNED_DRY_GOODS,
        "cat food" to ShoppingItemCategory.PET,
        "корм для кошек" to ShoppingItemCategory.PET,
        "կատվի կեր" to ShoppingItemCategory.PET,
        "diapers" to ShoppingItemCategory.BABY,
        "подгузники" to ShoppingItemCategory.BABY,
        "տակդիր" to ShoppingItemCategory.BABY
    )

    fun normalize(text: String): String =
        text.lowercase()
            .trim()
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun classify(text: String): ShoppingItemCategory {
        val normalized = normalize(text)
        val match = keywordCategories.firstOrNull { (keyword, _) ->
            normalized == keyword || normalized.contains(keyword)
        }
        val category = match?.second ?: ShoppingItemCategory.UNKNOWN
        DiagLog.d(
            "SHOP_CLASSIFY",
            "item=$text normalized=$normalized category=${category.name} keyword=${match?.first ?: "none"}"
        )
        return category
    }
}
