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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * A configuration transition that maps a single input [BuildOptions] to a single output
 * [BuildOptions].
 * 
 * 
 * Also see [SplitTransition], which maps a single input [BuildOptions] to possibly
 * multiple [BuildOptions].
 * 
 * 
 * The concept is simple: given the input configuration's build options, the transition does
 * whatever it wants to them and returns the modified result.
 * 
 * 
 * Implementations must be stateless: the output must exclusively depend on the input build
 * options and any immutable member fields. Implementations must also override [Object.equals]
 * and [Object.hashCode] unless exclusively accessed as singletons. For example:
 * 
 * <pre>
 * public class MyTransition implements PatchTransition {
 * public MyTransition INSTANCE = new MyTransition();
 * 
 * private MyTransition() {}
 * 
 * @Override
 * public BuildOptions patch(RestrictedBuildOptions options) {
 * BuildOptions toOptions = options.clone();
 * // Change some setting on toOptions
 * return toOptions;
 * }
 * }
</pre> * 
 * 
 * 
 * For performance reasons, the input options are passed as a *reference*, not a
 * *copy*. Implementations should *always* treat these as immutable, and call [ ][com.google.devtools.build.lib.analysis.config.BuildOptions.clone] before making changes.
 * Unfortunately, [com.google.devtools.build.lib.analysis.config.BuildOptions] doesn't
 * currently enforce immutability. So care must be taken not to modify the wrong instance.
 */
interface PatchTransition : ConfigurationTransition {
    /**
     * Applies the transition.
     * 
     * 
     * Blaze throws an [IllegalArgumentException] if this method reads any options fragment
     * not declared in [ConfigurationTransition.requiresOptionFragments].
     * 
     * @param options the options representing the input configuration to this transition. **DO NOT
     * MODIFY THIS VARIABLE WITHOUT CLONING IT FIRST!**
     * @param eventHandler
     * @return the options representing the desired post-transition configuration
     */
    @Throws(java.lang.InterruptedException::class)
    fun patch(
        options: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): BuildOptions?

    @Throws(java.lang.InterruptedException::class)
    override fun apply(
        buildOptions: BuildOptionsView?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ): MutableMap<String?, BuildOptions?> {
        return Collections.singletonMap<String?, BuildOptions?>(
            ConfigurationTransition.Companion.PATCH_TRANSITION_KEY,
            patch(buildOptions, eventHandler)
        )
    }

    override fun reasonForOverride(): String? {
        return "This is a fundamental transition modeling the simple, common case 1-1 options mapping"
    }
}
