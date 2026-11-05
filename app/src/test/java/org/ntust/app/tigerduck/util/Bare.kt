package org.ntust.app.tigerduck.util

import sun.misc.Unsafe

/** An object with no constructor run, for a class of many collaborators a test never reaches. */
internal inline fun <reified T : Any> bare(): T {
    val unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as Unsafe
    return unsafe.allocateInstance(T::class.java) as T
}

/** Sets [field] of a [bare] object, for the few a test does reach. */
internal fun Any.set(field: String, value: Any?) {
    javaClass.getDeclaredField(field).apply { isAccessible = true }.set(this, value)
}
