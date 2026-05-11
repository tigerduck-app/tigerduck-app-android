package org.ntust.app.tigerduck.wear

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.ntust.app.tigerduck.shared.WearProtocol
import javax.inject.Inject

@AndroidEntryPoint
class PhoneSyncListener : WearableListenerService() {

    @Inject lateinit var bridge: WearScheduleBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WearProtocol.SyncRequest.PATH) return
        Log.d(TAG, "sync request from watch: ${messageEvent.sourceNodeId}")
        scope.launch { bridge.publish() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "WearBridge"
    }
}
