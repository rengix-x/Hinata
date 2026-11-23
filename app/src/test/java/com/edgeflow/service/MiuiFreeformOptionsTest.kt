package com.edgeflow.service

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiuiFreeformOptionsTest {

    private val dummyContext: Context = object : ContextWrapper(null) {}

    @Test
    fun mapsHyperOs3FiveArgSignature() {
        val types = arrayOf<Class<*>>(
            Context::class.java,
            String::class.java,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!
        )
        val args = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = types,
            context = dummyContext,
            packageName = "com.example.target",
            anchorX = 540,
            anchorY = 1200
        )
        assertArrayEquals(
            arrayOf<Any?>(dummyContext, "com.example.target", true, 540, 1200),
            args
        )
    }

    @Test
    fun mapsSixArgSignatureWithSecondaryBooleanAndRect() {
        val types = arrayOf<Class<*>>(
            Context::class.java,
            String::class.java,
            Boolean::class.javaObjectType,
            Boolean::class.javaPrimitiveType!!,
            Int::class.javaObjectType,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Rect::class.java,
            String::class.java
        )
        val args = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = types,
            context = dummyContext,
            packageName = "com.example.chat",
            anchorX = 600,
            anchorY = 1100
        )
        assertArrayEquals(
            arrayOf<Any?>(dummyContext, "com.example.chat", true, false, 600, 1100, 0, null, null),
            args
        )
    }

    @Test
    fun mapsLegacyFourThreeAndTwoArgSignatures() {
        val args4 = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = arrayOf(
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!
            ),
            context = dummyContext,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertArrayEquals(arrayOf<Any?>(dummyContext, "com.example.app", true, false), args4)

        val args3 = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = arrayOf(
                Context::class.java,
                String::class.java,
                Boolean::class.javaPrimitiveType!!
            ),
            context = dummyContext,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertArrayEquals(arrayOf<Any?>(dummyContext, "com.example.app", true), args3)

        val args2 = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = arrayOf(Context::class.java, String::class.java),
            context = dummyContext,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertArrayEquals(arrayOf<Any?>(dummyContext, "com.example.app"), args2)
    }

    @Test
    fun skipsOverloadsWithUnknownParameterTypesOrMissingContext() {
        val unknownTypeArgs = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = arrayOf(
                Context::class.java,
                String::class.java,
                Float::class.javaPrimitiveType!!
            ),
            context = dummyContext,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertNull(unknownTypeArgs)

        val missingContextArgs = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = arrayOf(Context::class.java, String::class.java),
            context = null,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertNull(missingContextArgs)

        val emptyArgs = MiuiFreeformOptions.mapArgumentsForTypes(
            paramTypes = emptyArray(),
            context = dummyContext,
            packageName = "com.example.app",
            anchorX = 540,
            anchorY = 1000
        )
        assertNull(emptyArgs)
    }
}
