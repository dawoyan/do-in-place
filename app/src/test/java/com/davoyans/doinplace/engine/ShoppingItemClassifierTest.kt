package com.davoyans.doinplace.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ShoppingItemClassifierTest {
    @Test
    fun classifyEnglishExamples() {
        assertEquals(ShoppingItemCategory.DAIRY, ShoppingItemClassifier.classify("milk"))
        assertEquals(ShoppingItemCategory.BREAD_BAKERY, ShoppingItemClassifier.classify("bread"))
        assertEquals(ShoppingItemCategory.BEVERAGES, ShoppingItemClassifier.classify("juice"))
        assertEquals(ShoppingItemCategory.FRUITS_VEGETABLES, ShoppingItemClassifier.classify("tomato"))
        assertEquals(ShoppingItemCategory.HOUSEHOLD, ShoppingItemClassifier.classify("soap"))
    }

    @Test
    fun classifyRussianExamples() {
        assertEquals(ShoppingItemCategory.DAIRY, ShoppingItemClassifier.classify("молоко"))
        assertEquals(ShoppingItemCategory.BREAD_BAKERY, ShoppingItemClassifier.classify("лаваш"))
        assertEquals(ShoppingItemCategory.BEVERAGES, ShoppingItemClassifier.classify("вода"))
        assertEquals(ShoppingItemCategory.FRUITS_VEGETABLES, ShoppingItemClassifier.classify("огурец"))
        assertEquals(ShoppingItemCategory.HOUSEHOLD, ShoppingItemClassifier.classify("порошок"))
    }

    @Test
    fun classifyArmenianExamples() {
        assertEquals(ShoppingItemCategory.DAIRY, ShoppingItemClassifier.classify("կաթ"))
        assertEquals(ShoppingItemCategory.DAIRY, ShoppingItemClassifier.classify("յոգուրտ"))
        assertEquals(ShoppingItemCategory.BREAD_BAKERY, ShoppingItemClassifier.classify("լավաշ"))
        assertEquals(ShoppingItemCategory.BEVERAGES, ShoppingItemClassifier.classify("հյութ"))
        assertEquals(ShoppingItemCategory.HOUSEHOLD, ShoppingItemClassifier.classify("լվացող"))
    }
}
