// Auto-generated by GenerateSteppedRangesCodegenTestData. Do not edit!
// KJS_WITH_FULL_RUNTIME
// WITH_RUNTIME
import kotlin.test.*

fun zero() = 0

fun box(): String {
    assertFailsWith<IllegalArgumentException> {
        val uintProgression = 7u downTo 1u
        for (i in uintProgression step zero()) {
        }
    }

    assertFailsWith<IllegalArgumentException> {
        val ulongProgression = 7uL downTo 1uL
        for (i in ulongProgression step zero().toLong()) {
        }
    }

    return "OK"
}