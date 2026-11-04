package org.ntust.app.tigerduck.data

import org.ntust.app.tigerduck.push.WriteAnswer

/** A write a test's backend took, answered with nothing more: no clock, no verdict, no values. */
internal val TOOK = WriteAnswer(sent = true, serverClockUs = null, applied = null, values = emptyMap())

/** [TOOK] when the write went, else [WriteAnswer.NOT_SENT]. */
internal fun tookIf(sent: Boolean): WriteAnswer = if (sent) TOOK else WriteAnswer.NOT_SENT
