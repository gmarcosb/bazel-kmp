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
package com.google.devtools.build.lib.testing.common

import com.google.devtools.build.lib.testing.common.FakeOptions

/**
 * Fake options class, allowing to easily create an [OptionsProvider] with injected options.
 * 
 * 
 * The alternative to [FakeOptions] would be creating an [ ] and parsing arguments.
 */
class FakeOptions private constructor(options: com.google.common.collect.ImmutableClassToInstanceMap<com.google.devtools.common.options.OptionsBase?>) :
    com.google.devtools.common.options.OptionsProvider {
    private val options: com.google.common.collect.ImmutableClassToInstanceMap<com.google.devtools.common.options.OptionsBase?>

    init {
        this.options = options
    }

    /** Builder for [FakeOptions].  */
    class Builder private constructor() {
        private val options: com.google.common.collect.ImmutableMap.Builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.devtools.common.options.OptionsBase?> =
            com.google.common.collect.ImmutableMap.builder<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.devtools.common.options.OptionsBase?>()

        /**
         * Adds a specified option for the [options][OptionsBase] class.
         * 
         * 
         * Please note that [build] will fail if this method is called twice with options of
         * the same class.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <O : com.google.devtools.common.options.OptionsBase?> put(options: O?): Builder {
            this.options.put(options.getOptionsClass(), options)
            return this
        }

        /**
         * Puts defaults for each of the provided [option classes][OptionsBase].
         * 
         * 
         * Please note that [build] will fail if we overwrite an already specified [ ] class.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @java.lang.SafeVarargs
        fun putDefaults(vararg optionsClasses: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?): Builder {
            for (optionsClass in optionsClasses) {
                put(com.google.devtools.common.options.Options.getDefaults(optionsClass))
            }
            return this
        }

        fun build(): com.google.devtools.common.options.OptionsProvider? {
            val optionsMap: com.google.common.collect.ImmutableMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, com.google.devtools.common.options.OptionsBase?> =
                options.build()
            if (optionsMap.isEmpty()) {
                return com.google.devtools.common.options.OptionsProvider.EMPTY
            }
            return FakeOptions(
                com.google.common.collect.ImmutableClassToInstanceMap.copyOf<com.google.devtools.common.options.OptionsBase?, com.google.devtools.common.options.OptionsBase?>(
                    optionsMap
                )
            )
        }
    }

    override fun <O : com.google.devtools.common.options.OptionsBase?> getOptions(optionsClass: java.lang.Class<O?>): O? {
        return options.getInstance<O?>(optionsClass)
    }

    val starlarkOptions: com.google.common.collect.ImmutableMap<String?, Any?>
        get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

    val scopesAttributes: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.of<String?, String?>()

    val onLeaveScopeValues: com.google.common.collect.ImmutableMap<String?, Any?>
        get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

    val explicitCommandLineStarlarkOptions: MutableMap<String?, Any?>
        get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

    val starlarkOptionsAllowingMultiple: com.google.common.collect.ImmutableSet<String?>
        get() = com.google.common.collect.ImmutableSet.of<String?>()

    val userOptions: com.google.common.collect.ImmutableMap<String?, String?>
        get() = com.google.common.collect.ImmutableMap.of<String?, String?>()

    companion object {
        /** Creates an [OptionsProvider] with a provided options value for its class.  */
        fun <O : com.google.devtools.common.options.OptionsBase?> of(options: O?): com.google.devtools.common.options.OptionsProvider? {
            return builder().put<O?>(options).build()
        }

        /**
         * Creates an [OptionsProvider] which has defaults for all provided [ option][OptionsBase] classes.
         */
        @java.lang.SafeVarargs
        fun ofDefaults(vararg optionsClasses: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?): com.google.devtools.common.options.OptionsProvider? {
            return builder().putDefaults(*optionsClasses).build()
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.testing.common.FakeOptions.Builder()
        }
    }
}
