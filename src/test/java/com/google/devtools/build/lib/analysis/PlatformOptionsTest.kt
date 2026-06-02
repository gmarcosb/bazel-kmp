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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.Label

@RunWith(JUnit4::class)
class PlatformOptionsTest : OptionsTestCase<PlatformOptions>() {
    val optionsClass: java.lang.Class<PlatformOptions?>
        get() = PlatformOptions::class.java

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraPlatforms_orderMatters() {
        // It seems that the platforms are considered in order. Picking the wrong one results in broken
        // builds. So add test asserting that order matters.
        val one: PlatformOptions = createWithPrefix(EXTRA_PLATFORMS_PREFIX, "platform1", "platform2")
        val two: PlatformOptions = createWithPrefix(EXTRA_PLATFORMS_PREFIX, "platform2", "platform1")
        assertDifferent(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraToolchains_ordering() {
        // The ordering matters for tool chains, but the last one in the list has highest priority.
        val one: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "one", "two")
        val two: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "two", "one")
        assertDifferent(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraToolchains_duplicates() {
        // Specifying the same tool chain multiple times is a no-op.
        val one: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "one", "one")
        val two: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "one")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraToolchains_duplicates_keepLast() {
        // The last toolchain in the list has highest priority, so keep the last of any duplicates.
        val one: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "one", "two", "one")
        val two: PlatformOptions = createWithPrefix(EXTRA_TOOLCHAINS_PREFIX, "two", "one")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatforms_duplicates() {
        val one: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//p:one,//p:one")
        val two: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//p:one")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatforms_extraValues() {
        // Only the first value matters.
        val one: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//one,//two")
        val two: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//one")
        assertSame(one, two)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPlatforms_orderMatters() {
        // Changing the order changes the semantics.
        val foo: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//one,//two")
        val bar: PlatformOptions = createWithPrefix(PLATFORMS_PREFIX, "//two,//one")
        assertDifferent(foo, bar)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun platformMappings_default() {
        val options: PlatformOptions = create(com.google.common.collect.ImmutableList.of<String?>())
        assertThat(options.platformMappingKey).isEqualTo(PlatformMappingKey.DEFAULT)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun platformMappings_custom() {
        val options: PlatformOptions = createWithPrefix(PLATFORM_MAPPINGS_PREFIX, "a/b/platform_mappings")
        assertThat(options.platformMappingKey)
            .isEqualTo(
                PlatformMappingKey.createExplicitlySet(PathFragment.create("a/b/platform_mappings"))
            )
    }

    @org.junit.Test
    fun platformMappings_absolutePath_throws() {
        org.junit.Assert.assertThrows<OptionsParsingException?>(
            OptionsParsingException::class.java,
            org.junit.function.ThrowingRunnable {
                createWithPrefix(
                    PLATFORM_MAPPINGS_PREFIX,
                    "/a/b/platform_mappings"
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun hostPlatformEmpty_default() {
        val options: PlatformOptions = createWithPrefix(HOST_PLATFORM_PREFIX, "")
        assertThat(options.hostPlatform)
            .isEqualTo(Label.parseCanonicalUnchecked(PlatformOptions.DEFAULT_HOST_PLATFORM))
    }

    companion object {
        private const val EXTRA_PLATFORMS_PREFIX = "--extra_execution_platforms="
        private const val EXTRA_TOOLCHAINS_PREFIX = "--extra_toolchains="
        private const val PLATFORMS_PREFIX = "--platforms="
        private const val PLATFORM_MAPPINGS_PREFIX = "--platform_mappings="
        private const val HOST_PLATFORM_PREFIX = "--host_platform="
    }
}
