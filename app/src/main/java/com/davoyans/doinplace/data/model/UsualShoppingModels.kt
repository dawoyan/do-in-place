package com.davoyans.doinplace.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "usual_shopping_sessions")
data class UsualShoppingSession(
    @PrimaryKey val id: String,
    val userId: String,
    val taskId: String,
    val placeTypeKey: String,
    val placeName: String,
    val completedAt: Long
)

@Entity(
    tableName = "usual_shopping_item_stats",
    indices = [Index(value = ["userId", "placeTypeKey", "normalizedItem"], unique = true)]
)
data class UsualShoppingItemStats(
    @PrimaryKey val id: String,
    val userId: String,
    val placeTypeKey: String,
    val normalizedItem: String,
    val displayItem: String,
    val buyCount: Int = 0,
    val suggestedCount: Int = 0,
    val acceptedCount: Int = 0,
    val dismissCount: Int = 0,
    val lastBoughtAt: Long = 0,
    val lastDismissedAt: Long = 0,
    val suppressedUntil: Long = 0
)

@Entity(
    tableName = "usual_shopping_suppressions",
    indices = [Index(value = ["userId", "placeTypeKey"], unique = true)]
)
data class UsualShoppingSuppression(
    @PrimaryKey val id: String,
    val userId: String,
    val placeTypeKey: String,
    val suppressedUntil: Long,
    val lastShownAt: Long
)

@Entity(
    tableName = "food_health_tags",
    indices = [Index(value = ["normalizedName", "language"], unique = true)]
)
data class FoodHealthTag(
    @PrimaryKey val id: String,
    val normalizedName: String,
    val language: String,
    val healthTag: String,
    val suggestion: String? = null,
    val subcategory: String? = null
)

@Entity(
    tableName = "user_food_health_overrides",
    indices = [Index(value = ["userId", "normalizedName"], unique = true)]
)
data class UserFoodHealthOverride(
    @PrimaryKey val id: String,
    val userId: String,
    val normalizedName: String,
    val healthTag: String
)
