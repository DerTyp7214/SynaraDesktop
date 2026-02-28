package dev.dertyp.synara.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import coil3.size.SizeResolver
import coil3.size.pxOrElse
import dev.dertyp.core.ifZeroNullable
import dev.dertyp.synara.Config
import dev.dertyp.synara.player.PlayerModel
import dev.dertyp.synara.viewmodels.GlobalStateModel
import kotlinx.coroutines.delay
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
    val particlePool = remember(particleCap) { ArrayList<Particle>(particleCap) }
    val particles = remember(particleCap) { ArrayList<Particle>(particleCap) }
    var tick by remember { mutableLongStateOf(0L) }

    val canvasSize = remember {
        object {
            var width = 0f
            var height = 0f
        }
    }

    LaunchedEffect(particleMultiplier, isPlayerExpanded) {
        if (isPlayerExpanded) delay(450)

        var frameTime = 0L
        val centerOffsetPx = centerResolver?.let {
            it.size().width
                .pxOrElse { 0 }
                .ifZeroNullable { null }
                ?.div(2)
                ?.minus(5 * density)
                ?.roundToInt()
        } ?: 0

        while (true) {
            withFrameNanos { time ->
                val dt = if (frameTime == 0L) 0f else (time - frameTime) / 1E9f
                val normalizedDt = (dt * 60f).coerceIn(0f, 2f)
                frameTime = time

                if (emitParticles && particleMultiplier > 0) {
                    val baseSpeed = (2..5).random() * audioIntensity
                    val speed = baseSpeed * density * .6f
                    
                    val x = if (center.value.isSpecified) center.value.x else canvasSize.width / 2f
                    val y = if (center.value.isSpecified) center.value.y else canvasSize.height / 2f
                    
                    val decayRate = max(0.001f * baseSpeed, 0.0005f)
                    val spawnCount = (baseSpeed.pow(particleMultiplier) * audioIntensity * 2).roundToInt()

                    repeat(spawnCount) {
                        val angle = Random.nextDouble(0.0, 2.0 * PI)
                        val cosA = cos(angle).toFloat()
                        val sinA = sin(angle).toFloat()

                        val velocity = (speed.pow(3) / 4).coerceAtLeast(1f)
                        val vx = cosA * velocity
                        val vy = sinA * velocity

                        val spawnX = x + cosA * centerOffsetPx * (1f - (.4f * Random.nextFloat()))
                        val spawnY = y + sinA * centerOffsetPx * (1f - (.4f * Random.nextFloat()))

                        val p = if (particlePool.isNotEmpty()) {
                            particlePool.removeAt(particlePool.lastIndex).also { p ->
                                p.x = spawnX; p.y = spawnY; p.vx = vx; p.vy = vy
                                p.decayRate = decayRate; p.life = 1f
                            }
                        } else {
                            Particle(spawnX, spawnY, vx, vy, decayRate, 1f)
                        }

                        if (particles.size < particleCap) particles.add(p)
                    }
                }

                val iterator = particles.iterator()
                while (iterator.hasNext()) {
                    val p = iterator.next()
                    p.x += p.vx * normalizedDt
                    p.y += p.vy * normalizedDt
                    p.life -= p.decayRate * normalizedDt

                    val margin = 100f
                    val isOutOfBounds = p.x < -margin || p.x > canvasSize.width + margin || 
                                       p.y < -margin || p.y > canvasSize.height + margin

                    if (p.life <= 0f || isOutOfBounds) {
                        iterator.remove()
                        if (particlePool.size < particleCap) particlePool.add(p)
                    }
                }

                tick = time
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
        val baseAlpha = color.alpha

        for (i in 0 until particles.size) {
            val p = particles.getOrNull(i) ?: continue
            val lifeAlpha = p.life.coerceIn(0f, 1f)
            val pColor = color.copy(alpha = lifeAlpha)
                .compositeOver(highlightColor)
                .copy(alpha = baseAlpha * lifeAlpha)

            drawCircle(
                color = pColor,
                radius = pSize * lifeAlpha,
                center = Offset(p.x, p.y)
            )
        }
    }
}

private data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var decayRate: Float = 0.01f,
    var life: Float = 1.0f
)
