// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import com.google.devtools.build.lib.util.StringUtilities
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** A test for [StringUtilities].  */
@RunWith(JUnit4::class)
class StringUtilitiesTest {
    @org.junit.Test
    fun emptyLinesYieldsEmptyString() {
        Truth.assertThat(StringUtilities.joinLines()).isEmpty()
    }

    @org.junit.Test
    fun twoLinesGetjoinedNicely() {
        Truth.assertThat(StringUtilities.joinLines("line 1", "line 2")).isEqualTo("line 1\nline 2")
    }

    @org.junit.Test
    fun aTrailingNewlineIsAvailableWhenYouNeedIt() {
        Truth.assertThat(StringUtilities.joinLines("two lines", "with trailing newline", ""))
            .isEqualTo("two lines\nwith trailing newline\n")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun replaceAllLiteral() {
        Truth.assertThat(StringUtilities.replaceAllLiteral("bababa", "ba", "ab")).isEqualTo("ababab")
        Truth.assertThat(StringUtilities.replaceAllLiteral("bababa", "ba", "")).isEmpty()
        Truth.assertThat(StringUtilities.replaceAllLiteral("bababa", "", "ab")).isEqualTo("bababa")
    }

    @org.junit.Test
    fun testPrettyPrintBytes() {
        val expected = arrayOf<String?>(
            "2B",
            "23B",
            "234B",
            "2345B",
            "23KB",
            "234KB",
            "2345KB",
            "23MB",
            "234MB",
            "2345MB",
            "23456MB",
            "234GB",
            "2345GB",
            "23456GB",
        )
        var x = 2.3456
        for (ii in expected.indices) {
            Truth.assertThat(StringUtilities.prettyPrintBytes(x.toLong())).isEqualTo(expected[ii])
            x = x * 10.0
        }
    }

    @org.junit.Test
    fun testBytesCountToDisplayString() {
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(1000)).isEqualTo("1000 B")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString((1 shl 10).toLong())).isEqualTo("1.0 KiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(((1 shl 10) + (1 shl 10) / 10).toLong()))
            .isEqualTo("1.1 KiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString((1 shl 20).toLong())).isEqualTo("1.0 MiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(((1 shl 20) + (1 shl 20) / 10).toLong()))
            .isEqualTo("1.1 MiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString((1 shl 30).toLong())).isEqualTo("1.0 GiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(((1 shl 30) + (1 shl 30) / 10).toLong()))
            .isEqualTo("1.1 GiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(1L shl 40)).isEqualTo("1.0 TiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString((1L shl 40) + (1L shl 40) / 10)).isEqualTo("1.1 TiB")
        Truth.assertThat(StringUtilities.bytesCountToDisplayString(1L shl 50)).isEqualTo("1024.0 TiB")
    }

    @org.junit.Test
    fun sanitizeControlChars() {
        Truth.assertThat(StringUtilities.sanitizeControlChars("\u0000")).isEqualTo("<?>")
        Truth.assertThat(StringUtilities.sanitizeControlChars("\u0001")).isEqualTo("<?>")
        Truth.assertThat(StringUtilities.sanitizeControlChars("\r")).isEqualTo("\\r")
        Truth.assertThat(StringUtilities.sanitizeControlChars(" abc123")).isEqualTo(" abc123")
    }
}
