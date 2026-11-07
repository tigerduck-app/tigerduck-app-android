package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.sync.EditTime

/** A class-table action's moment ([CourseMoment]) at [ms], its order and its wall time alike, as with the clock never set back. */
internal fun moment(ms: Long): CourseMoment = CourseMoment(ms, EditTime(ms))
