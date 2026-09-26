package dev.dertyp.synara.player

class StereoSpectrum(val left: FloatArray, val right: FloatArray) {
    companion object {
        val EMPTY = StereoSpectrum(FloatArray(512), FloatArray(512))
    }
}
