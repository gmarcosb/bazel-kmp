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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** A return value of [FlagSetFunction]  */
class FlagSetValue(
    flags: com.google.common.collect.ImmutableSet<String?>?,
    persistentMessages: com.google.common.collect.ImmutableSet<Event?>?
) : SkyValue {
    private val flags: com.google.common.collect.ImmutableSet<String?>?

    /**
     * Warnings and info messages for the caller to emit. This lets the caller persistently emit
     * messages that Skyframe ignores on cache hits. See [Reportable.storeForReplay]).
     */
    private val persistentMessages: com.google.common.collect.ImmutableSet<Event?>?

    /** Key for [FlagSetValue] based on the raw flags.  */
    @ThreadSafety.Immutable
    @AutoCodec
    class Key(
        targets: com.google.common.collect.ImmutableSet<Label?>?,
        projectFile: Label?,
        sclConfig: String?,
        targetOptions: BuildOptions?,
        allOptionNames: com.google.common.collect.ImmutableSet<String?>?,
        userOptions: com.google.common.collect.ImmutableMap<String?, String?>?,
        configFlagDefinitions: ConfigFlagDefinitions?,
        val enforceCanonical: Boolean
    ) : SkyKey {
        val skyKeyInterner: SkyKeyInterner<*>
            get() = com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key.Companion.interner

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.FLAG_SET
        }

        val targets: com.google.common.collect.ImmutableSet<Label?>?
        val projectFile: Label?
        val sclConfig: String?
        val targetOptions: BuildOptions?
        val allOptionNames: com.google.common.collect.ImmutableSet<String?>?
        val userOptions: com.google.common.collect.ImmutableMap<String?, String?>?
        val configFlagDefinitions: ConfigFlagDefinitions?

        init {
            this.configFlagDefinitions = configFlagDefinitions
            this.userOptions = userOptions
            this.allOptionNames = allOptionNames
            this.targetOptions = targetOptions
            this.projectFile = projectFile
            this.targets = targets
            var sclConfig = sclConfig
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<Label?>?>(targets, "targets")
            Object > java.util.Objects.requireNonNull<Any?>(projectFile, "projectFile")
            sclConfig = com.google.common.base.Strings.nullToEmpty(sclConfig)
            Object > java.util.Objects.requireNonNull<Any?>(targetOptions, "targetOptions")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<String?>?>(
                allOptionNames,
                "allOptionNames"
            )
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, String?>?>(
                userOptions,
                "userOptions"
            )
            Object > java.util.Objects.requireNonNull<Any?>(configFlagDefinitions, "configFlagDefinitions")
            this.sclConfig = sclConfig
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            /**
             * Creating @link FlagSetValue.Key. b/409382048 requires to pass the targets to the Key so it
             * can be used in FlagSetFunction. But this is bad for Skyframe caching. For the sake of fast
             * iteration, this is the simplest approach. We should consider to optimize this in the future.
             */
            fun create(
                targets: com.google.common.collect.ImmutableSet<Label?>?,
                projectFile: Label?,
                sclConfig: String?,
                targetOptions: BuildOptions?,
                allOptionNames: com.google.common.collect.ImmutableSet<String?>?,
                userOptions: com.google.common.collect.ImmutableMap<String?, String?>?,
                configFlagDefinitions: ConfigFlagDefinitions?,
                enforceCanonical: Boolean
            ): Key {
                return com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.config.FlagSetValue.Key(
                        targets,
                        projectFile,
                        sclConfig,
                        targetOptions,
                        allOptionNames,
                        userOptions,
                        configFlagDefinitions,
                        enforceCanonical
                    )
                )
            }
        }
    }

    init {
        this.flags = flags
        this.persistentMessages = persistentMessages
    }

    val optionsFromFlagset: com.google.common.collect.ImmutableSet<String?>?
        /** Returns the set of flags to be applied to the build from the flagset, in flag=value form.  */
        get() = flags

    fun getPersistentMessages(): com.google.common.collect.ImmutableSet<Event?>? {
        return persistentMessages
    }

    companion object {
        fun create(
            flags: com.google.common.collect.ImmutableSet<String?>?,
            persistentMessages: com.google.common.collect.ImmutableSet<Event?>?
        ): FlagSetValue {
            return FlagSetValue(flags, persistentMessages)
        }
    }
}
