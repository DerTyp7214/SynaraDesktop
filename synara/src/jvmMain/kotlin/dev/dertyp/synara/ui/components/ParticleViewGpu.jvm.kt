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
import androidx.compose.runtime.snapshotFlow
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
import dev.dertyp.synara.player.PlayerSwitcher
import dev.dertyp.synara.ui.models.PerformanceMonitor
import dev.dertyp.synara.utils.OSUtils
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
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
import org.jetbrains.skia.Canvas as SkiaCanvas

private const val NUM_BUCKETS = 100
private const val HEX_CORNERS = 6
private const val HEX_VERTICES = HEX_CORNERS + 1
private const val HEX_INDICES = HEX_CORNERS * 3
private const val CHUNK_PARTICLES = 9_000
private const val PARTICLE_MARGIN = 100f
private const val OPAQUE_BLACK = 0xFF000000.toInt()

internal class ParticleSystem(val capacity: Int) {
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    val life = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    private val decay = FloatArray(capacity)
    var count = 0
        private set

    fun spawn(spawnCount: Int, originX: Float, originY: Float, offset: Int, velocity: Float, decayRate: Float) {
        var current = count
        repeat(spawnCount) {
            if (current < capacity) {
                val angle = Random.nextDouble(0.0, 2.0 * PI)
                val cosA = cos(angle).toFloat()
                val sinA = sin(angle).toFloat()

                x[current] = originX + cosA * offset * (1f - (.4f * Random.nextFloat()))
                y[current] = originY + sinA * offset * (1f - (.4f * Random.nextFloat()))
                vx[current] = cosA * velocity
                vy[current] = sinA * velocity
                decay[current] = decayRate
                life[current] = 1f
                current++
            }
        }
        count = current
    }

    fun update(normalizedDt: Float, width: Float, height: Float) {
        var current = count
        var i = 0
        while (i < current) {
            x[i] += vx[i] * normalizedDt
            y[i] += vy[i] * normalizedDt
            life[i] -= decay[i] * normalizedDt

            val isOutOfBounds = x[i] < -PARTICLE_MARGIN || x[i] > width + PARTICLE_MARGIN ||
                    y[i] < -PARTICLE_MARGIN || y[i] > height + PARTICLE_MARGIN

            val isDead = life[i] <= 0f || isOutOfBounds
            if (isDead) {
                val lastIdx = current - 1
                if (i != lastIdx) {
                    x[i] = x[lastIdx]
                    y[i] = y[lastIdx]
                    vx[i] = vx[lastIdx]
                    vy[i] = vy[lastIdx]
                    decay[i] = decay[lastIdx]
                    life[i] = life[lastIdx]
                }
                current--
            } else {
                i++
            }
        }
        count = current
    }
}

@Composable
actual fun ParticleViewGpu(
    modifier: Modifier,
    color: Color,
    highlightColor: Color,
    center: State<Offset>,
    emit: State<Boolean>,
    centerResolver: SizeResolver?,
    playerSwitcher: PlayerSwitcher,
    globalStateModel: GlobalStateModel,
    performanceMonitor: PerformanceMonitor,
) {
    val isPlaying by playerSwitcher.isPlaying.collectAsState()
    val isPlayerExpanded by globalStateModel.isPlayerExpanded.collectAsState()
    val audioIntensity by playerSwitcher.audioIntensity.collectAsState()
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

    val particles = remember(particleCap) { ParticleSystem(particleCap) }
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
        var lastInterval = 0L
        var smoothedIntensity = 0f
        var lastStatsTime = 0L
        var frameCount = 0

        while (true) {
            var resumed = false
            if (activeCount == 0 && !emitParticles && !isObserved) {
                snapshotFlow { activeCount == 0 && !emitParticles && !isObserved }.first { !it }
                resumed = true
            }

            withFrameNanos { time ->
                val interval = when {
                    frameTime == 0L -> 0L
                    resumed -> lastInterval
                    else -> time - frameTime
                }
                lastInterval = interval
                val dt = interval / 1E9f
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

                val emitting = emitParticles
                val target = if (emitting) audioIntensity else 0f
                val lerpFactor = (deltaMillis / 12.5f).coerceIn(0f, 1f)
                val alpha = if (target > smoothedIntensity) 1f - 0.15f.pow(lerpFactor) else 1f - 0.90f.pow(lerpFactor)
                smoothedIntensity += (target - smoothedIntensity) * alpha

                val normalizedDt = (dt * 60f).coerceIn(0f, 2f)

                val multiplier = particleMultiplier
                if (emitting && multiplier > 0) {
                    val intensity = smoothedIntensity
                    val baseSpeed = Random.nextInt(2, 6) * intensity
                    val speed = baseSpeed * speedMultiplier * .6f
                    val velocity = (speed * speed * speed / 4f).coerceAtLeast(1f)
                    val decayRate = max(0.001f * baseSpeed, 0.0005f)

                    val centerValue = center.value
                    val x = if (centerValue.isSpecified) centerValue.x else canvasSize.width / 2f
                    val y = if (centerValue.isSpecified) centerValue.y else canvasSize.height / 2f

                    val spawnCount = (baseSpeed.pow(multiplier) * intensity * 2).roundToInt()
                        .coerceAtMost(2000)

                    particles.spawn(spawnCount, x, y, centerOffsetPx, velocity, decayRate)
                }

                particles.update(normalizedDt, canvasSize.width, canvasSize.height)

                activeCount = particles.count
                tick = time
            }
        }
    }

    val hidden by remember { derivedStateOf { activeCount == 0 && !isPlayerExpanded } }
    if (hidden) return

    ParticleCanvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                if (size.width > 0 && size.height > 0) {
                    canvasSize.width = size.width.toFloat()
                    canvasSize.height = size.height.toFloat()
                }
            },
        color = color,
        highlightColor = highlightColor,
        particleX = particles.x,
        particleY = particles.y,
        particleLife = particles.life,
        count = { activeCount },
        tick = { tick }
    )
}

