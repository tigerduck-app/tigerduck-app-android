package org.ntust.app.tigerduck.data.model

data class SyncConflict(
    val localIgnored: Set<String>,
    val localCompleted: Set<String>,
    val serverIgnored: Set<String>,
    val serverCompleted: Set<String>,
)
