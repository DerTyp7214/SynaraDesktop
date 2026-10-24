package dev.dertyp.synara.ui.server

import dev.dertyp.ui.UiImageRef

object UiTestTags {
    fun image(image: UiImageRef) = "uiImage:${image.imageId}"
    const val ICON = "uiIcon"
    fun searchField(key: String) = "uiSearchField:$key"
    fun searchOption(value: String) = "uiSearchOption:$value"
    fun formSubmit(formId: String) = "uiFormSubmit:$formId"
}
