// Copyright 2024 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.unsafe.StringUnsafe

/**
 * Utility functions for reencoding strings between Bazel's internal raw byte encoding and regular
 * Java strings.
 * 
 * 
 * Bazel needs to support the following two setups:
 * 
 * 
 *  * Standard setup: file paths, command-line arguments, environment variables, BUILD and .bzl
 * files are all encoded in UTF-8, on Linux, macOS or Windows.
 *  * Legacy setup: file paths, command-line arguments, environment variables, BUILD and .bzl
 * files are all encoded in *some* consistent superset of ASCII, on Linux, with the
 * en_US.ISO-8859-1 locale available on the host. In particular, this setup allows any byte
 * sequence to appear in a file path and be referenced in a BUILD file.
 * 
 * 
 * 
 * Bazel achieves this by forcing an en_US.ISO-8859-1 locale on Unix when available, which due to
 * the byte-based nature of Unix APIs allows all Java (N)IO functions to treat strings as raw byte
 * sequences (a Latin-1 character is equivalent to an unconstrained byte value). On macOS, where the
 * JVM forces UTF-8 encoding for any kind of system interaction, as well as on Windows, where system
 * APIs are all restricted to valid Unicode strings, Bazel has to reencode strings to Unicode before
 * passing them to the JVM (and vice versa). Since BUILD and .bzl files are always read into Latin-1
 * strings (file encodings are not forced by the JVM) and are assumed to be encoded in UTF-8 (unless
 * the Latin-1 locale is available), Bazel has to reencode the strings to UTF-8 so that they match
 * up with the Starlark contents of these files (e.g. file paths mentioned in a BUILD file).
 * 
 * 
 * While allowing the user a great deal of flexibility, this requires great care when [ ]s are passed into or out of Bazel via Java standard library functions or external APIs.
 * The following three different types of strings need to be distinguished as if they were different
 * Java types:
 * 
 * 
 *  * Internal strings: All strings retained by Bazel and used in its inner layers are expected
 * to be raw byte sequences stored in Latin-1 [String]s. With Java's compact string
 * representation, this means that the Latin-1 bytes are stored directly in the internal byte
 * array [String.value] and the [String.coder] is [String.LATIN1].
 *  * Unicode strings: Regular Java strings, which are always Unicode. A common example is a
 * `string` field in a protobuf message.
 *  * Platform strings: Strings that are passed to or returned from Java (N)IO functions or as
 * command-line arguments or environment variables to the `java` binary at startup or
 * processes started via [java.lang.ProcessBuilder]. These strings are encoded and
 * decoded by the JVM according to its default native encoding, which is given by the `sun.jnu.encoding` system property. With the current JDK version (21), this is:
 * 
 *  * UTF-8 on macOS;
 *  * determined by the active code page on Windows (Cp1252 on US Windows, can be set to
 * UTF-8 by the user);
 *  * determined by the current locale on Linux (forced to en_US.ISO-8859-1 by the client
 * if available, otherwise usually UTF-8);
 *  * determined by the current locale on OpenBSD, which is always UTF-8.
 * 
 * As a result, there are two cases to consider:
 * 
 *  * On Linux with a Latin-1 locale, platform strings are identical to internal strings
 * and Java (N)IO functions can be used to operate with Unix API on a raw byte level.
 *  * In all other cases, platform strings are a subset of Unicode strings.
 * 
 * 
 * 
 * 
 * The static methods in this class efficiently reencode [String]s between these three
 * "types". Crucially, since ASCII strings are encoded identically in ISO-8859-1 and UTF-8, such
 * strings do not need to be reencoded.
 */
object StringEncoding {
    init {
        try {
            val compactStrings: java.lang.reflect.Field = String::class.java.getDeclaredField("COMPACT_STRINGS")
            compactStrings.setAccessible(true)
            com.google.common.base.Preconditions.checkState(
                compactStrings.get(null) as Boolean, "Bazel requires -XX:+CompactStrings"
            )
        } catch (e: java.lang.NoSuchFieldException) {
            throw java.lang.IllegalStateException(e)
        } catch (e: java.lang.IllegalAccessException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Transforms an internal string into a platform string as efficiently as possible.
     * 
     * 
     * See the class documentation for more information on the different types of strings.
     */
    @kotlin.jvm.JvmStatic
    fun internalToPlatform(s: String?): String? {
        return if (needsReencodeForPlatform(s)) String(
            StringUnsafe.getInternalStringBytes(s),
            java.nio.charset.StandardCharsets.UTF_8
        ) else
            s
    }

    /**
     * Transforms a platform string into an internal string as efficiently as possible.
     * 
     * 
     * See the class documentation for more information on the different types of strings.
     */
    @kotlin.jvm.JvmStatic
    fun platformToInternal(s: String): String? {
        return if (needsReencodeForPlatform(s))
            StringUnsafe.newInstance(s.toByteArray(java.nio.charset.StandardCharsets.UTF_8), StringUnsafe.LATIN1)
        else
            s
    }

    /**
     * Transforms an internal string into a Unicode string as efficiently as possible.
     * 
     * 
     * See the class documentation for more information on the different types of strings.
     */
    @kotlin.jvm.JvmStatic
    fun internalToUnicode(s: String?): String? {
        return if (needsReencodeForUnicode(s)) String(
            StringUnsafe.getInternalStringBytes(s),
            java.nio.charset.StandardCharsets.UTF_8
        ) else
            s
    }

    /**
     * Transforms a Unicode string into an internal string as efficiently as possible.
     * 
     * 
     * See the class documentation for more information on the different types of strings.
     */
    @kotlin.jvm.JvmStatic
    fun unicodeToInternal(s: String): String? {
        return if (needsReencodeForUnicode(s))
            StringUnsafe.newInstance(s.toByteArray(java.nio.charset.StandardCharsets.UTF_8), StringUnsafe.LATIN1)
        else
            s
    }

    /**
     * The [Charset] with which the JVM encodes any strings passed to or returned from Java
     * (N)IO functions, command-line arguments or environment variables.
     */
    private val SUN_JNU_ENCODING_IS_ISO_8859_1 =
        java.nio.charset.Charset.forName(java.lang.System.getProperty("sun.jnu.encoding")) == java.nio.charset.StandardCharsets.ISO_8859_1

    private fun needsReencodeForPlatform(s: String?): Boolean {
        if (SUN_JNU_ENCODING_IS_ISO_8859_1 && com.google.devtools.build.lib.util.OS.Companion.getCurrent() == com.google.devtools.build.lib.util.OS.LINUX) {
            // In this case, platform strings encode raw bytes and are thus identical to internal strings.
            return false
        }
        // Otherwise, platform strings are a subset of Unicode strings.
        return needsReencodeForUnicode(s)
    }

    private fun needsReencodeForUnicode(s: String?): Boolean {
        return !StringUnsafe.isAscii(s)
    }
}
