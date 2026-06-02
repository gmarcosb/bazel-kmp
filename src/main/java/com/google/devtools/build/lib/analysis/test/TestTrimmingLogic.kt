// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * Contains the pure logic for trimming test configuration from non-test targets that backs [ ].
 */
object TestTrimmingLogic {
    val REQUIRED_FRAGMENTS: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
        com.google.common.collect.ImmutableSet.of<E?>(
            CoreOptions::class.java,
            com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java
        )

    // This cache is to prevent major slowdowns when using --trim_test_configuration. This
    // transition is always invoked on every target in the top-level invocation. Thus, a wide
    // invocation, like //..., will cause the transition to be invoked on a large number of targets
    // leading to significant performance degradation. (Notably, the transition itself is somewhat
    // fast; however, the post-processing of the BuildOptions into the actual BuildConfigurationValue
    // takes a significant amount of time).
    //
    // Test any caching changes for performance impact in a longwide scenario with
    // --trim_test_configuration on versus off.
    // LINT.IfChange
    private val CACHE: BuildOptionsCache<Boolean?> = BuildOptionsCache(
        { options, unused, unusedNonEventHandler ->
            val builder: BuildOptions.Builder = options.underlying().toBuilder()
            builder.removeFragmentOptions(com.google.devtools.build.lib.analysis.test.TestConfiguration.TestOptions::class.java)
            // Only the label of the --run_under target (if any) needs to be part of the
            // configuration for non-test targets, all other information is directly obtained
            // from the options in RunCommand.
            val coreOptions: CoreOptions = builder.getFragmentOptions(CoreOptions::class.java)
            coreOptions.setRunUnder(
                RunUnder.trimForNonTestConfiguration(coreOptions.getRunUnder())
            )
            builder.build()
        })

    // LINT.ThenChange(TestConfiguration.java)
    /** Returns a new [BuildOptions] instance with test configuration removed.  */
    fun trim(buildOptions: BuildOptions?): BuildOptions {
        return TestTrimmingLogic.trim(BuildOptionsView(buildOptions, REQUIRED_FRAGMENTS))
    }

    /** Returns a new [BuildOptions] instance with test configuration removed.  */
    fun trim(buildOptions: BuildOptionsView?): BuildOptions {
        try {
            return CACHE.applyTransition(buildOptions, java.lang.Boolean.TRUE,  /* eventHandler= */null)
        } catch (e: java.lang.InterruptedException) {
            // The transition logic doesn't throw InterruptedException.
            throw java.lang.IllegalStateException(e)
        }
    }
}
