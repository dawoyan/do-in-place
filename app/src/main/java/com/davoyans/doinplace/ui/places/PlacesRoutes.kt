package com.davoyans.doinplace.ui.places

object PlacesRoutes {
    const val TASK_PICKER = "place_picker"
    const val EDITOR = "place_editor"
    const val DETAIL = "place_detail"

    fun addFromPlacesRoute(): String = EDITOR
    fun pickForTaskRoute(): String = TASK_PICKER
}