private class ParticleMesh {
    private val sizeClasses = IntArray(8) { 64 shl it } + CHUNK_PARTICLES
    private val positions = arrayOfNulls<FloatArray>(sizeClasses.size)
    private val indices = arrayOfNulls<ShortArray>(sizeClasses.size)
    private val colors = arrayOfNulls<IntArray>(sizeClasses.size)

    private var sizeClass = 0
    private var colored = false
    private var particleCount = 0

    val isFull: Boolean get() = particleCount == CHUNK_PARTICLES

    fun begin(remaining: Int, withColors: Boolean) {
        val needed = remaining.coerceAtMost(CHUNK_PARTICLES)
        var c = 0
        while (sizeClasses[c] < needed) c++
        sizeClass = c
        particleCount = 0
        if (positions[c] == null) {
            val size = sizeClasses[c]
            positions[c] = FloatArray(size * HEX_VERTICES * 2)
            indices[c] = ShortArray(size * HEX_INDICES).also { fanIndices(it, size) }
        }
        colored = withColors
        if (withColors && colors[c] == null) colors[c] = IntArray(sizeClasses[c] * HEX_VERTICES)
    }

    fun add(x: Float, y: Float, radius: Float, hexOffsets: FloatArray, color: Int) {
        val cols = colors[sizeClass]!!
        cols.fill(color, particleCount * HEX_VERTICES, (particleCount + 1) * HEX_VERTICES)
        add(x, y, radius, hexOffsets)
    }

    fun add(x: Float, y: Float, radius: Float, hexOffsets: FloatArray) {
        val pos = positions[sizeClass]!!
        var v = particleCount * HEX_VERTICES * 2
        pos[v++] = x
        pos[v++] = y
        for (j in 0 until HEX_CORNERS) {
            pos[v++] = x + hexOffsets[j * 2] * radius
            pos[v++] = y + hexOffsets[j * 2 + 1] * radius
        }
        particleCount++
    }

    fun draw(canvas: SkiaCanvas, paint: Paint) {
        if (particleCount == 0) return
        val pos = positions[sizeClass]!!
        val used = particleCount * HEX_VERTICES * 2
        if (used < pos.size) {
            val lastX = pos[used - 2]
            val lastY = pos[used - 1]
            var v = used
            while (v < pos.size) {
                pos[v++] = lastX
                pos[v++] = lastY
            }
        }
        if (colored) {
            val cols = colors[sizeClass]!!
            val usedVertices = particleCount * HEX_VERTICES
            cols.fill(cols[usedVertices - 1], usedVertices, cols.size)
            canvas.drawVertices(VertexMode.TRIANGLES, pos, cols, null, indices[sizeClass], BlendMode.DST, paint)
        } else {
            canvas.drawVertices(VertexMode.TRIANGLES, pos, null, null, indices[sizeClass], BlendMode.SRC_OVER, paint)
        }
        particleCount = 0
    }

    private fun fanIndices(target: ShortArray, size: Int) {
        var k = 0
        for (p in 0 until size) {
            val base = p * HEX_VERTICES
            for (j in 0 until HEX_CORNERS) {
                target[k++] = base.toShort()
                target[k++] = (base + 1 + j).toShort()
                target[k++] = (base + 1 + (j + 1) % HEX_CORNERS).toShort()
            }
        }
    }
}

