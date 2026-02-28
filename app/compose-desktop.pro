-dontwarn **
-ignorewarnings

-dontnote io.github.oshai.kotlinlogging.**
-dontnote io.ktor.**
-dontnote org.slf4j.**
-dontnote org.lwjgl.**
-dontnote com.sun.jna.**

-dontwarn ch.qos.logback.**
-dontwarn io.github.oshai.kotlinlogging.logback.**

-dontpreverify

-keep class kotlinx.rpc.** { *; }
-keep class dev.dertyp.** { *; }

-keep class org.koin.** { *; }
-keepattributes *Annotation*, Signature
-keepclassmembers class * {
    @org.koin.core.annotation.** *;
}

-keep class cafe.adriel.voyager.** { *; }

-keep class io.ktor.** { *; }
-keep class coil3.** { *; }

-keep class okio.** { *; }
-keep class kotlinx.io.** { *; }

-keep class org.jflac.** { *; }

-keep class com.kmpalette.** { *; }

-keep class org.slf4j.** { *; }

-keep class com.charleskorn.kaml.** { *; }
-keep class it.krzeminski.snakeyaml.engine.kmp.** { *; }

-keep class com.russhwolf.settings.** { *; }