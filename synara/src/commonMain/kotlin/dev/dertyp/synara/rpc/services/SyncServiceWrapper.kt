package dev.dertyp.synara.rpc.services

import dev.dertyp.services.ISyncService
import dev.dertyp.synara.rpc.RpcServiceManager

class SyncServiceWrapper(manager: RpcServiceManager) : BaseServiceWrapper(manager), ISyncService