@Composable
internal fun ParticleCanvas(
    modifier: Modifier,
    color: Color,
    highlightColor: Color,
    particleX: FloatArray,
    particleY: FloatArray,
    particleLife: FloatArray,
    count: () -> Int,
    tick: () -> Long,
    onSingleDraw: ((Boolean) -> Unit)? = null,
) {
    val density = LocalDensity.current.density
    val particleCap = particleX.size

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

    val bucketCounts = remember { IntArray(NUM_BUCKETS) }
    val bucketStarts = remember { IntArray(NUM_BUCKETS + 1) }
    val bucketOffsets = remember { IntArray(NUM_BUCKETS) }
    val sortedIndices = remember(particleCap) { IntArray(particleCap) }
    val bucketColors = remember { IntArray(NUM_BUCKETS) }
    val mesh = remember { ParticleMesh() }

    val hexOffsets = remember {
        FloatArray(12).apply {
            for (i in 0 until 6) {
                val angle = i * PI.toFloat() / 3f
                this[i * 2] = cos(angle)
                this[i * 2 + 1] = sin(angle)
            }
        }
    }

    Canvas(modifier = modifier) {
        @Suppress("unused")
        val redraw = tick()
        val activeCount = count()

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
                val bucket = (particleLife[i].coerceIn(0f, 0.999f) * NUM_BUCKETS).toInt()
                bucketCounts[bucket]++
            }

            bucketStarts[0] = 0
            for (b in 0 until NUM_BUCKETS) {
                bucketStarts[b + 1] = bucketStarts[b] + bucketCounts[b]
            }

            bucketOffsets.fill(0)
            for (i in 0 until activeCount) {
                val bucket = (particleLife[i].coerceIn(0f, 0.999f) * NUM_BUCKETS).toInt()
                val pos = bucketStarts[bucket] + bucketOffsets[bucket]
                sortedIndices[pos] = i
                bucketOffsets[bucket]++
            }

            var allOpaque = true
            for (b in 0 until NUM_BUCKETS) {
                if (bucketCounts[b] == 0) continue

                val midLife = (b + 0.5f) / NUM_BUCKETS

                val curTopA = topA * midLife
                val outA = curTopA + botA * (1f - curTopA)

                bucketColors[b] = if (outA > 0f) {
                    val invTopA = 1f - curTopA
                    val outR = (topR * curTopA + botR * botA * invTopA) / outA
                    val outG = (topG * curTopA + botG * botA * invTopA) / outA
                    val outB = (topB * curTopA + botB * botA * invTopA) / outA

                    val rInt = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val gInt = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val bInt = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)
                    val aInt = (outA * 255f + 0.5f).toInt().coerceIn(0, 255)
                    (aInt shl 24) or (rInt shl 16) or (gInt shl 8) or bInt
                } else {
                    0
                }
                if (bucketColors[b] ushr 24 != 255) allOpaque = false
            }

            onSingleDraw?.invoke(allOpaque)

            if (allOpaque) {
                paint.color = OPAQUE_BLACK
                var remaining = activeCount
                if (remaining > 0) mesh.begin(remaining, true)
                for (b in 0 until NUM_BUCKETS) {
                    val bucketCount = bucketCounts[b]
                    if (bucketCount == 0) continue

                    val start = bucketStarts[b]
                    val radius = pSize * ((b + 0.5f) / NUM_BUCKETS)
                    val bucketColor = bucketColors[b]

                    for (i in 0 until bucketCount) {
                        val pIdx = sortedIndices[start + i]
                        mesh.add(particleX[pIdx], particleY[pIdx], radius, hexOffsets, bucketColor)
                        remaining--
                        if (mesh.isFull) {
                            mesh.draw(skiaCanvas, paint)
                            if (remaining > 0) mesh.begin(remaining, true)
                        }
                    }
                }
                mesh.draw(skiaCanvas, paint)
            } else {
                for (b in 0 until NUM_BUCKETS) {
                    val bucketCount = bucketCounts[b]
                    if (bucketCount == 0) continue

                    val start = bucketStarts[b]
                    val radius = pSize * ((b + 0.5f) / NUM_BUCKETS)
                    paint.color = bucketColors[b]

                    mesh.begin(bucketCount, false)
                    for (i in 0 until bucketCount) {
                        val pIdx = sortedIndices[start + i]
                        mesh.add(particleX[pIdx], particleY[pIdx], radius, hexOffsets)
                        if (mesh.isFull) {
                            mesh.draw(skiaCanvas, paint)
                            mesh.begin(bucketCount - i - 1, false)
                        }
                    }
                    mesh.draw(skiaCanvas, paint)
                }
            }
        }
    }
}
