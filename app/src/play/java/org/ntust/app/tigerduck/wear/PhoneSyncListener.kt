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
import javax.inject.Inject

@AndroidEntryPoint
class PhoneSyncListener : WearableListenerService() {

    @Inject lateinit var bridge: WearScheduleBridge

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != SYNC_REQUEST_PATH) return
        Log.d(TAG, "sync request from watch: ${messageEvent.sourceNodeId}")
        scope.launch { bridge.publish() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val SYNC_REQUEST_PATH = "/tigerduck/sync_request"
        private const val TAG = "WearBridge"
    }
}
