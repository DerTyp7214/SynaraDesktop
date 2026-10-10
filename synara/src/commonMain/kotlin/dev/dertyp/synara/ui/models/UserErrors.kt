package dev.dertyp.synara.ui.models

import dev.dertyp.rpc.isTransportFailure
import org.jetbrains.compose.resources.StringResource
import synara.synara.generated.resources.*

fun Throwable.userMessageResource(): StringResource =
    if (isTransportFailure()) Res.string.error_server_unreachable else Res.string.error_request_failed
