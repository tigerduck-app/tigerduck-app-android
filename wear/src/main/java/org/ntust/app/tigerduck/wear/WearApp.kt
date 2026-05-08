package org.ntust.app.tigerduck.wear

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import org.ntust.app.tigerduck.wear.data.ScheduleRepository
import org.ntust.app.tigerduck.wear.data.SchedulePersistence
import org.ntust.app.tigerduck.wear.data.SyncRequester
import org.ntust.app.tigerduck.wear.data.WatchSnapshot
import org.ntust.app.tigerduck.wear.ui.NowNextScreen
import org.ntust.app.tigerduck.wear.ui.theme.WearTheme
import org.ntust.app.tigerduck.wear.ui.theme.parseAccent

@Composable
fun WearApp() {
    val context = LocalContext.current
    val snapshot by ScheduleRepository.get(context).flow.collectAsState(
        initial = WatchSnapshot(emptyList(), SchedulePersistence.DEFAULT_ACCENT, null, false)
    )

    LaunchedEffect(Unit) {
        // Pull-on-open if cache is stale (>10 min).
        SyncRequester.maybeRequest(context, snapshot)
    }

    WearTheme(accent = parseAccent(snapshot.accentHex)) {
        NowNextScreen(snapshot)
    }
}
