package com.davoyans.doinplace.data.places

data class PlaceTypeInfo(
    val id: String,
    val displayName: String,
    val inferKeywords: List<String> = emptyList(),
    val suggestions: List<String> = emptyList()
)

object PlaceTypeEngine {

    val DEFAULT_PLACE_TYPES = listOf(
        PlaceTypeInfo(
            id = "supermarket",
            displayName = "Supermarket",
            inferKeywords = listOf("supermarket", "sas", "yerevan city", "carrefour", "auchan", "lidl", "ashan"),
            suggestions = listOf("buy bread", "buy milk", "buy water", "take reusable bags", "buy items from family list")
        ),
        PlaceTypeInfo(
            id = "household",
            displayName = "Household goods",
            inferKeywords = listOf(
                "household", "home goods", "home good", "cleaning", "detergent", "hygiene",
                "soap", "shampoo", "toilet paper", "paper towel", "cups", "dish soap",
                "laundry", "bucket", "mop", "sponge", "trash bag", "plastic goods",
                "хозяйственный", "хозтовары", "бытовая химия", "товары для дома",
                "мыло", "шампунь", "туалетная бумага",
                "տնտեսական", "կենցաղային", "լվացող"
            ),
            suggestions = listOf("buy detergent", "buy cleaning supplies", "buy trash bags", "buy dishwashing liquid", "buy lightbulb", "buy soap", "buy shampoo", "buy toilet paper")
        ),
        PlaceTypeInfo(
            id = "food_shop",
            displayName = "Food shop",
            inferKeywords = listOf(
                "food shop", "food store", "food market", "mini market", "minimarket",
                "corner shop", "corner store", "grocery", "dairy", "bread shop",
                "продукты", "продуктовый", "мини маркет",
                "մթերք", "մթերային", "սնունդ"
            ),
            suggestions = listOf("buy bread", "buy dairy", "buy vegetables", "buy fruit", "buy snacks", "buy drinks")
        ),
        PlaceTypeInfo(
            id = "bazaar",
            displayName = "Village market",
            inferKeywords = listOf("bazaar", "bazar", "market", "shuka", "շուկա", "рынок"),
            suggestions = listOf("buy vegetables", "buy fruit", "buy herbs", "buy cheese", "buy meat", "buy honey")
        ),
        PlaceTypeInfo(
            id = "building_materials",
            displayName = "Building materials market",
            inferKeywords = listOf("building materials", "construction market", "строительный рынок", "շինանյութ"),
            suggestions = listOf("buy cement", "buy tiles", "buy paint", "buy pipes", "buy wood boards", "check price")
        ),
        PlaceTypeInfo(
            id = "hardware",
            displayName = "Tools & materials shop",
            inferKeywords = listOf("hardware", "tool", "tools", "электроинструмент"),
            suggestions = listOf("buy screws", "buy drill bit", "buy nails", "buy paint brush", "buy cable", "check price")
        ),
        PlaceTypeInfo(
            id = "shopping_mall",
            displayName = "Shopping mall",
            inferKeywords = listOf("mall", "shopping mall", "shopping center", "торговый центр", "тц", "tc"),
            suggestions = listOf("buy clothes", "check new arrivals", "pick up order", "visit food court", "buy shoes")
        ),
        PlaceTypeInfo(
            id = "pet_shop",
            displayName = "Pet shop",
            inferKeywords = listOf("pet shop", "pet store", "зоомагазин", "зоо", "petshop"),
            suggestions = listOf("buy pet food", "buy cat litter", "buy treats", "buy cage accessories", "check for new items")
        )
    )

    // Maps our placeTypeId to Geoapify Places API category strings
    val GEOAPIFY_CATEGORIES = mapOf(
        "supermarket"        to "commercial.supermarket",
        "household"          to "commercial.home_and_garden",
        "food_shop"          to "commercial.convenience",
        "bazaar"             to "commercial.marketplace",
        "building_materials" to "commercial.hardware",
        "hardware"           to "commercial.hardware",
        "shopping_mall"      to "commercial.shopping_mall",
        "pet_shop"           to "commercial.pet"
    )

    // Default search radius (meters) for each place type used in TYPE-mode tasks
    val DEFAULT_TYPE_RADIUS = mapOf(
        "supermarket"        to 300,
        "household"          to 250,
        "food_shop"          to 200,
        "bazaar"             to 400,
        "building_materials" to 500,
        "hardware"           to 300,
        "shopping_mall"      to 500,
        "pet_shop"           to 300
    )

    // Megamall and explicit Onex/Globbing places get special delivery suggestions
    private val ONEX_GLOBBING_PLACE_KEYWORDS = listOf(
        "onex", "globbing", "megamall", "mega mall", "delivery point", "pickup point"
    )
    val ONEX_SUGGESTIONS = listOf(
        "pick up Onex package", "pick up Globbing package", "take ID", "check pickup code"
    )

    fun isOnexOrGlobbing(placeName: String): Boolean {
        val lower = placeName.lowercase()
        return ONEX_GLOBBING_PLACE_KEYWORDS.any { lower.contains(it) }
    }

    fun inferPlaceType(placeName: String): PlaceTypeInfo? {
        val lower = placeName.lowercase()
        return DEFAULT_PLACE_TYPES.firstOrNull { type ->
            type.inferKeywords.any { kw -> lower.contains(kw) }
        }
    }

    fun getById(id: String): PlaceTypeInfo? = DEFAULT_PLACE_TYPES.find { it.id == id }
}
