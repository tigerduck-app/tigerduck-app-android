package org.ntust.app.tigerduck.network.backend

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * For the test it wraps, a base ending in `/v3` stays at `/v3`, as it did before the app moved to
 * `/v4` (T3.31): the rule of a test that pins what `/v3` sends, byte for byte, until Z1 deletes
 * those requests and their tests. It wraps the test's `@Before` and `@After` too, and puts back
 * what bases moved to before, however the test ends. `@get:Rule val api = SpeaksV3()`.
 */
class SpeaksV3 : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val before = ApiBase.moveBasesTo(BackendApi.V3)
            try {
                base.evaluate()
            } finally {
                ApiBase.moveBasesTo(before)
            }
        }
    }
}

/** [block] with every base ending in `/v3` kept at `/v3`, as [SpeaksV3] keeps it for a whole test. */
internal inline fun <T> speakingV3(block: () -> T): T {
    val before = ApiBase.moveBasesTo(BackendApi.V3)
    try {
        return block()
    } finally {
        ApiBase.moveBasesTo(before)
    }
}
