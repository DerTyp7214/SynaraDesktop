package dev.dertyp.synara.sync

import com.russhwolf.settings.Settings
import dev.dertyp.synara.Config
import dev.dertyp.synara.settings.SettingKey
import dev.dertyp.synara.testing.ConfigFixture
import dev.dertyp.synara.testing.InMemorySettings
import dev.dertyp.synara.testing.startTestKoin
import dev.dertyp.synara.testing.stopTestKoin
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeviceIdentityTest {

    private class RecordingSettings(val delegate: Settings = InMemorySettings()) : Settings by delegate {
        val writes = mutableListOf<String>()

        override fun putString(key: String, value: String) {
            writes += key
            delegate.putString(key, value)
        }

        override fun remove(key: String) {
            writes += key
            delegate.remove(key)
        }
    }

    private val deviceIdKey = SettingKey.DeviceId.name

    @BeforeTest
    fun setUp() {
        startTestKoin()
    }

    @AfterTest
    fun tearDown() {
        ConfigFixture.resetToDefaults()
        stopTestKoin()
    }

    @Test
    fun storedIdIsReturnedWithoutAWrite() {
        val settings = RecordingSettings()
        settings.delegate.putString(deviceIdKey, "stored-id")

        assertEquals("stored-id", DeviceIdentity(settings).deviceId)
        assertTrue(settings.writes.isEmpty())
    }

    @Test
    fun missingIdIsGeneratedStoredAndStableAcrossInstances() {
        val settings = RecordingSettings()

        val generated = DeviceIdentity(settings).deviceId

        assertTrue(generated.isNotBlank())
        assertEquals(generated, settings.delegate.getStringOrNull(deviceIdKey))
        assertEquals(listOf(deviceIdKey), settings.writes)
        assertEquals(generated, DeviceIdentity(settings).deviceId)
        assertEquals(listOf(deviceIdKey), settings.writes)
    }

    @Test
    fun blankIdIsReplacedByAGeneratedOneThatStaysStable() {
        val settings = RecordingSettings()
        settings.delegate.putString(deviceIdKey, "   ")

        val generated = DeviceIdentity(settings).deviceId

        assertTrue(generated.isNotBlank())
        assertEquals(generated, settings.delegate.getStringOrNull(deviceIdKey))
        assertEquals(generated, DeviceIdentity(settings).deviceId)
    }

    @Test
    fun idIsTheSameOnEveryReadOfOneInstance() {
        val identity = DeviceIdentity(RecordingSettings())

        assertEquals(identity.deviceId, identity.deviceId)
    }

    @Test
    fun configuredDeviceNameWinsOverThePlatformName() {
        val identity = DeviceIdentity(InMemorySettings())
        val configured = identity.platformDeviceName + " configured"

        Config.setQueueSyncDeviceName(configured)

        assertEquals(configured, identity.deviceName())
    }

    @Test
    fun blankConfiguredDeviceNameFallsBackToThePlatformName() {
        val identity = DeviceIdentity(InMemorySettings())

        Config.setQueueSyncDeviceName("   ")
        assertEquals(identity.platformDeviceName, identity.deviceName())

        Config.setQueueSyncDeviceName("")
        assertEquals(identity.platformDeviceName, identity.deviceName())
    }
}
