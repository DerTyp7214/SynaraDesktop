package dev.dertyp.synara.material


class TonalPalette private constructor(private val hue: Double, private val chroma: Double) {
    
    fun tone(tone: Int): Int {
        return Hct.from(hue, chroma, tone.toDouble()).toInt()
    }

    companion object {
        fun fromInt(argb: Int): TonalPalette {
            val hct = Hct.fromInt(argb)
            return TonalPalette(hct.hue, hct.chroma)
        }
        
        fun fromHueAndChroma(hue: Double, chroma: Double): TonalPalette {
            return TonalPalette(hue, chroma)
        }
    }
}
