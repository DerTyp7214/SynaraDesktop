package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import coil3.size.SizeResolver
import coil3.size.pxOrElse
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.utils.OSUtils
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.VertexMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

@Composable
actual fun ParticleViewGpu(
    modifier: Modifier,
    color: Color,
    highlightColor: Color,
    center: State<Offset>,
    emit: State<Boolean>,
    centerResolver: SizeResolver?,
    playerModel: PlayerModel,
    globalStateModel: GlobalStateModel,
    performanceMonitor: PerformanceMonitor,
) {
    val isPlaying by playerModel.isPlaying.collectAsState()
    val isPlayerExpanded by globalStateModel.isPlayerExpanded.collectAsState()
    val audioIntensity by playerModel.audioIntensity.collectAsState()
    val isObserved by performanceMonitor.isObserved.collectAsState()

    val emitParticles by remember(emit.value, isPlaying, isPlayerExpanded) {
        derivedStateOf {
            emit.value && isPlaying && isPlayerExpanded
        }
    }

    val particleMultiplier by Config.particleMultiplier.collectAsState()

    val particleCap by remember(particleMultiplier) {
        derivedStateOf {
            (2000 * (particleMultiplier.coerceAtLeast(0.1f)).pow(2)).roundToInt()
        }
    }
    val density = LocalDensity.current.density
    val speedMultiplier = density.let { d ->
        if (OSUtils.isMac) d / 2f else d
    }

    val particleX = remember(particleCap) { FloatArray(particleCap) }
    val particleY = remember(particleCap) { FloatArray(particleCap) }
    val particleVX = remember(particleCap) { FloatArray(particleCap) }
    val particleVY = remember(particleCap) { FloatArray(particleCap) }
    val particleDecay = remember(particleCap) { FloatArray(particleCap) }
    val particleLife = remember(particleCap) { FloatArray(particleCap) }
    var activeCount by remember(particleCap) { mutableIntStateOf(0) }

    var tick by remember { mutableLongStateOf(0L) }

    val canvasSize = remember {
        object {
            var width = 0f
            var height = 0f
        }
    }

    val centerOffsetPx by produceState(0, centerResolver, isPlayerExpanded, density) {
        if (centerResolver == null) {
            value = 0
            return@produceState
        }

        while (true) {
            val resolvedWidth = centerResolver.size().width.pxOrElse { 0 }
            val newValue = if (resolvedWidth > 0) (resolvedWidth / 2f - 5 * density).roundToInt() else 0

            if (value != newValue) value = newValue

            when {
                isPlayerExpanded && value == 0 -> delay(32.milliseconds)
                else -> delay(250.milliseconds)
            }
        }
    }

    LaunchedEffect(particleCap) {
        var frameTime = 0L
        var smoothedIntensity = 0f
        var lastStatsTime = 0L
        var frameCount = 0

        while (true) {
            withFrameNanos { time ->
                val dt = if (frameTime == 0L) 0f else (time - frameTime) / 1E9f
                val deltaMillis = dt * 1000f
                frameTime = time

                if (isObserved) {
                    frameCount++
                    if (time - lastStatsTime >= 1E9) {
                        performanceMonitor.updateParticleStats(activeCount, frameCount)
                        frameCount = 0
                        lastStatsTime = time
                    }
                }

                val target = if (emitParticles) audioIntensity else 0f
                val lerpFactor = (deltaMillis / 12.5f).coerceIn(0f, 1f)
                val alpha = if (target > smoothedIntensity) 1f - 0.15f.pow(lerpFactor) else 1f - 0.90f.pow(lerpFactor)
                smoothedIntensity += (target - smoothedIntensity) * alpha

                val normalizedDt = (dt * 60f).coerceIn(0f, 2f)
                var currentCount = activeCount

                if (emitParticles && particleMultiplier > 0) {
                    val intensity = smoothedIntensity
                    val baseSpeed = (2..5).random() * intensity
                    val speed = baseSpeed * speedMultiplier * .6f
                    val velocity = (speed * speed * speed / 4f).coerceAtLeast(1f)
                    val decayRate = max(0.001f * baseSpeed, 0.0005f)

                    val x = if (center.value.isSpecified) center.value.x else canvasSize.width / 2f
                    val y = if (center.value.isSpecified) center.value.y else canvasSize.height / 2f

                    val spawnCount = (baseSpeed.pow(particleMultiplier) * intensity * 2).roundToInt()
                        .coerceAtMost(2000)

                    repeat(spawnCount) {
                        if (currentCount < particleCap) {
                            val angle = Random.nextDouble(0.0, 2.0 * PI)
                            val cosA = cos(angle).toFloat()
                            val sinA = sin(angle).toFloat()

                            particleX[currentCount] = x + cosA * centerOffsetPx * (1f - (.4f * Random.nextFloat()))
                            particleY[currentCount] = y + sinA * centerOffsetPx * (1f - (.4f * Random.nextFloat()))
                            particleVX[currentCount] = cosA * velocity
                            particleVY[currentCount] = sinA * velocity
                            particleDecay[currentCount] = decayRate
                            particleLife[currentCount] = 1f
                            currentCount++
                        }
                    }
                }

                var i = 0
                while (i < currentCount) {
                    particleX[i] += particleVX[i] * normalizedDt
                    particleY[i] += particleVY[i] * normalizedDt
                    particleLife[i] -= particleDecay[i] * normalizedDt

                    val margin = 100f
                    val isOutOfBounds = particleX[i] < -margin || particleX[i] > canvasSize.width + margin ||
                            particleY[i] < -margin || particleY[i] > canvasSize.height + margin

                    val isDead = particleLife[i] <= 0f || isOutOfBounds
                    if (isDead) {
                        val lastIdx = currentCount - 1
                        if (i != lastIdx) {
                            particleX[i] = particleX[lastIdx]
                            particleY[i] = particleY[lastIdx]
                            particleVX[i] = particleVX[lastIdx]
                            particleVY[i] = particleVY[lastIdx]
                            particleDecay[i] = particleDecay[lastIdx]
                            particleLife[i] = particleLife[lastIdx]
                        }
                        currentCount--
                    } else {
                        i++
                    }
                }

                activeCount = currentCount
                tick = time
            }
        }
    }

    if (activeCount == 0 && !isPlayerExpanded) return

    val paint = remember {
        Paint().apply {
            isAntiAlias = false
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            paint.close()
        }
    }

    val numBuckets = 100
    val bucketCounts = remember { IntArray(numBuckets) }
    val bucketStarts = remember { IntArray(numBuckets + 1) }
    val sortedIndices = remember(particleCap) { IntArray(particleCap) }
    val bucketPositions = remember(particleCap) { FloatArray(particleCap * 36) }

    val hexOffsets = remember {
        FloatArray(12).apply {
            for (i in 0 until 6) {
                val angle = i * PI.toFloat() / 3f
                this[i * 2] = cos(angle)
                this[i * 2 + 1] = sin(angle)
            }
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    canvasSize.width = size.width.toFloat()
                    canvasSize.height = size.height.toFloat()
                }
            }
    ) {
        @Suppress("unused")
        val redraw = tick

        val pSize = 2f * density

        val topR = color.red
        val topG = color.green
        val topB = color.blue
        val topA = color.alpha

        val botR = highlightColor.red
        val botG = highlightColor.green
        val botB = highlightColor.blue
        val botA = highlightColor.alpha

        drawIntoCanvas { canvas ->
            val skiaCanvas = canvas.skiaCanvas

            bucketCounts.fill(0)

            for (i in 0 until activeCount) {
                val bucket = (particleLife[i].coerceIn(0f, 0.999f) * numBuckets).toInt()
                bucketCounts[bucket]++
            }

            bucketStarts[0] = 0
            for (b in 0 until numBuckets) {
                bucketStarts[b + 1] = bucketStarts[b] + bucketCounts[b]
            }

            val currentOffsets = bucketCounts.copyOf()
            currentOffsets.fill(0)
            for (i in 0 until activeCount) {
                val bucket = (particleLife[i].coerceIn(0f, 0.999f) * numBuckets).toInt()
                val pos = bucketStarts[bucket] + currentOffsets[bucket]
                sortedIndices[pos] = i
                currentOffsets[bucket]++
            }

            for (b in 0 until numBuckets) {
                val count = bucketCounts[b]
                if (count == 0) continue

                val start = bucketStarts[b]
                val midLife = (b + 0.5f) / numBuckets

                val curTopA = topA * midLife
                val outA = curTopA + botA * (1f - curTopA)
                
                if (outA > 0f) {
                    val invTopA = 1f - curTopA
                    val outR = (topR * curTopA + botR * botA * invTopA) / outA
                    val outG = (topG * curTopA + botG * botA * invTopA) / outA
                    val outB = (topB * curTopA + botB * botA * invTopA) / outA
                    
                    val rInt = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gInt = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bInt = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val aInt = (outA * 255f + 0.5f).toInt().coerceIn(0, 255)
                    paint.color = (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                } else {
                    paint.color = 0
                }

                val radius = pSize * midLife
                var vIdx = 0

                for (i in 0 until count) {
                    val pIdx = sortedIndices[start + i]
                    val x = particleX[pIdx]
                    val y = particleY[pIdx]

                    for (j in 0 until 6) {
                        val nextJ = (j + 1) % 6

                        bucketPositions[vIdx++] = x
                        bucketPositions[vIdx++] = y

                        bucketPositions[vIdx++] = x + hexOffsets[j * 2] * radius
                        bucketPositions[vIdx++] = y + hexOffsets[j * 2 + 1] * radius

                        bucketPositions[vIdx++] = x + hexOffsets[nextJ * 2] * radius
                        bucketPositions[vIdx++] = y + hexOffsets[nextJ * 2 + 1] * radius
                    }
                }

                skiaCanvas.drawVertices(
                    VertexMode.TRIANGLES,
                    bucketPositions.copyOfRange(0, vIdx),
                    null,
                    null,
                    null,
                    BlendMode.SRC_OVER,
                    paint
                )
            }
        }
    }
}
