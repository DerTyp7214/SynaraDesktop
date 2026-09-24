package dev.dertyp.synara.ui.components.visualizer

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

    @Test
    fun reactionsDeclareTheirLayout() {
        assertTrue(SynaraReaction().mirrored)
        assertTrue(MonstercatReaction().mirrored)
    }

    @Test
    fun monstercatFillsHalfCountBandsWithBassAboveTreble() {
        val halfCount = 20
        val reaction = MonstercatReaction()
        val heights = FloatArray(halfCount) { minHeightPx }
        repeat(200) { reaction.update(pinkFft(), true, heights, halfCount, heightPx, minHeightPx, 16L) }
        val targets = reaction.currentTargets()
        assertEquals(halfCount, targets.size)
        assertTrue(heights.all { it > minHeightPx })
        assertTrue(heights.first() > heights.last())
    }

    @Test
    fun monstercatRisesQuicklyWithNoiseReduction() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(200) { reaction.update(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        heights.fill(minHeightPx)
        reaction.update(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L)
        val targets = reaction.currentTargets()
        val peak = targets.indices.maxBy { targets[it] }
        val expected = minHeightPx + (targets[peak] - minHeightPx) * 0.7f
        assertTrue(abs(heights[peak] - expected) < 0.01f)
        repeat(3) { reaction.update(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertTrue(heights[peak] > targets[peak] * 0.95f)
    }

    @Test
    fun monstercatFallsWithoutHold() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(8) { minHeightPx }
        repeat(200) { reaction.update(pinkFft(), true, heights, 8, heightPx, minHeightPx, 16L) }
        val peak = heights.copyOf()
        reaction.update(pinkFft(), false, heights, 8, heightPx, minHeightPx, 16L)
        assertTrue(heights.indices.all { heights[it] < peak[it] || peak[it] == minHeightPx })
    }

    @Test
    fun monstercatGravityReachesMinimumHeight() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(8) { minHeightPx }
        repeat(200) { reaction.update(pinkFft(), true, heights, 8, heightPx, minHeightPx, 16L) }
        val drops = mutableListOf<Float>()
        var previous = heights[0]
        repeat(200) {
            reaction.update(pinkFft(), false, heights, 8, heightPx, minHeightPx, 16L)
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
        reaction.update(loudFft(), true, heights, 8, heightPx, minHeightPx, 16L)
        assertTrue(heights.all { it > minHeightPx })
        repeat(500) { reaction.update(loudFft(), false, heights, 8, heightPx, minHeightPx, 16L) }
        assertTrue(heights.all { abs(it - minHeightPx) < 0.01f })
    }

    @Test
    fun monstercatPinkSpectrumKeepsContrastBelowFullHeight() {
        val barCount = 60
        val reaction = MonstercatReaction()
        val heights = FloatArray(barCount) { minHeightPx }
        repeat(1000) { reaction.update(pinkFft(), true, heights, barCount, heightPx, minHeightPx, 16L) }
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
        repeat(500) { reaction.update(FloatArray(512), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertEquals(1f, reaction.sensitivity)
    }

    @Test
    fun monstercatSilenceAfterMusicKeepsSensitivity() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(300) { reaction.update(pinkFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        val settled = reaction.sensitivity
        repeat(500) { reaction.update(FloatArray(512), true, heights, 16, heightPx, minHeightPx, 16L) }
        assertEquals(settled, reaction.sensitivity)
    }

    @Test
    fun monstercatSensitivityRecoversAfterLoudPassage() {
        val reaction = MonstercatReaction()
        val heights = FloatArray(16) { minHeightPx }
        repeat(300) { reaction.update(loudFft(), true, heights, 16, heightPx, minHeightPx, 16L) }
        val afterLoud = reaction.sensitivity
        val quiet = FloatArray(512) { 0.005f }
        repeat(300) { reaction.update(quiet, true, heights, 16, heightPx, minHeightPx, 16L) }
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
            reaction.update(kickFrame(t, random), true, heights, bands, heightPx, minHeightPx, 16L)
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
}
