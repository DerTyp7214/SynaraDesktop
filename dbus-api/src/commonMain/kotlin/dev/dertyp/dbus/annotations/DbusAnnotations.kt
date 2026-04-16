@file:Suppress("unused")

package dev.dertyp.dbus.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class DbusDoc(val description: String)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class DbusMethodDoc(
    val description: String,
    val returnsModel: KClass<*> = Any::class,
    val returnsList: Boolean = false,
    val example: String = ""
)

@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.SOURCE)
annotation class DbusParamDoc(
    val name: String,
    val description: String,
    val example: String = "",
    val keysModel: KClass<*> = Any::class
)

@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.SOURCE)
annotation class DbusFieldDoc(val description: String)
