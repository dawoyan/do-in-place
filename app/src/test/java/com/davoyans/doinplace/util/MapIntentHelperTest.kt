package com.davoyans.doinplace.util

import kotlin.test.Test
import kotlin.test.assertEquals

class MapIntentHelperTest {
    @Test
    fun buildSearchQueryUsesNameAndAddress() {
        assertEquals("MG Supermarket, Komitas Ave 12", MapIntentHelper.buildSearchQuery("MG Supermarket", "Komitas Ave 12"))
        assertEquals("MG Supermarket", MapIntentHelper.buildSearchQuery("MG Supermarket", null))
    }
}
