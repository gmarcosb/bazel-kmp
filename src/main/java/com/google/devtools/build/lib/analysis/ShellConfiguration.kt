// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.FragmentOptions

/** A configuration fragment that tells where the shell is.  */
@RequiresOptions(options = [com.google.devtools.build.lib.analysis.ShellConfiguration.Options::class])
class ShellConfiguration(buildOptions: BuildOptions) : com.google.devtools.build.lib.analysis.config.Fragment() {
    private val defaultShellExecutableFromOptions: PathFragment?

    init {
        this.defaultShellExecutableFromOptions =
            optionsBasedDefault.apply(buildOptions.get<T?>(com.google.devtools.build.lib.analysis.ShellConfiguration.Options::class.java))
    }

    /* Returns the default shell from build options if set explicitly. */
    fun getOptionsBasedDefault(): PathFragment? {
        return defaultShellExecutableFromOptions
    }

    /** An option that tells Bazel where the shell is.  */
    @OptionsClass
    abstract class Options : FragmentOptions() {
        @com.google.devtools.common.options.Option(
            name = "shell_executable",
            converter = PathFragmentConverter::class,
            defaultValue = "null",
            documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
            help = """
            Absolute path to the shell executable for Bazel to use. If this is unset, but the
            `BAZEL_SH` environment variable is set on the first Bazel invocation (that starts
            up a Bazel server), Bazel uses that. If neither is set, Bazel uses a hard-coded
            default path depending on the operating system it runs on;
            - Windows: `c:/msys64/usr/bin/bash.exe`
            - FreeBSD: `/usr/local/bin/bash`
            - All others: `/bin/bash`.

            Note that using a shell that is not compatible with `bash` may lead
            to build failures or runtime failures of the generated binaries.
            
            """.trimIndent()
        )
        abstract fun getShellExecutable(): PathFragment?

        abstract fun setShellExecutable(value: PathFragment?)
    }

    companion object {
        private var shellExecutables: MutableMap<OS?, PathFragment?>? = null

        private var optionsBasedDefault: java.util.function.Function<Options?, PathFragment?>? = null

        /**
         * Injects a function for retrieving the default sh path from build options, and a map for
         * locating the correct sh executable given a set of target constraints.
         */
        fun injectShellExecutableFinder(
            shellFromOptionsFinder: java.util.function.Function<Options?, PathFragment?>,
            osToShellMap: MutableMap<OS?, PathFragment?>
        ) {
            // It'd be nice not to have to set a global static field. But there are so many disparate calls
            // to getShellExecutables() (in both the build's analysis phase and in the run command) that
            // feeding this through instance variables is unwieldy. Fortunately this info is a function of
            // the Blaze implementation and not something that might change between builds.
            optionsBasedDefault = shellFromOptionsFinder
            shellExecutables = osToShellMap
        }

        fun getShellExecutable(os: OS?): java.util.Optional<PathFragment?> {
            return java.util.Optional.ofNullable<PathFragment?>(shellExecutables!!.get(os))
        }
    }
}
