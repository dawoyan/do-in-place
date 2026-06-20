package com.davoyans.doinplace.util

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaceLabelResolverTest {
    @Test
    fun prefersExactPlaceNameAndAddress() {
        val resolved = PlaceLabelResolver.resolve(
            exactPlaceName = "MG Supermarket",
            exactPlaceAddress = "Komitas Ave 12",
            savedPlaceName = "Store"
        )

        assertEquals("MG Supermarket", resolved.primaryName)
        assertEquals("Komitas Ave 12", resolved.address)
        assertEquals("You're next to MG Supermarket, Komitas Ave 12", resolved.notificationLine)
    }
}
