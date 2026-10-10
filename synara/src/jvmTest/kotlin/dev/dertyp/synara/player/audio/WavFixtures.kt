package dev.dertyp.synara.player.audio

object WavFixtures {
    private fun ascii(text: String) = text.toByteArray(Charsets.US_ASCII)

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    fun pcm16(sampleRate: Int, channelSamples: List<ShortArray>): ByteArray {
        val channels = channelSamples.size
        val frames = channelSamples.first().size
        val data = ByteArray(frames * channels * 2)
        for (i in 0 until frames) {
            for (c in 0 until channels) {
                val value = channelSamples[c][i].toInt()
                data[(i * channels + c) * 2] = value.toByte()
                data[(i * channels + c) * 2 + 1] = (value shr 8).toByte()
            }
        }
        val blockAlign = channels * 2
        val fmt = le16(1) + le16(channels) + le32(sampleRate) + le32(sampleRate * blockAlign) + le16(blockAlign) + le16(16)
        return ascii("RIFF") + le32(36 + data.size) + ascii("WAVE") + ascii("fmt ") + le32(16) + fmt +
            ascii("data") + le32(data.size) + data
    }
}
