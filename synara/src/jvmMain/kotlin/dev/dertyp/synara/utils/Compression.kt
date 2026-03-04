package dev.dertyp.synara.utils

import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZInputStream
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

actual fun compress(data: ByteArray): ByteArray {
    val baos = ByteArrayOutputStream()
    val xzos = XZOutputStream(baos, LZMA2Options())
    xzos.write(data)
    xzos.finish()
    xzos.close()
    return baos.toByteArray()
}

actual fun decompress(data: ByteArray): ByteArray {
    val bais = ByteArrayInputStream(data)
    val xzis = XZInputStream(bais)
    val result = xzis.readBytes()
    xzis.close()
    return result
}
