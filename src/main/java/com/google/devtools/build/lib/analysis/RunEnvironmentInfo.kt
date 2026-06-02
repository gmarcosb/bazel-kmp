// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.NativeInfo
import com.google.devtools.build.lib.starlarkbuildapi.RunEnvironmentInfoApi
import com.google.devtools.build.lib.starlarkbuildapi.RunEnvironmentInfoApi.RunEnvironmentInfoApiProvider

/**
 * Provider containing any additional environment variables for use in the executable or test
 * action.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class RunEnvironmentInfo(
    environment: MutableMap<String?, String?>?,
    inheritedEnvironment: MutableList<String?>?,
    private val shouldErrorOnNonExecutableRule: Boolean
) : NativeInfo(), RunEnvironmentInfoApi {
    private val environment: com.google.common.collect.ImmutableMap<String?, String?>
    private val inheritedEnvironment: com.google.common.collect.ImmutableList<String?>

    /** Constructs a new provider with the given fixed and inherited environment variables.  */
    init {
        this.environment = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
            com.google.common.base.Preconditions.checkNotNull<MutableMap<String?, String?>?>(environment)
        )
        this.inheritedEnvironment =
            com.google.common.collect.ImmutableList.copyOf<String?>(
                com.google.common.base.Preconditions.checkNotNull<MutableList<String?>?>(
                    inheritedEnvironment
                )
            )
    }

    /**
     * Returns environment variables which should be set when the target advertising this provider is
     * executed.
     */
    override fun getEnvironment(): com.google.common.collect.ImmutableMap<String?, String?> {
        return environment
    }

    /**
     * Returns names of environment variables whose value should be inherited from the shell
     * environment when the target advertising this provider is executed.
     */
    override fun getInheritedEnvironment(): com.google.common.collect.ImmutableList<String?> {
        return inheritedEnvironment
    }

    /**
     * Returns whether advertising this provider on a non-executable (and thus non-test) rule should
     * result in an error or a warning. The latter is required to not break testing.TestEnvironment,
     * which is now implemented via RunEnvironmentInfo.
     */
    fun shouldErrorOnNonExecutableRule(): Boolean {
        return shouldErrorOnNonExecutableRule
    }

    /** Provider implementation for [RunEnvironmentInfoApi].  */
    class RunEnvironmentInfoProvider private constructor() :
        BuiltinProvider<RunEnvironmentInfo?>("RunEnvironmentInfo", RunEnvironmentInfo::class.java),
        RunEnvironmentInfoApiProvider {
        @Throws(net.starlark.java.eval.EvalException::class)
        override fun constructor(
            environment: net.starlark.java.eval.Dict<*, *>?, inheritedEnvironment: net.starlark.java.eval.Sequence<*>?
        ): RunEnvironmentInfoApi {
            return RunEnvironmentInfo(
                net.starlark.java.eval.Dict.cast<String?, String?>(
                    environment,
                    String::class.java,
                    String::class.java,
                    "environment"
                ),
                net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(
                    net.starlark.java.eval.Sequence.cast<String?>(
                        inheritedEnvironment,
                        String::class.java,
                        "inherited_environment"
                    )
                ),  /* shouldErrorOnNonExecutableRule= */
                true
            )
        }
    }

    companion object {
        /** Singleton instance of the provider type for [DefaultInfo].  */
        val provider: RunEnvironmentInfoProvider = RunEnvironmentInfoProvider()
            get() = Companion.field
    }
}
