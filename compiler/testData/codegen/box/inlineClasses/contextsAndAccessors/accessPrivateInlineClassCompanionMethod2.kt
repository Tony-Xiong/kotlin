// !LANGUAGE: +InlineClasses
// IGNORE_BACKEND: JVM_IR

inline class R(private val r: Long) {
    fun test() = ok()

    companion object {
        private fun ok() = "OK"
    }
}

fun box() = R(0).test()