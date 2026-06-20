package com.davoyans.doinplace.ui.cards

data class SavedCardUiPolicy(
    val showsRawCode: Boolean,
    val showsPassword: Boolean,
    val showsDelete: Boolean
)

object SavedCardUiPolicies {
    fun displayMode(): SavedCardUiPolicy = SavedCardUiPolicy(
        showsRawCode = false,
        showsPassword = false,
        showsDelete = false
    )

    fun editMode(): SavedCardUiPolicy = SavedCardUiPolicy(
        showsRawCode = true,
        showsPassword = true,
        showsDelete = true
    )
}
