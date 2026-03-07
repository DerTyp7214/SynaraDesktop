package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import coil3.size.SizeResolver
import coil3.size.pxOrElse
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
import org.jetbrains.skia.Paint
import org.koin.compose.koinInject
import kotlin.math.*
import kotlin.random.Random

@Composable
fun ParticleView(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
    highlightColor: Color = MaterialTheme.colorScheme.onSurface,
    center: State<Offset> = mutableStateOf(Offset.Unspecified),
    emit: State<Boolean> = mutableStateOf(true),
    centerResolver: SizeResolver? = null,
    playerModel: PlayerModel = koinInject(),
    globalStateModel: GlobalStateModel = koinInject()
) {
    val isPlaying by playerModel.isPlaying.collectAsState()
    val isPlayerExpanded by globalStateModel.isPlayerExpanded.collectAsState()
    val audioIntensity by playerModel.audioIntensity.collectAsState()

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
                isPlayerExpanded && value == 0 -> delay(32)
                else -> delay(250)
            }
        }
    }

    LaunchedEffect(particleCap) {
        var frameTime = 0L

        while (true) {
            withFrameNanos { time ->
                val dt = if (frameTime == 0L) 0f else (time - frameTime) / 1E9f
                val normalizedDt = (dt * 60f).coerceIn(0f, 2f)
                frameTime = time

                var currentCount = activeCount

                if (emitParticles && particleMultiplier > 0) {
                    val intensity = audioIntensity
                    val baseSpeed = (2..5).random() * intensity
                    val speed = baseSpeed * density * .6f
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

        val skiaCanvas = drawContext.canvas.nativeCanvas

        val paint = Paint().apply {
            isAntiAlias = false
        }

        for (i in 0 until activeCount) {
            val life = particleLife[i].coerceIn(0f, 1f)

            val curTopA = topA * life
            val outA = curTopA + botA * (1f - curTopA)

            val outR: Float
            val outG: Float
            val outB: Float
            if (outA > 0f) {
                val invTopA = 1f - curTopA
                outR = (topR * curTopA + botR * botA * invTopA) / outA
                outG = (topG * curTopA + botG * botA * invTopA) / outA
                outB = (topB * curTopA + botB * botA * invTopA) / outA
            } else {
                outR = 0f; outG = 0f; outB = 0f
            }

            val argb = ((outA * 255f + 0.5f).toInt() shl 24) or
                    ((outR * 255f + 0.5f).toInt() shl 16) or
                    ((outG * 255f + 0.5f).toInt() shl 8) or
                    (outB * 255f + 0.5f).toInt()

            paint.color = argb

            skiaCanvas.drawCircle(
                particleX[i],
                particleY[i],
                pSize * life,
                paint
            )
        }
    }
}
