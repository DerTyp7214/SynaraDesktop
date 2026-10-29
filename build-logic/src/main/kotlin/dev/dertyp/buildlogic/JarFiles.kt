package dev.dertyp.buildlogic

import java.io.File
import java.security.MessageDigest
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private val shrunkPackagePrefixes = listOf("com/composables/icons/", "icons/")

private val reproducibleEntryTime = GregorianCalendar(1980, Calendar.FEBRUARY, 1, 0, 0, 0).timeInMillis

internal fun File.composeContentHash(): String =
    MessageDigest.getInstance("MD5").digest(readBytes()).joinToString("") { Integer.toHexString(it.toInt() and 0xFF) }

internal fun File.holdsShrunkPackages(): Boolean =
    ZipFile(this).use { zip -> zip.stream().anyMatch { entry -> shrunkPackagePrefixes.any { entry.name.startsWith(it) } } }

internal fun File.normalizeJarEntries() {
    val normalized = resolveSibling("$name.normalized")
    ZipFile(this).use { zip ->
        ZipOutputStream(normalized.outputStream().buffered()).use { out ->
            zip.entries().asSequence()
                .sortedWith(compareBy({ it.name != "META-INF/MANIFEST.MF" }, { it.name }))
                .forEach { entry ->
                    out.putNextEntry(ZipEntry(entry.name).apply { time = reproducibleEntryTime })
                    zip.getInputStream(entry).use { it.copyTo(out) }
                    out.closeEntry()
                }
        }
    }
    normalized.copyTo(this, overwrite = true)
    normalized.delete()
}
