package sample

/** A code sample for the text viewer: long lines wrap, tabs stay. */
fun fibonacci(n: Int): Long {
	var (a, b) = 0L to 1L
	repeat(n) { val next = a + b; a = b; b = next }
	return a
}

fun main() {
    println((0..10).joinToString(", ") { fibonacci(it).toString() } + " — and a line long enough to need wrapping on a phone screen.")
}
