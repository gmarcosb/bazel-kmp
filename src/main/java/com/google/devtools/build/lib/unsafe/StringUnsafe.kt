// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.unsafe

/**
 * Provides direct access to the string implementation used by JDK9.
 * 
 * 
 * As of JDK9, a string is two fields: `byte coder`, and `byte[] value`.
 * The `coder` field has value 0 if the encoding is LATIN-1, and 2 if the encoding is
 * UTF-16 (the classic JDK8 encoding).
 * 
 * 
 * The `value` field contains the actual bytes.
 */
object StringUnsafe {
    // Fields corresponding to the coder
    const val LATIN1: Byte = 0
    const val UTF16: Byte = 1

    private val CONSTRUCTOR: java.lang.invoke.MethodHandle
    private val HAS_NEGATIVES: java.lang.invoke.MethodHandle
    private val VALUE_HANDLE: java.lang.invoke.VarHandle
    private val CODE_HANDLE: java.lang.invoke.VarHandle

    init {
        try {
            val stringCoding: java.lang.Class<*> = java.lang.Class.forName("java.lang.StringCoding")
            val hasNegatives: java.lang.reflect.Method =
                stringCoding.getDeclaredMethod(
                    "hasNegatives",
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType
                )
            hasNegatives.setAccessible(true)
            HAS_NEGATIVES = java.lang.invoke.MethodHandles.lookup().unreflect(hasNegatives)

            val constructor: java.lang.reflect.Constructor<String?> =
                String::class.java.getDeclaredConstructor(ByteArray::class.java, Byte::class.javaPrimitiveType)
            constructor.setAccessible(true)
            CONSTRUCTOR = java.lang.invoke.MethodHandles.lookup().unreflectConstructor(constructor)

            val stringLookup: java.lang.invoke.MethodHandles.Lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                String::class.java, java.lang.invoke.MethodHandles.lookup()
            )
            VALUE_HANDLE = stringLookup.unreflectVarHandle(String::class.java.getDeclaredField("value"))
            CODE_HANDLE = stringLookup.unreflectVarHandle(String::class.java.getDeclaredField("coder"))
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /** Returns the coder used for this string. See [.LATIN1] and [.UTF16].  */
    @kotlin.jvm.JvmStatic
    fun getCoder(obj: String?): Byte {
        return CODE_HANDLE.get(obj) as Byte
    }

    /**
     * Returns the internal byte array, encoded according to [.getCoder].
     * 
     * 
     * Use of this is unsafe. The representation may change from one JDK version to the next.
     * Ensure you do not mutate this byte array in any way.
     */
    @kotlin.jvm.JvmStatic
    fun getByteArray(obj: String?): ByteArray {
        return VALUE_HANDLE.get(obj) as ByteArray
    }

    /**
     * Return the internal byte array of a String using Bazel's internal encoding (see [ ]).
     * 
     * 
     * Callers must not mutate the returned byte array.
     */
    @kotlin.jvm.JvmStatic
    fun getInternalStringBytes(obj: String): ByteArray {
        // This is both a performance optimization and a correctness check: internal strings must
        // always be coded in Latin-1, otherwise they have been constructed out of a non-ASCII string
        // that hasn't been converted to internal encoding.
        if (getCoder(obj) != LATIN1) {
            // Truncation is ASCII only and thus doesn't change the encoding.
            val truncatedString: String = com.google.common.base.Ascii.truncate(obj, 1000, "...")
            throw java.lang.IllegalArgumentException(
                java.lang.String.format(
                    "Expected internal string with Latin-1 coder, got: %s (%s)",
                    truncatedString, java.util.Arrays.toString(getByteArray(truncatedString))
                )
            )
        }
        return getByteArray(obj)
    }

    /** Returns whether the string is ASCII-only.  */
    @kotlin.jvm.JvmStatic
    fun isAscii(obj: String?): Boolean {
        // This implementation uses java.lang.StringCoding#hasNegatives, which is implemented as a JVM
        // intrinsic. On a machine with 512-bit SIMD registers, this is 5x as fast as a naive loop
        // over getByteArray(obj), which in turn is 5x as fast as obj.chars().anyMatch(c -> c > 0x7F) in
        // a JMH benchmark.

        if (getCoder(obj) != LATIN1) {
            // Latin-1 is a superset of ASCII, so we must have non-ASCII characters.
            return false
        }
        val bytes = getByteArray(obj)
        try {
            return !HAS_NEGATIVES.invokeExact(bytes, 0, bytes.size) as Boolean
        } catch (t: Throwable) {
            // hasNegatives doesn't throw.
            throw java.lang.IllegalStateException(t)
        }
    }

    /**
     * Constructs a new string from a byte array and coder.
     * 
     * 
     * The new string shares the byte array instance, which must not be modified after calling this
     * method.
     */
    @kotlin.jvm.JvmStatic
    fun newInstance(bytes: ByteArray, coder: Byte): String? {
        try {
            return CONSTRUCTOR.invokeExact(bytes, coder) as String?
        } catch (e: Throwable) {
            // The constructor never throws, so this is not expected.
            throw java.lang.IllegalStateException(
                "Could not instantiate string: " + java.util.Arrays.toString(bytes) + ", " + coder, e
            )
        }
    }
}
