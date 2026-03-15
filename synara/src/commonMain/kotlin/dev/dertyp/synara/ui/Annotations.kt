package dev.dertyp.synara.ui

@Target(AnnotationTarget.CLASS)
annotation class LucidePack

@Target(AnnotationTarget.CLASS)
annotation class MaterialPack

@Target(AnnotationTarget.CLASS)
annotation class PhosphorPack

enum class FillMode {
    Unspecified, Filled, Outlined
}

@Suppress("unused")
@Repeatable
@Target(AnnotationTarget.PROPERTY)
annotation class MapTo(
    val iconId: SynaraIcons,
    val fillMode: FillMode = FillMode.Unspecified
)
