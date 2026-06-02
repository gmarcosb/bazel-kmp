// Copyright 2007 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.config.RunUnder.CommandRunUnder

/** Tests [RunUnderConverter].  */
@RunWith(JUnit4::class)
class RunUnderConverterTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConverter() {
        assertEqualsRunUnder("command", null, "command", com.google.common.collect.ImmutableList.of<String?>())
        assertEqualsRunUnder("command -c", null, "command", com.google.common.collect.ImmutableList.of<String?>("-c"))
        assertEqualsRunUnder(
            "command -c --out=all", null, "command",
            com.google.common.collect.ImmutableList.of<String?>("-c", "--out=all")
        )
        assertEqualsRunUnder("//run:under", "//run:under", null, com.google.common.collect.ImmutableList.of<String?>())
        assertEqualsRunUnder(
            "//run:under -c",
            "//run:under",
            null,
            com.google.common.collect.ImmutableList.of<String?>("-c")
        )
        assertEqualsRunUnder(
            "//run:under -c --out=all", "//run:under", null,
            com.google.common.collect.ImmutableList.of<String?>("-c", "--out=all")
        )

        assertRunUnderFails("", "Empty command")
    }

    @Throws(java.lang.Exception::class)
    private fun assertEqualsRunUnder(
        input: String?, label: String?, command: String?, options: com.google.common.collect.ImmutableList<String?>?
    ) {
        val runUnder: RunUnder? = RunUnderConverter().convert(input,  /*conversionContext=*/null)
        if (label == null) {
            assertThat(runUnder).isEqualTo(CommandRunUnder(input, options, command))
        } else {
            assertThat(runUnder)
                .isEqualTo(LabelRunUnder(input, options, Label.parseCanonicalUnchecked(label)))
        }
    }

    private fun assertRunUnderFails(input: String?, expectedError: String?) {
        val e: OptionsParsingException? =
            org.junit.Assert.assertThrows<T?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { RunUnderConverter().convert(input,  /*conversionContext=*/null) })
        assertThat(e).hasMessageThat().isEqualTo(expectedError)
    }
}
