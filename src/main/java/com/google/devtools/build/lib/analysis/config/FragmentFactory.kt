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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.analysis.config.FragmentOptions
import com.google.devtools.build.lib.analysis.config.InvalidConfigurationException
import java.util.concurrent.CompletionException

/** Handles construction of [Fragment] from a [BuildOptions].  */
class FragmentFactory {
    /**
     * Creates the requested [Fragment] using a given [BuildOptions].
     * 
     * 
     * Returns null if the fragment could not be built (e.g. the supplied BuildOptions does not
     * contain the required [FragmentOption]s).
     */
    @Throws(InvalidConfigurationException::class)
    fun createFragment(buildOptions: BuildOptions, fragmentClass: java.lang.Class<out Fragment>): Fragment? {
        val trimmedOptions: BuildOptions = trimToRequiredOptions(buildOptions, fragmentClass)
        val fragment: Fragment?
        val fragmentKey = FragmentKey.Companion.create(trimmedOptions, fragmentClass)
        try {
            fragment = fragmentCache.get(fragmentKey)
        } catch (e: CompletionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<InvalidConfigurationException?>(
                e.cause,
                InvalidConfigurationException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.cause)
            throw e
        }
        if (fragment !== NULL_MARKER) {
            return fragment
        } else {
            // NULL_MARKER is never GC'ed, so this entry will stay in cache forever unless we delete it
            // ourselves. Since it's a cheap computation we don't care about recomputing it.
            fragmentCache.invalidate(fragmentKey)
            return null
        }
    }

    private val fragmentCache: com.github.benmanes.caffeine.cache.LoadingCache<FragmentKey?, Fragment?> =
        Caffeine.newBuilder().weakValues()
            .build<FragmentKey?, Fragment?>(com.github.benmanes.caffeine.cache.CacheLoader { fragmentKey: FragmentKey? ->
                Companion.makeFragment(fragmentKey!!)
            })

    /**
     * A fragment key.
     * 
     * @param buildOptions These BuildOptions should be already-trimmed to maximize cache efficacy
     */
    internal class FragmentKey(buildOptions: BuildOptions?, fragmentClass: java.lang.Class<out Fragment>) {
        val buildOptions: BuildOptions?
        val fragmentClass: java.lang.Class<out Fragment>

        init {
            this.fragmentClass = fragmentClass
            this.buildOptions = buildOptions
            java.util.Objects.requireNonNull<Any?>(buildOptions, "buildOptions")
            java.util.Objects.requireNonNull(fragmentClass, "fragmentClass")
        }

        companion object {
            private fun create(
                buildOptions: BuildOptions?, fragmentClass: java.lang.Class<out Fragment>
            ): FragmentKey {
                return FragmentKey(buildOptions, fragmentClass)
            }
        }
    }

    companion object {
        /** Cache and associated infrastructure*  */ // Cache with weak values can't have null values.
        // TODO(blaze-configurability-team): At the moment, the only time shouldInclude is false is when
        //   TestFragment is constructed without TestOptions, which is already being registered as a
        //   required option of TestFragment. Should just abort fragment construction early when a
        //   required option is missing rather than use this NULL_MARKER infra.
        private val NULL_MARKER: Fragment = object : Fragment() {}

        private fun trimToRequiredOptions(
            original: BuildOptions, fragment: java.lang.Class<out Fragment>?
        ): BuildOptions {
            val trimmed: BuildOptions.Builder = BuildOptions.builder()
            val requiredOptions: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
                Fragment.requiredOptions(fragment)
            for (options in original.getNativeOptions()) {
                // CoreOptions is implicitly required by all fragments.
                if (options is CoreOptions || requiredOptions.contains(options.getOptionsClass())) {
                    trimmed.addFragmentOptions(options)
                }
            }
            if (Fragment.requiresStarlarkOptions(fragment)) {
                trimmed.addStarlarkOptions(original.getStarlarkOptions())
            }
            return trimmed.build()
        }

        @Throws(InvalidConfigurationException::class)
        private fun makeFragment(fragmentKey: FragmentKey): Fragment? {
            val buildOptions: BuildOptions? = fragmentKey.buildOptions
            val fragmentClass: java.lang.Class<out Fragment> = fragmentKey.fragmentClass
            val noConstructorPattern = "%s lacks constructor(BuildOptions)"
            try {
                val fragment: Fragment =
                    fragmentClass.getConstructor(BuildOptions::class.java).newInstance(buildOptions)
                return if (fragment.shouldInclude()) fragment else NULL_MARKER
            } catch (e: java.lang.reflect.InvocationTargetException) {
                if (e.cause is InvalidConfigurationException) {
                    throw e.cause as InvalidConfigurationException?
                }
                throw java.lang.IllegalStateException(String.format(noConstructorPattern, fragmentClass), e)
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException(String.format(noConstructorPattern, fragmentClass), e)
            }
        }
    }
}
