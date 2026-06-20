package com.davoyans.doinplace.ui.places

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class PlacesRoutesTest {
    @Test
    fun addFromPlacesDoesNotReuseTaskPickerRoute() {
        assertEquals(PlacesRoutes.EDITOR, PlacesRoutes.addFromPlacesRoute())
        assertNotEquals(PlacesRoutes.pickForTaskRoute(), PlacesRoutes.addFromPlacesRoute())
    }
}
