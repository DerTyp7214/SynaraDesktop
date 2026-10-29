package dev.dertyp.synara.screens

import dev.dertyp.synara.utils.InstallChannel
import dev.dertyp.synara.utils.installChannelOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VisibleChangelogTest {

    private val everywhere = Change(ChangeType.Fixed, "everywhere")
    private val pacmanOnly = Change(ChangeType.Improved, "pacman", channels = setOf(InstallChannel.PACMAN))
    private val desktopInstallers = Change(ChangeType.New, "installers", channels = setOf(InstallChannel.MSI, InstallChannel.DMG))

    private val mixed = ChangelogEntry(version = "2.0.0", date = "2026-10-29", changes = listOf(everywhere, pacmanOnly, desktopInstallers))
    private val pacmanLines = ChangelogEntry(version = "1.1.0", date = "2026-10-28", changes = listOf(pacmanOnly))
    private val debEntry = ChangelogEntry(version = "1.0.0", date = "2026-10-27", changes = listOf(everywhere), channels = setOf(InstallChannel.DEB))

    private val entries = listOf(mixed, pacmanLines, debEntry)

    @Test
    fun unknownChannelShowsEverything() {
        assertEquals(entries, visibleChangelog(entries, null))
    }

    @Test
    fun pacmanSeesItsLinesAndUnrestrictedOnes() {
        val visible = visibleChangelog(entries, InstallChannel.PACMAN)

        assertEquals(listOf("2.0.0", "1.1.0"), visible.map { it.version })
        assertEquals(listOf(everywhere, pacmanOnly), visible[0].changes)
        assertEquals(listOf(pacmanOnly), visible[1].changes)
    }

    @Test
    fun entriesWithoutVisibleLinesAreHidden() {
        val visible = visibleChangelog(entries, InstallChannel.MSI)

        assertEquals(listOf("2.0.0"), visible.map { it.version })
        assertEquals(listOf(everywhere, desktopInstallers), visible[0].changes)
    }

    @Test
    fun entryChannelsLimitTheWholeEntry() {
        val visible = visibleChangelog(entries, InstallChannel.DEB)

        assertEquals(listOf("2.0.0", "1.0.0"), visible.map { it.version })
        assertEquals(listOf(everywhere), visible[0].changes)
    }

    @Test
    fun channelNamesAreReadInLowercase() {
        assertEquals(InstallChannel.PACMAN, installChannelOf("pacman"))
        assertEquals(InstallChannel.TARBALL, installChannelOf("tarball"))
        assertNull(installChannelOf("PACMAN"))
        assertNull(installChannelOf("snap"))
        assertNull(installChannelOf(null))
    }
}
