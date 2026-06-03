// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.License.LicenseType

@RunWith(JUnit4::class)
class LicenseTest {
    @org.junit.Test
    fun testLeastRestrictive() {
        assertThat(License.leastRestrictive(java.util.Arrays.< T > asList < T ? > (LicenseType.RESTRICTED)))
            .isEqualTo(LicenseType.RESTRICTED)
        assertThat(
            License.leastRestrictive(
                java.util.Arrays.asList<T?>(LicenseType.RESTRICTED, LicenseType.BY_EXCEPTION_ONLY)
            )
        )
            .isEqualTo(LicenseType.RESTRICTED)
        assertThat(License.leastRestrictive(mutableListOf<LicenseType?>()))
            .isEqualTo(LicenseType.BY_EXCEPTION_ONLY)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun repr() {
        Truth.assertThat(Starlark.repr(License.NO_LICENSE, StarlarkSemantics.DEFAULT))
            .isEqualTo("[\"none\"]")
        assertThat(
            License.parseLicense(
                evalAsSequence(Starlark.repr(License.NO_LICENSE, StarlarkSemantics.DEFAULT))
            )
        )
            .isEqualTo(License.NO_LICENSE)

        val withoutExceptions: License? =
            License.parseLicense(com.google.common.collect.ImmutableList.of<E?>("notice", "restricted"))
        // License types sorted by LicenseType enum order.
        Truth.assertThat(Starlark.repr(withoutExceptions, StarlarkSemantics.DEFAULT))
            .isEqualTo("[\"restricted\", \"notice\"]")
        assertThat(
            License.parseLicense(
                evalAsSequence(Starlark.repr(withoutExceptions, StarlarkSemantics.DEFAULT))
            )
        )
            .isEqualTo(withoutExceptions)

        val withExceptions: License? =
            License.parseLicense(
                com.google.common.collect.ImmutableList.of<E?>(
                    "notice",
                    "restricted",
                    "exception=//foo:bar",
                    "exception=//baz:qux"
                )
            )
        // Exceptions sorted alphabetically.
        Truth.assertThat(Starlark.repr(withExceptions, StarlarkSemantics.DEFAULT))
            .isEqualTo(
                "[\"restricted\", \"notice\", \"exception=//baz:qux\", \"exception=//foo:bar\"]"
            )
        assertThat(
            License.parseLicense(
                evalAsSequence(Starlark.repr(withExceptions, StarlarkSemantics.DEFAULT))
            )
        )
            .isEqualTo(withExceptions)
    }

    companion object {
        /** Evaluates a string as a Starlark expression returning a sequence of strings.  */
        @Throws(java.lang.Exception::class)
        private fun evalAsSequence(string: String?): net.starlark.java.eval.Sequence<String?>? {
            val input: net.starlark.java.syntax.ParserInput? = net.starlark.java.syntax.ParserInput.fromLines(string)
            val mutability: Mutability = Mutability.create("test")
            val parsedValue: Any? =
                Starlark.execFile(
                    input,
                    net.starlark.java.syntax.FileOptions.DEFAULT,
                    net.starlark.java.eval.Module.create(),
                    StarlarkThread.createTransient(mutability, StarlarkSemantics.DEFAULT)
                )
            mutability.freeze()
            return net.starlark.java.eval.Sequence.cast<String?>(
                parsedValue,
                String::class.java,
                "evalAsSequence() input"
            )
        }
    }
}
