package dev.dertyp.synara.ui

@Target(AnnotationTarget.CLASS)
annotation class LucidePack

@Target(AnnotationTarget.CLASS)
annotation class MaterialPack

enum class FillMode {
    Unspecified, Filled, Outlined
}

@Repeatable
@Target(AnnotationTarget.PROPERTY)
annotation class MapTo(
    val iconId: SynaraIcons,
    val fillMode: FillMode = FillMode.Unspecified
)
