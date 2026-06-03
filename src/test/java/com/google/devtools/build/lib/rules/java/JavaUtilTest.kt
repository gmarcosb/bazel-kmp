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
package com.google.devtools.build.lib.rules.java

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/** Tests for [JavaUtil] methods.  */
@RunWith(JUnit4::class)
class JavaUtilTest {
    @org.junit.Test
    fun testGetJavaPath() {
        assertThat(
            JavaUtil.getJavaPath(PathFragment.create("java/com/google/foo/FooModule.java"))
                .getPathString()
        )
            .isEqualTo("com/google/foo/FooModule.java")
        assertThat(JavaUtil.getJavaPath(PathFragment.create("org/foo/FooUtil.java"))).isNull()
    }

    @org.junit.Test
    fun testDetokenization() {
        val options: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "-source",
                "8",
                "-target",
                "8",
                "-Xmx1G",
                "--arg=val",
                "-XepExcludedPaths:.*/\\\\$$?\\\\$$?AutoValue(Gson)?_.*\\.java"
            )

        val detokenized: NestedSet<String?> = JavaHelper.detokenizeJavaOptions(options)
        val retokenized: com.google.common.collect.ImmutableList<String?>? = JavaHelper.tokenizeJavaOptions(detokenized)

        assertThat(detokenized.toList())
            .containsExactly(
                "-source 8 -target 8 -Xmx1G '--arg=val'"
                        + " '-XepExcludedPaths:.*/\\\\$$?\\\\$$?AutoValue(Gson)?_.*\\.java'"
            )
        Truth.assertThat(retokenized).isEqualTo(options)
    }
}
