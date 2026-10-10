package dev.dertyp.synara.player.audio.decode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class OggTailTest {
    private val frames = 2 * 48000 + 123
    private val fixture = OggOpusFixture.tone(frames, packetsPerPage = 8)

    @Test
    fun streamStartReadsTheSerialAndThePreSkipOfTheFirstPage() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))

        assertEquals(OggOpusFixture.PRE_SKIP, start.preSkip)
        assertEquals(start.sid, assertNotNull(OggTail.streamStart(fixture.bytes.copyOf(fixture.pageOffsets()[1]))).sid)
    }

    @Test
    fun streamStartIsUnknownWithoutAnOpusHead() {
        assertNull(OggTail.streamStart(ByteArray(0)))
        assertNull(OggTail.streamStart(ByteArray(40)))
        assertNull(OggTail.streamStart(fixture.bytes.copyOf(30)))
        assertNull(OggTail.streamStart(fixture.bytes.copyOfRange(fixture.audioStart, fixture.bytes.size)))
    }

    @Test
    fun lastGranulePositionComesFromTheCompletePageAtTheEndOfTheTail() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))

        assertEquals(OggOpusFixture.PRE_SKIP + frames.toLong(), OggTail.lastGranulePosition(fixture.bytes, start.sid))
    }

    @Test
    fun aTailStartingInsideAPageSkipsThePartialPage() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))
        val pages = fixture.pageOffsets()
        val tail = fixture.bytes.copyOfRange(pages[pages.size - 2] + 5, fixture.bytes.size)

        assertEquals(OggOpusFixture.PRE_SKIP + frames.toLong(), OggTail.lastGranulePosition(tail, start.sid))
    }

    @Test
    fun aTailWhoseLastPageIsIncompleteGivesNoGranule() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))

        assertNull(OggTail.lastGranulePosition(fixture.bytes.copyOf(fixture.bytes.size - 1), start.sid))
        assertNull(OggTail.lastGranulePosition(fixture.bytes + byteArrayOf(0), start.sid))
        assertNull(OggTail.lastGranulePosition(ByteArray(0), start.sid))
    }

    @Test
    fun theLastPageOfAnotherStreamIsIgnored() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))

        assertNull(OggTail.lastGranulePosition(fixture.bytes, start.sid + 1))
    }

    @Test
    fun durationIsTheLastGranuleWithoutThePreSkip() {
        val start = assertNotNull(OggTail.streamStart(fixture.bytes))

        assertEquals(frames * 1000L / 48000, OggTail.durationMs(fixture.bytes, start))
    }

    @Test
    fun durationIsUnknownForAGranuleBelowThePreSkip() {
        val broken = OggOpusFixture.tone(frames, packetsPerPage = 8) { OggOpusFixture.PRE_SKIP - 1L }
        val start = assertNotNull(OggTail.streamStart(broken.bytes))

        assertNull(OggTail.durationMs(broken.bytes, start))
    }
}
