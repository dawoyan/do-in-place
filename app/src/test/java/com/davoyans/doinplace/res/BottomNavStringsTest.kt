package com.davoyans.doinplace.res

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class BottomNavStringsTest {

    @Test
    fun savedPlacesLabelUsesPlacesInAllSupportedLocales() {
        assertContains("src/main/res/values/strings.xml", """<string name="saved_places">Places</string>""")
        assertContains("src/main/res/values-hy/strings.xml", """<string name="saved_places">Վայրեր</string>""")
        assertContains("src/main/res/values-ru/strings.xml", """<string name="saved_places">Места</string>""")
    }

    private fun assertContains(path: String, expected: String) {
        val content = sequenceOf(
            File(path),
            File("app").resolve(path)
        ).firstOrNull { it.exists() }?.readText()
        assertTrue(
            actual = content?.contains(expected) == true,
            message = "Expected $path to contain $expected"
        )
    }
}
