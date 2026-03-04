package dev.dertyp.synara.utils

import com.github.luben.zstd.Zstd

actual fun compress(data: ByteArray): ByteArray {
    return Zstd.compress(data)
}

actual fun decompress(data: ByteArray): ByteArray {
    val size = Zstd.getFrameContentSize(data)
    return if (size > 0) {
        Zstd.decompress(data, size.toInt())
    } else {
        data
    }
}
