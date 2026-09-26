package dev.dertyp.synara.ui.components.visualizer

import dev.dertyp.synara.ui.components.BOTH_CHANNELS
import dev.dertyp.synara.ui.components.ChannelBand
import dev.dertyp.synara.ui.components.LEFT_CHANNEL
import dev.dertyp.synara.ui.components.RIGHT_CHANNEL
import dev.dertyp.synara.ui.components.barChannelBand
import dev.dertyp.synara.ui.components.mirroredBandCount
import kotlin.test.Test
import kotlin.test.assertEquals

class BarChannelBandTest {
    private fun mapping(barCount: Int, circular: Boolean) = List(barCount) { barChannelBand(it, barCount, circular) }

    @Test
    fun oddStripSplitsAroundASharedCentreBar() {
        assertEquals(
            listOf(
                ChannelBand(LEFT_CHANNEL, 2),
                ChannelBand(LEFT_CHANNEL, 1),
                ChannelBand(BOTH_CHANNELS, 0),
                ChannelBand(RIGHT_CHANNEL, 1),
                ChannelBand(RIGHT_CHANNEL, 2)
            ),
            mapping(5, circular = false)
        )
        assertEquals(3, mirroredBandCount(5, circular = false))
    }

    @Test
    fun evenStripGivesEachHalfItsOwnBass() {
        assertEquals(
            listOf(
                ChannelBand(LEFT_CHANNEL, 1),
                ChannelBand(LEFT_CHANNEL, 0),
                ChannelBand(RIGHT_CHANNEL, 0),
                ChannelBand(RIGHT_CHANNEL, 1)
            ),
            mapping(4, circular = false)
        )
        assertEquals(2, mirroredBandCount(4, circular = false))
    }

    @Test
    fun ringPutsRightClockwiseAndSharesTopAndBottom() {
        assertEquals(
            listOf(
                ChannelBand(BOTH_CHANNELS, 0),
                ChannelBand(RIGHT_CHANNEL, 1),
                ChannelBand(RIGHT_CHANNEL, 2),
                ChannelBand(RIGHT_CHANNEL, 3),
                ChannelBand(BOTH_CHANNELS, 4),
                ChannelBand(LEFT_CHANNEL, 3),
                ChannelBand(LEFT_CHANNEL, 2),
                ChannelBand(LEFT_CHANNEL, 1)
            ),
            mapping(8, circular = true)
        )
        assertEquals(5, mirroredBandCount(8, circular = true))
    }

    @Test
    fun oddRingCoversEveryBandOnBothSides() {
        val bars = mapping(9, circular = true)
        assertEquals(ChannelBand(BOTH_CHANNELS, 0), bars[0])
        assertEquals(ChannelBand(BOTH_CHANNELS, 4), bars[4])
        assertEquals(ChannelBand(LEFT_CHANNEL, 4), bars[5])
        assertEquals((1..3).toList(), bars.filter { it.channel == RIGHT_CHANNEL }.map { it.band })
        assertEquals((1..4).toList(), bars.filter { it.channel == LEFT_CHANNEL }.map { it.band }.sorted())
        assertEquals(5, mirroredBandCount(9, circular = true))
    }
}
