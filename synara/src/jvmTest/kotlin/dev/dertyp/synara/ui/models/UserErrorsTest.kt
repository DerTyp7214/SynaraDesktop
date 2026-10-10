package dev.dertyp.synara.ui.models

import kotlinx.coroutines.channels.ClosedReceiveChannelException
import synara.synara.generated.resources.*
import java.io.IOException
import java.nio.channels.UnresolvedAddressException
import kotlin.test.Test
import kotlin.test.assertEquals

class UserErrorsTest {

    @Test
    fun anIoFailureMeansTheServerIsUnreachable() {
        assertEquals(Res.string.error_server_unreachable, IOException("down").userMessageResource())
    }

    @Test
    fun aClosedChannelMeansTheServerIsUnreachable() {
        assertEquals(Res.string.error_server_unreachable, ClosedReceiveChannelException("closed").userMessageResource())
    }

    @Test
    fun anUnresolvedAddressMeansTheServerIsUnreachable() {
        assertEquals(Res.string.error_server_unreachable, UnresolvedAddressException().userMessageResource())
    }

    @Test
    fun aCancelledRpcClientMeansTheServerIsUnreachable() {
        assertEquals(
            Res.string.error_server_unreachable,
            IllegalStateException("RpcClient was cancelled").userMessageResource(),
        )
    }

    @Test
    fun anIllegalStateMeansTheRequestFailed() {
        assertEquals(Res.string.error_request_failed, IllegalStateException("broken").userMessageResource())
    }

    @Test
    fun anIllegalStateWithoutMessageMeansTheRequestFailed() {
        assertEquals(Res.string.error_request_failed, IllegalStateException().userMessageResource())
    }

    @Test
    fun anyOtherFailureMeansTheRequestFailed() {
        assertEquals(Res.string.error_request_failed, RuntimeException("boom").userMessageResource())
        assertEquals(Res.string.error_request_failed, IllegalArgumentException("bad").userMessageResource())
    }
}
