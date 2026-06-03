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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.pkgcache.LoadingOptions

/** Tests for [TargetPatternPhaseKey].  */
@RunWith(JUnit4::class)
class TargetPatternPhaseKeyTest {
    internal enum class Flag {
        COMPILE_ONE_DEPENDENCY,
        BUILD_TESTS_ONLY,
        DETERMINE_TESTS
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEquality() {
        EqualsTester()
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<String?>("a"),
                    PathFragment.create("offset")
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<String?>("b"),
                    PathFragment.create("offset")
                )
            )
            .addEqualityGroup(of(com.google.common.collect.ImmutableList.of<String?>("b"), PathFragment.EMPTY_FRAGMENT))
            .addEqualityGroup(of(com.google.common.collect.ImmutableList.of<String?>("c"), PathFragment.EMPTY_FRAGMENT))
            .addEqualityGroup(of(com.google.common.collect.ImmutableList.of<String?>(), PathFragment.EMPTY_FRAGMENT))
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    false,
                    true,
                    null,
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.COMPILE_ONE_DEPENDENCY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    false,
                    false,
                    null,
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.COMPILE_ONE_DEPENDENCY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    true,
                    true,
                    null,
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.COMPILE_ONE_DEPENDENCY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    true,
                    false,
                    null,
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.COMPILE_ONE_DEPENDENCY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    false,
                    true,
                    emptyTestFilter(),
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.BUILD_TESTS_ONLY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    true,
                    true,
                    emptyTestFilter(),
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.BUILD_TESTS_ONLY
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    false,
                    true,
                    emptyTestFilter(),
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.DETERMINE_TESTS
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<E?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<E?>(),
                    true,
                    true,
                    emptyTestFilter(),
                    com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.DETERMINE_TESTS
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<String?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<String?>("a"),
                    false,
                    true,
                    null
                )
            )
            .addEqualityGroup(
                of(
                    com.google.common.collect.ImmutableList.of<String?>(),
                    PathFragment.EMPTY_FRAGMENT,
                    com.google.common.collect.ImmutableList.of<String?>("a"),
                    true,
                    true,
                    null
                )
            )
            .testEquals()
    }

    @org.junit.Test
    fun testNull() {
        NullPointerTester()
            .testAllPublicConstructors(TargetPatternPhaseKey::class.java)
    }

    companion object {
        private fun of(
            targetPatterns: com.google.common.collect.ImmutableList<String?>?,
            offset: PathFragment?,
            buildTagFilter: com.google.common.collect.ImmutableList<String?>?,
            includeManualTests: Boolean,
            expandTestSuites: Boolean,
            testFilter: TestFilter?,
            vararg flags: Flag?
        ): TargetPatternPhaseKey {
            val set: com.google.common.collect.ImmutableSet<Flag?> =
                com.google.common.collect.ImmutableSet.copyOf<Flag?>(flags)
            val compileOneDependency: Boolean =
                set.contains(com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.COMPILE_ONE_DEPENDENCY)
            val buildTestsOnly: Boolean =
                set.contains(com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.BUILD_TESTS_ONLY)
            val determineTests: Boolean =
                set.contains(com.google.devtools.build.lib.skyframe.TargetPatternPhaseKeyTest.Flag.DETERMINE_TESTS)
            return TargetPatternPhaseValue.key(
                targetPatterns,
                offset,
                compileOneDependency,
                buildTestsOnly,
                determineTests,
                buildTagFilter,
                includeManualTests,
                expandTestSuites,
                testFilter
            )
        }

        private fun of(
            targetPatterns: com.google.common.collect.ImmutableList<String?>?, offset: PathFragment?
        ): TargetPatternPhaseKey {
            return of(targetPatterns, offset, com.google.common.collect.ImmutableList.of<String?>(), false, true, null)
        }

        private fun emptyTestFilter(): TestFilter {
            val options: LoadingOptions? = Options.getDefaults(LoadingOptions::class.java)
            return TestFilter.forOptions(options)
        }
    }
}
