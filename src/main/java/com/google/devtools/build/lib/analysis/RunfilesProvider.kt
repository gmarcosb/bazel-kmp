// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/**
 * Runfiles a target contributes to targets that depend on it.
 * 
 * 
 * The set of runfiles contributed can be different if the dependency is through a `data
` *  attribute (note that this is just a rough approximation of the reality -- rule
 * implementations are free to request the data runfiles at any time)
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class RunfilesProvider private constructor(
    defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
    dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?
) : TransitiveInfoProvider {
    private val defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?
    private val dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?

    fun getDefaultRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
        return defaultRunfiles
    }

    fun getDataRunfiles(): com.google.devtools.build.lib.analysis.Runfiles? {
        return dataRunfiles
    }

    init {
        this.defaultRunfiles = defaultRunfiles
        this.dataRunfiles = dataRunfiles
    }

    companion object {
        /**
         * Returns a function that gets the default runfiles from a [TransitiveInfoCollection] or
         * the empty runfiles instance if it does not contain that provider.
         */
        val DEFAULT_RUNFILES: com.google.common.base.Function<TransitiveInfoCollection?, com.google.devtools.build.lib.analysis.Runfiles?> =
            object :
                com.google.common.base.Function<TransitiveInfoCollection?, com.google.devtools.build.lib.analysis.Runfiles?> {
                override fun apply(input: TransitiveInfoCollection): com.google.devtools.build.lib.analysis.Runfiles? {
                    val provider: RunfilesProvider? = input.getProvider(RunfilesProvider::class.java)
                    if (provider != null) {
                        return provider.getDefaultRunfiles()
                    }

                    return com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
                }
            }

        /**
         * Returns a function that gets the data runfiles from a [TransitiveInfoCollection] or the
         * empty runfiles instance if it does not contain that provider.
         * 
         * 
         * These are usually used if the target is depended on through a `data` attribute.
         */
        val DATA_RUNFILES: com.google.common.base.Function<TransitiveInfoCollection?, com.google.devtools.build.lib.analysis.Runfiles?> =
            object :
                com.google.common.base.Function<TransitiveInfoCollection?, com.google.devtools.build.lib.analysis.Runfiles?> {
                override fun apply(input: TransitiveInfoCollection): com.google.devtools.build.lib.analysis.Runfiles? {
                    val provider: RunfilesProvider? = input.getProvider(RunfilesProvider::class.java)
                    if (provider != null) {
                        return provider.getDataRunfiles()
                    }

                    return com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
                }
            }

        fun simple(defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?): RunfilesProvider {
            return RunfilesProvider(defaultRunfiles, defaultRunfiles)
        }

        fun withData(
            defaultRunfiles: com.google.devtools.build.lib.analysis.Runfiles?,
            dataRunfiles: com.google.devtools.build.lib.analysis.Runfiles?
        ): RunfilesProvider {
            return RunfilesProvider(defaultRunfiles, dataRunfiles)
        }

        val EMPTY: RunfilesProvider = RunfilesProvider(
            com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY,
            com.google.devtools.build.lib.analysis.Runfiles.Companion.EMPTY
        )
    }
}
