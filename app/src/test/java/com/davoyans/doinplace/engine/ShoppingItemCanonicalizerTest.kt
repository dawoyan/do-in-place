package com.davoyans.doinplace.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShoppingItemCanonicalizerTest {
    @Test
    fun canonicalizesEnglishReceiptLines() {
        assertCanonical("Ararat Milk 3.2% 1L", "milk")
        assertCanonical("Coca-Cola Zero 1.5L", "cola")
        assertCanonical("Lavash 300g", "lavash")
        assertCanonical("Bonduelle Corn 340g", "corn")
        assertCanonical("Fairy Lemon 900ml", "detergent")
        assertCanonical("Pedigree Dog Food 2kg", "dog food")
    }

    @Test
    fun canonicalizesRussianAndArmenianReceiptLines() {
        assertCanonical("Լավաշ 300գ", "լավաշ")
        assertCanonical("Молоко Простоквашино 3.2% 950мл", "молоко")
    }

    @Test
    fun tracksRemovedSegmentsForDebugLogging() {
        val result = ShoppingItemCanonicalizer.canonicalize("Ararat Milk 3.2% 1L", emitLog = false)

        assertEquals("milk", result.canonicalName)
        assertTrue("brand" in result.removed)
        assertTrue("percent" in result.removed)
        assertTrue("volume" in result.removed)
    }

    private fun assertCanonical(raw: String, expected: String) {
        val result = ShoppingItemCanonicalizer.canonicalize(raw, emitLog = false)

        assertEquals(expected, result.canonicalName, raw)
        assertEquals(ShoppingItemCanonicalizer.Confidence.HIGH, result.confidence, raw)
    }
}
