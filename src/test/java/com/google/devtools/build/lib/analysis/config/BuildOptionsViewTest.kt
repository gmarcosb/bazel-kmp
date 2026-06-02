// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.rules.cpp.CppOptions

/** Tests for [BuildOptionsView].  */
@RunWith(JUnit4::class)
class BuildOptionsViewTest {
    private var options: BuildOptions? = null

    @Before
    fun constructBuildOptions() {
        options =
            BuildOptions.of(
                BUILD_CONFIG_OPTIONS,
                OptionsParser.builder().optionsClasses(BUILD_CONFIG_OPTIONS).build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowedGet() {
        val restrictedOptions: BuildOptionsView =
            BuildOptionsView(options, com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java))
        assertThat(restrictedOptions.get(CoreOptions::class.java))
            .isSameInstanceAs(options.get(CoreOptions::class.java))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prohibitedGet() {
        val restrictedOptions: BuildOptionsView =
            BuildOptionsView(options, com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { restrictedOptions.get(CppOptions::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowedContains() {
        val restrictedOptions: BuildOptionsView =
            BuildOptionsView(options, com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java))
        assertThat(restrictedOptions.contains(CoreOptions::class.java)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun prohibitedContains() {
        val restrictedOptions: BuildOptionsView =
            BuildOptionsView(options, com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java))
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { restrictedOptions.contains(CppOptions::class.java) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cloneTest() {
        val restrictedOptions: BuildOptionsView =
            BuildOptionsView(options, com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java))
        val clone: BuildOptionsView = restrictedOptions.clone()
        assertThat(clone).isNotSameInstanceAs(restrictedOptions)
        assertThat(restrictedOptions.underlying()).isSameInstanceAs(options)
        assertThat(clone.underlying()).isNotSameInstanceAs(options)
        assertThat(clone.underlying()).isEqualTo(options)
    }

    companion object {
        private val BUILD_CONFIG_OPTIONS: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java, CppOptions::class.java)
    }
}
