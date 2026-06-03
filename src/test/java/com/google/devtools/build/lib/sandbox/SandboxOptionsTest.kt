// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.sandbox

import com.google.common.truth.Truth
import com.google.devtools.common.options.OptionsParsingException
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for `SandboxOptions`.  */
@RunWith(JUnit4::class)
class SandboxOptionsTest {
    private var pathPair: MutableMap.MutableEntry<String?, String?>? = null

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_singlePathWithoutColonSucess() {
        val source = "/a/bc/def/gh"
        val target = source
        val input = source
        pathPair = MountPairConverter().convert(input)
        Companion.assertMountPair(pathPair!!, source, target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_singlePathWithColonSucess() {
        val source = "/a/b:c/def/gh"
        val target = source
        val input = "/a/b\\:c/def/gh"
        pathPair = MountPairConverter().convert(input)
        Companion.assertMountPair(pathPair!!, source, target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_pathPairWithoutColonSucess() {
        val source = "/a/bc/def/gh"
        val target = "/1/2/3/4/5"
        val input = source + ":" + target
        pathPair = MountPairConverter().convert(input)
        Companion.assertMountPair(pathPair!!, source, target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_pathPairWithColonSucess() {
        val source = "/a:/bc:/d:ef/gh"
        val target = ":/1/2/3/4/5"
        val input = "/a\\:/bc\\:/d\\:ef/gh:\\:/1/2/3/4/5"
        pathPair = MountPairConverter().convert(input)
        Companion.assertMountPair(pathPair!!, source, target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_tooManyPaths() {
        val input = "a/bc/def/gh:/1/2/3:x/y/z"
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<OptionsParsingException?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { pathPair = MountPairConverter().convert(input) })
        Truth.assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "Input must be a single path to mount inside the sandbox or "
                        + "a mounting pair in the form of 'source:target'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParsingAdditionalMounts_emptyInput() {
        val input = ""
        val e: OptionsParsingException =
            org.junit.Assert.assertThrows<OptionsParsingException>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { pathPair = MountPairConverter().convert(input) })
        Truth.assertThat(
            ("Input "
                    + input
                    + " contains one or more empty paths. "
                    + "Input must be a single path to mount inside the sandbox or "
                    + "a mounting pair in the form of 'source:target'")
        )
            .isEqualTo(e.message)
    }

    companion object {
        private fun assertMountPair(
            pathPair: MutableMap.MutableEntry<String?, String?>, source: String?, target: String?
        ) {
            Truth.assertThat(source).isEqualTo(pathPair.key)
            Truth.assertThat(target).isEqualTo(pathPair.value)
        }
    }
}
