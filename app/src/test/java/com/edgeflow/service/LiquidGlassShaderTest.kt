package com.edgeflow.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pure JVM regression tests for [LiquidGlassShader.AGSL_SOURCE].
 *
 * Guards against SkSL / AGSL syntax and runtime traps that break Android 13+ [android.graphics.RuntimeShader]
 * compilation or cause undefined Adreno/Mali GPU rendering:
 * - Forbidden C/GLSL built-ins not present in SkSL/AGSL (`hypot`, `vec2`, `vec3`, `vec4`, `texture2D`)
 * - Reversed `smoothstep(edge0, edge1, x)` bounds (`edge0 >= edge1`), which have undefined behavior in AGSL
 */
class LiquidGlassShaderTest {

    private val source: String = LiquidGlassShader.AGSL_SOURCE

    @Test
    fun agslSourceDoesNotUseHypot() {
        assertFalse(
            "AGSL/SkSL does not support hypot(); use length(float2(a, b)) instead.",
            Regex("""\bhypot\s*\(""").containsMatchIn(source)
        )
    }

    @Test
    fun agslSourceDoesNotUseGlslOnlyTypesOrSamplers() {
        val forbidden = listOf(
            "vec2" to Regex("""\bvec2\s*\("""),
            "vec3" to Regex("""\bvec3\s*\("""),
            "vec4" to Regex("""\bvec4\s*\("""),
            "texture2D" to Regex("""\btexture2D\s*\(""")
        )
        for ((token, pattern) in forbidden) {
            assertFalse(
                "AGSL/SkSL must not use GLSL-only symbol '$token('. Use float2/float3/float4 and uBackdrop.eval().",
                pattern.containsMatchIn(source)
            )
        }
    }

    @Test
    fun agslSourceHasNoReverseSmoothstepBounds() {
        // Guard known reversed symbolic forms directly
        val forbiddenPatterns = listOf(
            Regex("""smoothstep\s*\(\s*aa\s*,\s*-aa\b"""),
            Regex("""smoothstep\s*\(\s*max\s*\(\s*uShadowRange"""),
            Regex("""smoothstep\s*\(\s*uShadowRange\s*,\s*0(?:\.0)?\b""")
        )
        for (pattern in forbiddenPatterns) {
            assertFalse(
                "Found reversed symbolic smoothstep bounds matching ${pattern.pattern}; use 1.0 - smoothstep(low, high, x).",
                pattern.containsMatchIn(source)
            )
        }

        // Parse every smoothstep(arg0, arg1, arg2) call with balanced parentheses
        val calls = extractSmoothstepCalls(source)
        assertTrue(
            "Expected at least 4 smoothstep() calls in AGSL_SOURCE, found ${calls.size}.",
            calls.size >= 4
        )

        for (call in calls) {
            val edge0 = call.edge0.trim()
            val edge1 = call.edge1.trim()

            // 1. Numeric literal check: edge0 must be strictly less than edge1
            val num0 = edge0.toDoubleOrNull()
            val num1 = edge1.toDoubleOrNull()
            if (num0 != null && num1 != null) {
                if (num0 >= num1) {
                    fail(
                        "Reversed numeric smoothstep bounds: smoothstep($edge0, $edge1, ${call.value}). " +
                            "AGSL requires edge0 < edge1."
                    )
                }
            }

            // 2. Positive symbolic edge0 descending to zero literal edge1 (e.g. smoothstep(foo, 0.0, x))
            if (num0 == null && num1 != null && num1 <= 0.0 && !edge0.startsWith("-")) {
                fail(
                    "Potentially reversed smoothstep bounds: smoothstep($edge0, $edge1, ${call.value}). " +
                        "AGSL requires edge0 < edge1."
                )
            }

            // 3. Symmetric sign check: smoothstep(sym, -sym, x) is reversed
            if (edge1 == "-$edge0") {
                fail(
                    "Reversed symmetric smoothstep bounds: smoothstep($edge0, $edge1, ${call.value}). " +
                        "Use 1.0 - smoothstep(-$edge0, $edge0, x)."
                )
            }
        }
    }

    @Test
    fun agslSourceUsesWidenedSmoothLuminanceRamp() {
        assertTrue(
            "Expected widened smoothstep(0.32, 0.86, ...) ramp for smooth white/black glass transition.",
            source.contains("smoothstep(0.32, 0.86,")
        )
    }

    private data class SmoothstepCall(
        val edge0: String,
        val edge1: String,
        val value: String
    )

    private fun extractSmoothstepCalls(code: String): List<SmoothstepCall> {
        val result = mutableListOf<SmoothstepCall>()
        val tokenRegex = Regex("""\bsmoothstep\s*\(""")
        for (match in tokenRegex.findAll(code)) {
            val openParenIdx = match.range.last
            var depth = 1
            var i = openParenIdx + 1
            val args = mutableListOf<String>()
            var argStart = i

            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            args.add(code.substring(argStart, i))
                        }
                    }
                    ',' -> if (depth == 1) {
                        args.add(code.substring(argStart, i))
                        argStart = i + 1
                    }
                }
                i++
            }
            if (args.size == 3) {
                result.add(SmoothstepCall(args[0], args[1], args[2]))
            }
        }
        return result
    }
}
