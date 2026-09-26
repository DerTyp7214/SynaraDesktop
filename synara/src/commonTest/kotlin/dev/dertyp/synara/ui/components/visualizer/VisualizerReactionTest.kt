package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.settings.*
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VisualizerReactionTest {
    private val heightPx = 100f
    private val minHeightPx = 3f
    private val binWidth = MonstercatReaction.binWidthHz(512)

    private fun VisualizerReaction.updateMono(
        fft: FloatArray,
        isPlaying: Boolean,
        heights: FloatArray,
        bandCount: Int,
        heightPx: Float,
        minHeightPx: Float,
        deltaMs: Long
    ) = update(arrayOf(fft), isPlaying, arrayOf(heights), bandCount, heightPx, minHeightPx, deltaMs)

    private fun loudFft(size: Int = 512) = FloatArray(size) { 0.1f }

    private fun pinkFft(size: Int = 512, scale: Float = 0.5f) =
        FloatArray(size) { if (it == 0) 0f else scale / sqrt(it * binWidth / 40f) }

    @Test
    fun bandEdgeFrequenciesAreMonotonicAndSpanMonstercatRange() {
        val hz = MonstercatReaction.bandEdgeFrequencies(60)
        assertEquals(61, hz.size)
        assertTrue(abs(hz.first() - 40f) < 0.01f)
        assertTrue(abs(hz.last() - 16000f) < 1f)
        for (i in 1 until hz.size) assertTrue(hz[i] > hz[i - 1])
        val ratio = hz[1] / hz[0]
        for (i in 1 until hz.size) assertTrue(abs(hz[i] / hz[i - 1] - ratio) < 0.001f)
    }

    @Test
    fun bandEdgesMapFrequenciesToBins() {
        val edges = MonstercatReaction.bandEdges(60, 512)
        assertTrue(abs(edges.first() - 40f / binWidth) < 0.001f)
        assertTrue(abs(edges.last() - 16000f / binWidth) < 0.01f)
        assertTrue(edges.last() < 512f)
        for (i in 1 until edges.size) assertTrue(edges[i] > edges[i - 1])
    }

    @Test
    fun bandLevelsInterpolateNarrowBandsAndTakePeakOfWideOnes() {
        val fft = floatArrayOf(9f, 1f, 2f, 3f, 7f, 5f, 6f, 4f)
        val levels = MonstercatReaction.bandLevels(fft, floatArrayOf(1f, 1.5f, 4f, 8f))
        assertEquals(1.25f, levels[0])
        assertEquals(3f, levels[1])
        assertEquals(7f, levels[2])
    }

    @Test
    fun bandLevelsSkipDcBin() {
        val fft = floatArrayOf(9f, 1f, 5f, 3f)
        val levels = MonstercatReaction.bandLevels(fft, floatArrayOf(0f, 0.5f, 3f))
        assertEquals(1f, levels[0])
        assertEquals(5f, levels[1])
    }

    @Test
    fun falloffSpreadsSpikeToNeighbours() {
        val values = floatArrayOf(0f, 0f, 0f, 80f, 0f, 0f, 0f)
        MonstercatReaction.applyFalloff(values, 2f)
        assertEquals(80f, values[3])
        assertEquals(40f, values[2])
        assertEquals(40f, values[4])
        assertEquals(20f, values[1])
        assertEquals(20f, values[5])
        assertEquals(10f, values[0])
    }

    @Test
    fun falloffUsesPerBandFactors() {
        val values = floatArrayOf(90f, 0f, 0f, 0f)
        MonstercatReaction.applyFalloff(values, floatArrayOf(1.5f, 2f, 2f, 2f))
        assertEquals(60f, values[1])
        assertEquals(30f, values[2])
        assertEquals(15f, values[3])
    }

    @Test
    fun bandFalloffsSoftenOnlyTheBass() {
        val falloffs = MonstercatReaction.bandFalloffs(40, 1.5f, 2f, 0.25f)
        assertEquals(1.5f, falloffs[0])
        for (i in 1 until falloffs.size) assertTrue(falloffs[i] >= falloffs[i - 1])
        assertTrue(falloffs.drop(10).all { it == 2f })
    }

    @Test
    fun tiltIsFlatThroughTheBassAndLiftsAboveTheCorner() {
        val edges = MonstercatReaction.bandEdges(40, 512)
        val gains = MonstercatReaction.bandGains(edges, 512, tilt = 0.5f)
        val hz = MonstercatReaction.bandEdgeFrequencies(40)
        for (i in gains.indices) {
            val center = sqrt(hz[i] * hz[i + 1])
            if (center <= 240f) assertTrue(abs(gains[i] - 1f) < 1e-3f)
            else if (center > 260f) assertTrue(abs(gains[i] - (center / 250f).pow(0.5f)) < 0.02f * gains[i])
        }
        for (i in 1 until gains.size) assertTrue(gains[i] >= gains[i - 1])
        assertTrue(gains.last() < 9f)
    }

    @Test
    fun falloffKeepsHigherValues() {
        val values = floatArrayOf(10f, 90f, 80f)
        MonstercatReaction.applyFalloff(values, 2f)
        assertEquals(80f, values[2])
        assertEquals(45f, values[0])
    }

    private fun preset(id: String) = VisualizerPresets.builtIns.first { it.id == id }

    private fun assertSameHeights(a: VisualizerReaction, b: VisualizerReaction, frames: (Int) -> Pair<FloatArray, Boolean>) {
        val bands = 24
        val first = FloatArray(bands) { minHeightPx }
        val second = FloatArray(bands) { minHeightPx }
        repeat(300) {
            val (fft, playing) = frames(it)
            a.updateMono(fft, playing, first, bands, heightPx, minHeightPx, 16L)
            b.updateMono(fft, playing, second, bands, heightPx, minHeightPx, 16L)
            for (i in 0 until bands) assertEquals(first[i], second[i])
        }
    }

    @Test
    fun speedsAtHalfKeepTheOriginalConstants() {
        assertEquals(1f, speedFactor(0.5f))
        assertEquals(0.2f, scaledBase(SynaraReaction.RISE_BASE, 0.5f))
        assertEquals(0.88f, scaledBase(SynaraReaction.FALL_BASE, 0.5f))
        assertEquals(0.3f, scaledBase(MonstercatReaction.RISE_NOISE_REDUCTION, 0.5f))
        assertEquals(600f, MonstercatReaction.FALL_DURATION_MS / speedFactor(0.5f))
        assertTrue(scaledBase(SynaraReaction.RISE_BASE, 1f) < 0.2f)
        assertTrue(scaledBase(SynaraReaction.RISE_BASE, 0f) > 0.2f)
        assertTrue(scaledBase(SynaraReaction.FALL_BASE, 1f) < 0.88f)
        assertTrue(scaledBase(SynaraReaction.FALL_BASE, 0f) > 0.88f)
    }

    @Test
    fun monstercatPresetReactsLikeTheDefaultReaction() {
        val random = Random(3)
        assertSameHeights(
            preset(VisualizerPresets.MONSTERCAT_ID).createReaction(44100),
            MonstercatReaction()
        ) { i -> kickFrame(i * 16f, random).let { frame -> frame to (i < 250) } }
    }

    @Test
    fun synaraPresetReactsLikeTheDefaultReaction() {
        assertSameHeights(
            preset(VisualizerPresets.SYNARA_ID).createReaction(44100),
            SynaraReaction()
        ) { i -> loudFft() to (i < 150) }
    }

    @Test
    fun unknownSampleRateFallsBackToDefault() {
        val random = Random(5)
        assertSameHeights(
            preset(VisualizerPresets.MONSTERCAT_ID).createReaction(0),
            preset(VisualizerPresets.MONSTERCAT_ID).createReaction(DEFAULT_SAMPLE_RATE)
        ) { i -> kickFrame(i * 16f, random) to true }
    }

    @Test
    fun synaraHzRangeRestrictsCountedBins() {
        val sampleRate = 44100f
        val binWidthHz = sampleRate / 2f / 512
        val fft = FloatArray(512)
        val toneBin = (5000f / binWidthHz).toInt()
        fft[toneBin] = 0.1f
        val wide = SynaraReaction(lowHz = 20f, highHz = 11025f, sampleRate = sampleRate)
        val narrow = SynaraReaction(lowHz = 20f, highHz = 2000f, sampleRate = sampleRate)
        val wideHeights = FloatArray(8) { minHeightPx }
        val narrowHeights = FloatArray(8) { minHeightPx }
        repeat(50) {
            wide.updateMono(fft, true, wideHeights, 8, heightPx, minHeightPx, 16L)
            narrow.updateMono(fft, true, narrowHeights, 8, heightPx, minHeightPx, 16L)
        }
        assertTrue(wideHeights.any { it > heightPx * 0.9f })
        assertTrue(narrowHeights.all { abs(it - minHeightPx) < 0.01f })
    }

    @Test
    fun monstercatHzRangeRestrictsCountedBins() {
        val fft = FloatArray(512)
        fft[(10000f / binWidth).toInt()] = 0.1f
        val wide = MonstercatReaction(highHz = 16000f)
        val narrow = MonstercatReaction(highHz = 4000f)
        val wideHeights = FloatArray(8) { minHeightPx }
        val narrowHeights = FloatArray(8) { minHeightPx }
        repeat(50) {
            wide.updateMono(fft, true, wideHeights, 8, heightPx, minHeightPx, 16L)
            narrow.updateMono(fft, true, narrowHeights, 8, heightPx, minHeightPx, 16L)
        }
        assertTrue(wideHeights.any { it > minHeightPx + 1f })
        assertTrue(narrowHeights.all { abs(it - minHeightPx) < 0.01f })
    }

    @Test
    fun logAndLinearBandEdgesDiffer() {
        val log = MonstercatReaction.bandEdgeFrequencies(10, lowHz = 100f, highHz = 10000f, scale = FrequencyScale.Log)
        val linear = MonstercatReaction.bandEdgeFrequencies(10, lowHz = 100f, highHz = 10000f, scale = FrequencyScale.Linear)
        assertTrue(abs(log.first() - 100f) < 0.01f && abs(linear.first() - 100f) < 0.01f)
        assertTrue(abs(log.last() - 10000f) < 1f && abs(linear.last() - 10000f) < 1f)
        for (i in 1 until 11) {
            assertTrue(abs(log[i] / log[i - 1] - 10f.pow(0.2f)) < 0.001f)
            assertTrue(abs((linear[i] - linear[i - 1]) - 990f) < 0.1f)
        }
        assertTrue(log[5] < linear[5])

        val synaraLog = SynaraReaction.bandEdgeBins(10, 512, 44100f, 100f, 10000f, FrequencyScale.Log)
        val synaraLinear = SynaraReaction.bandEdgeBins(10, 512, 44100f, 100f, 10000f, FrequencyScale.Linear)
        assertTrue(abs(synaraLog.first() - synaraLinear.first()) < 0.001f)
        assertTrue(abs(synaraLog.last() - synaraLinear.last()) < 0.001f)
        assertTrue(synaraLog[5] < synaraLinear[5])
    }

    @Test
    fun synaraLinearRangeStartsAtTheFirstBin() {
        val edges = SynaraReaction.bandEdgeBins(8, 512, 44100f, 20f, 11025f, FrequencyScale.Linear)
        assertEquals(0, edges.first().toInt())
        assertTrue(abs(edges.last() - 256f) < 0.01f)
    }

    @Test
    fun synaraFixedGainKeepsWindowWhileAutoGainAdapts() {
        val quiet = FloatArray(512) { 0.002f }
        val fixed = SynaraReaction(lowHz = 20f, highHz = 11025f, autoGain = false)
        val auto = SynaraReaction(lowHz = 20f, highHz = 11025f, autoGain = true)
        val fixedHeights = FloatArray(8) { minHeightPx }
        val autoHeights = FloatArray(8) { minHeightPx }
        repeat(600) {
            fixed.updateMono(quiet, true, fixedHeights, 8, heightPx, minHeightPx, 16L)
            auto.updateMono(quiet, true, autoHeights, 8, heightPx, minHeightPx, 16L)
        }
        assertEquals(-20f, fixed.ceilingDb)
        assertTrue(auto.ceilingDb < -40f)
        assertTrue(autoHeights.average() > fixedHeights.average() * 2)
    }

    @Test
    fun monstercatFixedGainIgnoresLevelChanges() {
        val loud = MonstercatReaction(autoGain = false, minDb = -60f, maxDb = -20f)
        val heights = FloatArray(16) { minHeightPx }
        repeat(300) { loud.updateMono(loudFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertEquals(1f, loud.sensitivity)
        assertTrue(heights.all { it > heightPx * 0.8f })

        val silentFloor = MonstercatReaction(autoGain = false, minDb = -30f, maxDb = -20f)
        val quietHeights = FloatArray(16) { minHeightPx }
        repeat(300) { silentFloor.updateMono(FloatArray(512) { 0.005f }, true, quietHeights, 16, heightPx, minHeightPx, 16L) }
        assertTrue(quietHeights.all { abs(it - minHeightPx) < 0.01f })

        val auto = MonstercatReaction()
        repeat(300) { auto.updateMono(loudFft(), true, FloatArray(16) { minHeightPx }, 16, heightPx, minHeightPx, 16L) }
        assertTrue(auto.sensitivity != 1f)
    }

    @Test
    fun monstercatFillsHalfCountBandsWithBassAboveTreble() {
        val halfCount = 20
        val reaction = MonstercatReaction()
        val heights = FloatArray(halfCount) { minHeightPx }
        repeat(200) { reaction.updateMono(pinkFft(), true, heights, halfCount, heightPx, minHeightPx, 16L) }
        val targets = reaction.currentTargets()
        assertEquals(halfCount, targets.size)
        assertTrue(heights.all { it > minHeightPx })
        assertTrue(heights.first() > heights.last())
    }

    @Test
    fun monstercatRisesQuicklyWithNoiseReduction() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(200) { reaction.updateMono(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        heights.fill(minHeightPx)
        reaction.updateMono(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L)
        val targets = reaction.currentTargets()
        val peak = targets.indices.maxBy { targets[it] }
        val expected = minHeightPx + (targets[peak] - minHeightPx) * 0.7f
        assertTrue(abs(heights[peak] - expected) < 0.01f)
        repeat(3) { reaction.updateMono(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertTrue(heights[peak] > targets[peak] * 0.95f)
    }

    @Test
    fun monstercatFallsWithoutHold() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(8) { minHeightPx }
        repeat(200) { reaction.updateMono(pinkFft(), true, heights, 8, heightPx, minHeightPx, 16L) }
        val peak = heights.copyOf()
        reaction.updateMono(pinkFft(), false, heights, 8, heightPx, minHeightPx, 16L)
        assertTrue(heights.indices.all { heights[it] < peak[it] || peak[it] == minHeightPx })
    }

    @Test
    fun monstercatGravityReachesMinimumHeight() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(8) { minHeightPx }
        repeat(200) { reaction.updateMono(pinkFft(), true, heights, 8, heightPx, minHeightPx, 16L) }
        val drops = mutableListOf<Float>()
        var previous = heights[0]
        repeat(200) {
            reaction.updateMono(pinkFft(), false, heights, 8, heightPx, minHeightPx, 16L)
            drops += previous - heights[0]
            previous = heights[0]
        }
        assertTrue(heights.all { it == minHeightPx })
        val falling = drops.filter { it > 0f }
        assertTrue(falling.size >= 3)
        assertTrue(falling[1] > falling[0])
    }

    @Test
    fun synaraRisesQuicklyAndSettlesAtMinimumWhenStopped() {
        val reaction = SynaraReaction()
        val heights = FloatArray(8) { minHeightPx }
        reaction.updateMono(loudFft(), true, heights, 8, heightPx, minHeightPx, 16L)
        assertTrue(heights.all { it > minHeightPx })
        repeat(500) { reaction.updateMono(loudFft(), false, heights, 8, heightPx, minHeightPx, 16L) }
        assertTrue(heights.all { abs(it - minHeightPx) < 0.01f })
    }

    @Test
    fun monstercatPinkSpectrumKeepsContrastBelowFullHeight() {
        val barCount = 60
        val reaction = MonstercatReaction()
        val heights = FloatArray(barCount) { minHeightPx }
        repeat(1000) { reaction.updateMono(pinkFft(), true, heights, barCount, heightPx, minHeightPx, 16L) }
        val targets = reaction.currentTargets()
        val max = targets.max()
        val median = targets.sorted()[barCount / 2]
        assertTrue(max <= heightPx)
        assertTrue(max > heightPx * 0.85f)
        assertTrue(median < max * 0.7f)
        assertTrue(targets.min() < max)
        assertTrue(targets.distinct().size > barCount / 2)
    }

    @Test
    fun monstercatSilenceKeepsSensitivity() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(500) { reaction.updateMono(FloatArray(512), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertEquals(1f, reaction.sensitivity)
    }

    @Test
    fun monstercatSilenceAfterMusicKeepsSensitivity() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(300) { reaction.updateMono(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        val settled = reaction.sensitivity
        repeat(500) { reaction.updateMono(FloatArray(512), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertEquals(settled, reaction.sensitivity)
    }

    @Test
    fun monstercatSensitivityRecoversAfterLoudPassage() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(300) { reaction.updateMono(loudFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        val afterLoud = reaction.sensitivity
        val quiet = FloatArray(512) { 0.005f }
        repeat(300) { reaction.updateMono(quiet, true, heights, 16, heightPx, minHeightPx, 16L) }
        assertTrue(reaction.sensitivity > afterLoud * 1.5f)
    }

    private fun kickFrame(t: Float, random: Random): FloatArray {
        val beat = 469f
        val phase = t % beat
        val hatPhase = (t + beat / 2f) % beat
        val kick = exp(-phase / 90f)
        val click = exp(-phase / 15f)
        val hat = exp(-hatPhase / 35f)
        return FloatArray(512) { k ->
            if (k == 0) return@FloatArray 0f
            val f = k * binWidth
            val d = (f - 55f) / 60f
            var v = 0.02f * (f / 100f).pow(-0.5f) * (0.7f + 0.6f * random.nextFloat())
            v += 0.15f * kick * exp(-d * d)
            if (f in 1000f..5000f) v += 0.01f * click
            if (f > 6000f) v += 0.012f * hat * (0.5f + random.nextFloat())
            v + 0.0005f * random.nextFloat()
        }
    }

    @Test
    fun monstercatKickPunchesTheBassAboveMidsAndTreble() {
        val bands = 30
        val beat = 469f
        val reaction = MonstercatReaction()
        val heights = FloatArray(bands) { minHeightPx }
        val hz = MonstercatReaction.bandEdgeFrequencies(bands)
        val centers = FloatArray(bands) { sqrt(hz[it] * hz[it + 1]) }
        val bass = 0 until bands / 5
        val mid = centers.indices.filter { centers[it] in 500f..4000f }
        val treble = centers.indices.filter { centers[it] >= 6000f }
        val random = Random(7)
        var bassSum = 0f
        var midSum = 0f
        var trebleSum = 0f
        var trebleMax = 0f
        var frames = 0
        val kickPeaks = mutableListOf<Float>()
        val beatPeaks = mutableListOf<Float>()
        val beatTroughs = mutableListOf<Float>()
        var currentBeat = -1
        var peak = 0f
        var bassPeak = 0f
        var trough = Float.MAX_VALUE
        for (i in 0 until 1900) {
            val t = i * 16f
            reaction.updateMono(kickFrame(t, random), true, heights, bands, heightPx, minHeightPx, 16L)
            if (t < 10000f) continue
            val bassMean = bass.map { heights[it] }.average().toFloat() / heightPx
            bassSum += bassMean
            midSum += mid.map { heights[it] }.average().toFloat() / heightPx
            trebleSum += treble.map { heights[it] }.average().toFloat() / heightPx
            trebleMax = maxOf(trebleMax, treble.maxOf { heights[it] } / heightPx)
            frames++
            val beatIndex = (t / beat).toInt()
            if (beatIndex != currentBeat) {
                if (currentBeat >= 0) {
                    kickPeaks += peak
                    beatPeaks += bassPeak
                    beatTroughs += trough
                }
                currentBeat = beatIndex
                peak = 0f
                bassPeak = 0f
                trough = Float.MAX_VALUE
            }
            peak = maxOf(peak, bass.maxOf { heights[it] } / heightPx)
            bassPeak = maxOf(bassPeak, bassMean)
            trough = minOf(trough, bassMean)
        }
        val bassMean = bassSum / frames
        val midMean = midSum / frames
        val trebleMean = trebleSum / frames
        assertTrue(kickPeaks.average() > 0.85)
        assertTrue(beatPeaks.average() > 0.6)
        assertTrue(bassMean > midMean * 1.5f)
        assertTrue(beatTroughs.average() < beatPeaks.average() * 0.6)
        assertTrue(trebleMean > 0.15f)
        assertTrue(trebleMax < 0.9f)
    }

    private fun assertHardPannedLeftMatchesMono(stereo: VisualizerReaction, mono: VisualizerReaction, frames: (Int) -> FloatArray) {
        val bands = 24
        val left = FloatArray(bands) { minHeightPx }
        val right = FloatArray(bands) { minHeightPx }
        val single = FloatArray(bands) { minHeightPx }
        val silence = FloatArray(512)
        repeat(400) {
            val fft = frames(it)
            stereo.update(arrayOf(fft, silence), true, arrayOf(left, right), bands, heightPx, minHeightPx, 16L)
            mono.updateMono(fft, true, single, bands, heightPx, minHeightPx, 16L)
            for (i in 0 until bands) {
                assertEquals(single[i], left[i])
                assertEquals(minHeightPx, right[i])
            }
        }
    }

    @Test
    fun synaraSharedGainKeepsSilentSideDownAndLoudSideAtMonoLevel() {
        val stereo = SynaraReaction(autoGain = true)
        val mono = SynaraReaction(autoGain = true)
        val quiet = FloatArray(512) { 0.002f }
        assertHardPannedLeftMatchesMono(stereo, mono) { quiet }
        assertEquals(mono.ceilingDb, stereo.ceilingDb)
        assertTrue(stereo.ceilingDb < -40f)
    }

    @Test
    fun monstercatSharedGainKeepsSilentSideDownAndLoudSideAtMonoLevel() {
        val stereo = MonstercatReaction()
        val mono = MonstercatReaction()
        val random = Random(11)
        val frames = Array(400) { kickFrame(it * 16f, random) }
        assertHardPannedLeftMatchesMono(stereo, mono) { frames[it] }
        assertEquals(mono.sensitivity, stereo.sensitivity)
        assertTrue(stereo.sensitivity != 1f)
    }

    @Test
    fun sharedGainFollowsTheLouderChannel() {
        val stereo = MonstercatReaction()
        val loudOnly = MonstercatReaction()
        val bands = 16
        val quiet = FloatArray(512) { 0.005f }
        repeat(300) {
            stereo.update(arrayOf(loudFft(), quiet), true, arrayOf(FloatArray(bands) { minHeightPx }, FloatArray(bands) { minHeightPx }), bands, heightPx, minHeightPx, 16L)
            loudOnly.updateMono(loudFft(), true, FloatArray(bands) { minHeightPx }, bands, heightPx, minHeightPx, 16L)
        }
        assertEquals(loudOnly.sensitivity, stereo.sensitivity)
    }

    @Test
    fun monstercatChannelsKeepIndependentMotion() {
        val bands = 20
        val stereo = MonstercatReaction(autoGain = false)
        val leftOnly = MonstercatReaction(autoGain = false)
        val rightOnly = MonstercatReaction(autoGain = false)
        val left = FloatArray(bands) { minHeightPx }
        val right = FloatArray(bands) { minHeightPx }
        val leftMono = FloatArray(bands) { minHeightPx }
        val rightMono = FloatArray(bands) { minHeightPx }
        val random = Random(13)
        var diverged = false
        repeat(400) {
            val kick = kickFrame(it * 16f, random)
            val pink = if (it % 90 < 45) pinkFft() else FloatArray(512)
            val playing = it < 350
            stereo.update(arrayOf(kick, pink), playing, arrayOf(left, right), bands, heightPx, minHeightPx, 16L)
            leftOnly.updateMono(kick, playing, leftMono, bands, heightPx, minHeightPx, 16L)
            rightOnly.updateMono(pink, playing, rightMono, bands, heightPx, minHeightPx, 16L)
            for (i in 0 until bands) {
                assertEquals(leftMono[i], left[i])
                assertEquals(rightMono[i], right[i])
            }
            assertTrue(stereo.currentTargets(0).contentEquals(leftOnly.currentTargets()))
            assertTrue(stereo.currentTargets(1).contentEquals(rightOnly.currentTargets()))
            if (playing && left.indices.any { left[it] != right[it] }) diverged = true
        }
        assertTrue(diverged)
        assertTrue(left.all { it == minHeightPx } && right.all { it == minHeightPx })
    }
}
