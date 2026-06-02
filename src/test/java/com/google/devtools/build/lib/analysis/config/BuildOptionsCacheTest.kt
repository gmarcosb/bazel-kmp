// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.common.options.OptionsParsingException

/** Tests for [BuildOptionsCache].  */
@RunWith(JUnit4::class)
class BuildOptionsCacheTest {
    private val cache: BuildOptionsCache<Context?> = BuildOptionsCache(
        { options, context, unused ->
            val clone: BuildOptionsView = options.clone()
            clone.get(CoreOptions::class.java).setCpu(context.`val`)
            clone.underlying()
        })

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun appliesTransitionFunction() {
        val from: BuildOptionsView = createOptions("--cpu=default")
        val to: BuildOptions = cache.applyTransition(
            from,
            com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
            null
        )
        assertCpu(from.underlying(), "default") // No change.
        assertCpu(to, "abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cachesTransition() {
        val to1: BuildOptions? =
            cache.applyTransition(
                createOptions("--cpu=default"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        val to2: BuildOptions? =
            cache.applyTransition(
                createOptions("--cpu=default"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        assertThat(to2).isSameInstanceAs(to1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheKeyRespectsFromOptions() {
        val to1: BuildOptions =
            cache.applyTransition(
                createOptions("--cpu=default", "--host_cpu=one"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        val to2: BuildOptions =
            cache.applyTransition(
                createOptions("--cpu=default", "--host_cpu=two"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        assertCpu(to1, "abc")
        assertCpu(to2, "abc")
        assertHostCpu(to1, "one")
        assertHostCpu(to2, "two")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheKeyRespectsContext() {
        val to1: BuildOptions =
            cache.applyTransition(
                createOptions("--cpu=default"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        val to2: BuildOptions =
            cache.applyTransition(
                createOptions("--cpu=default"),
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("xyz"),
                null
            )
        assertCpu(to1, "abc")
        assertCpu(to2, "xyz")
    }

    // We would like to also test that the toOptions are not strongly retained, but since they are
    // referenced softly, this is not easy to do.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun doesNotRetainFromOptions() {
        var from: BuildOptionsView? = createOptions("--cpu=default")
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cache.applyTransition(
                from,
                com.google.devtools.build.lib.analysis.config.BuildOptionsCacheTest.Context("abc"),
                null
            )
        val fromRef: java.lang.ref.WeakReference<BuildOptions?> = java.lang.ref.WeakReference<T?>(from.underlying())
        from = null
        GcFinalization.awaitClear(fromRef)
    }

    /** Simple value class for testing the context parameter.  */
    private class Context(`val`: String) {
        private val `val`: String

        init {
            this.`val` = `val`
        }

        override fun hashCode(): Int {
            return `val`.hashCode()
        }

        override fun equals(o: Any?): Boolean {
            if (o === this) {
                return true
            }
            return o is Context && `val` == o.`val`
        }
    }

    companion object {
        @Throws(OptionsParsingException::class)
        private fun createOptions(vararg args: String?): BuildOptionsView {
            return BuildOptionsView(
                BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java), args),
                com.google.common.collect.ImmutableSet.of<E?>(CoreOptions::class.java)
            )
        }

        private fun assertCpu(options: BuildOptions, expected: String?) {
            assertThat(options.get(CoreOptions::class.java).getCpu()).isEqualTo(expected)
        }

        private fun assertHostCpu(options: BuildOptions, expected: String?) {
            assertThat(options.get(CoreOptions::class.java).getHostCpu()).isEqualTo(expected)
        }
    }
}
