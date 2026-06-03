// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util.regex

import com.google.common.truth.Truth
import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.util.regex.RegexUtil.asOptimizedMatchingPredicate
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import net.starlark.java.syntax.FileOptions.Builder.build
import org.junit.runner.RunWith

/** Tests for [RegexUtil].  */
@RunWith(TestParameterInjector::class)
class RegexUtilTest {
    @org.junit.Test
    fun optimizedMatchingPredicate(
        @TestParameter(
            "",
            ".",
            "a",
            "foo",
            "foofoo",
            "coverage.dat",
            "/coverage.dat",
            "/coverage.data",
            "/coverage1dat",
            "/coverage1data",
            "foo/coverage.dat",
            "foo/coverage.data",
            "foo/coverage1dat",
            "foo/coverage1data",
            "foo/test/a/coverage.dat",
            "foo/test/.*/coverage.dat",
            "]]\n",
            "()",
            "+",
            "|"
        ) haystack: String?,
        @TestParameter(
            ".*",
            ".*?foo",
            ".*+foo",
            "^foo$",
            "coverage\\.dat",
            ".*/coverage.dat",
            ".*/coverage\\.dat",
            ".*/test/.*/coverage\\.dat",
            "$|",
            "^",
            ".]",
            ".*]",
            ".*^?^\\Q",
            "foo|/coverage.dat",
            ".*^|.*a",
            "\\Q.",
            ".*.",
            ".*\\\\",
            ".*()",
            ".*|",
            ".*^",
            ".*+"
        ) needle: String?
    ) {
        val originalPattern: java.util.regex.Pattern =
            java.util.regex.Pattern.compile(needle, java.util.regex.Pattern.DOTALL)
        val optimizedMatcher: java.util.function.Predicate<String?> =
            com.google.devtools.build.lib.util.regex.RegexUtil.asOptimizedMatchingPredicate(originalPattern)
        Truth.assertThat(optimizedMatcher.test(haystack))
            .isEqualTo(originalPattern.matcher(haystack).matches())
    }
}
