package com.davoyans.doinplace.ui.cards

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedCardUiPolicyTest {
    @Test
    fun displayModeHidesSensitiveFields() {
        val policy = SavedCardUiPolicies.displayMode()
        assertFalse(policy.showsRawCode)
        assertFalse(policy.showsPassword)
        assertFalse(policy.showsDelete)
    }

    @Test
    fun editModeShowsSensitiveFields() {
        val policy = SavedCardUiPolicies.editMode()
        assertTrue(policy.showsRawCode)
        assertTrue(policy.showsPassword)
        assertTrue(policy.showsDelete)
    }
}
