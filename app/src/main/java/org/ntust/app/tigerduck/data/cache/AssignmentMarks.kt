package org.ntust.app.tigerduck.data.cache

/**
 * This phone's assignment marks as [DataCache.updateAssignmentMarks] reads and writes them: the
 * ignored ids and the marked-done ones, each file a list of ids. Not stored as a class.
 */
data class AssignmentMarks(val ignored: Set<String>, val completed: Set<String>)
