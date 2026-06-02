// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.EnvironmentVariableValue
import com.google.devtools.build.lib.skyframe.PrecomputedValue
import com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult

/**
 * Skyframe function that provides the effective value for an environment variable in the context of
 * repository rules and module extensions. This will be the value from the repo environment as
 * provided by [com.google.devtools.build.lib.runtime.CommandEnvironment.getRepoEnv].
 */
class RepoEnvironmentFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue {
        val repoEnv: MutableMap<String?, String?>? = PrecomputedValue.Companion.REPO_ENV.get(env)
        val key = skyKey.argument() as String?
        return EnvironmentVariableValue(repoEnv!!.get(key))
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: String?) : AbstractSkyKey<String?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REPOSITORY_ENVIRONMENT_VARIABLE
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: String?): Key {
                return com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction.Key.Companion.interner.intern(key)
            }
        }
    }

    companion object {
        /** Returns the SkyKey to invoke this function for the environment variable `variable`.  */
        fun key(variable: String?): Key {
            return com.google.devtools.build.lib.skyframe.RepoEnvironmentFunction.Key.Companion.create(variable)
        }

        /**
         * Returns a map of environment variable key => values, getting them from Skyframe. Returns null
         * if and only if some dependencies from Skyframe still need to be resolved.
         */
        @Throws(java.lang.InterruptedException::class)
        fun getEnvironmentView(
            env: SkyFunction.Environment, keys: MutableSet<String?>
        ): com.google.common.collect.ImmutableSortedMap<String?, java.util.Optional<String?>?>? {
            val skyKeys: MutableCollection<Key> = com.google.common.collect.Collections2.transform<String?, Key>(
                keys,
                com.google.common.base.Function { variable: String? -> key(variable) })
            val values: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
            if (env.valuesMissing()) {
                return null
            }

            val result: com.google.common.collect.ImmutableSortedMap.Builder<String?, java.util.Optional<String?>?> =
                com.google.common.collect.ImmutableSortedMap.naturalOrder<String?, java.util.Optional<String?>?>()
            for (key in skyKeys) {
                val value: EnvironmentVariableValue? = values.get(key) as EnvironmentVariableValue?
                if (value == null) {
                    return null
                }
                result.put(key.argument().toString(), java.util.Optional.ofNullable<String?>(value.value))
            }
            return result.buildOrThrow()
        }
    }
}
