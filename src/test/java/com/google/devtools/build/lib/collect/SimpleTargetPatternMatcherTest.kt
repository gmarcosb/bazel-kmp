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
package com.google.devtools.build.lib.collect

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [SimpleTargetPatternMatcher].  */
@RunWith(TestParameterInjector::class)
class SimpleTargetPatternMatcherTest {
    @org.junit.Test
    @TestParameters(valuesProvider = com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider::class)
    @Throws(java.lang.Exception::class)
    fun contains(included: Boolean, patterns: com.google.common.collect.ImmutableList<String?>?, label: Label?) {
        val matcher: SimpleTargetPatternMatcher = SimpleTargetPatternMatcher.create(patterns)
        Truth.assertWithMessage("matcher %s contains %s", matcher, label)
            .that(matcher.contains(label))
            .isEqualTo(included)
    }

    @org.junit.Test
    @TestParameters(valuesProvider = com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider::class)
    @Suppress("unused")
    @Throws(java.lang.Exception::class)
    fun toString(included: Boolean, patterns: com.google.common.collect.ImmutableList<String?>, label: Label?) {
        val matcher: SimpleTargetPatternMatcher = SimpleTargetPatternMatcher.create(patterns)
        val expected: String = String.format("[%s]", com.google.common.base.Joiner.on(",").join(patterns))
        assertThat(matcher.toString()).isEqualTo(expected)
    }

    internal class TargetPatternProvider :
        com.google.testing.junit.testparameterinjector.TestParametersValuesProvider() {
        override fun provideValues(context: com.google.testing.junit.testparameterinjector.TestParametersValuesProvider.Context?): com.google.common.collect.ImmutableList<TestParametersValues?> {
            return com.google.common.collect.ImmutableList.of<TestParametersValues?>( // Single pattern
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo:foo",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo:foo",
                    "//foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo",
                    "//foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo:foo",
                    "//foo:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo",
                    "//foo:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo/...",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    "//foo/...",
                    "//foo/bar:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo/...",
                    "//bar:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo/bar/...",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo",
                    "//fooooooo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "//foo/...",
                    "//fooooooo"
                ),  // Multiple patterns

                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//bar:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>("//foo:foo", "//bar:bar"),
                    "//quux:quux"
                ),  // Negative patterns

                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "-//foo:foo",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    "-//foo/...",
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>("//foo/...", "-//foo/bar/..."),
                    "//foo/bar:bar"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>("//foo/...", "-//foo/bar/..."),
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo/bar/baz"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    true,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo:foo"
                ),
                com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    false,
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//foo/...",
                        "-//foo/bar/...",
                        "//foo/bar/baz/..."
                    ),
                    "//foo/bar/quux"
                )
            )
        }

        companion object {
            private fun create(included: Boolean, pattern: String, label: String?): TestParametersValues {
                return com.google.devtools.build.lib.collect.SimpleTargetPatternMatcherTest.TargetPatternProvider.Companion.create(
                    included,
                    com.google.common.collect.ImmutableList.of<String?>(pattern),
                    label
                )
            }

            private fun create(
                included: Boolean, patterns: MutableList<String?>?, label: String?
            ): TestParametersValues {
                val name: String = String.format("%s-%s-%s", if (included) "included" else "excluded", patterns, label)
                return TestParametersValues.builder()
                    .name(name)
                    .addParameter("included", included)
                    .addParameter("patterns", patterns)
                    .addParameter("label", Label.parseCanonicalUnchecked(label))
                    .build()
            }
        }
    }
}
