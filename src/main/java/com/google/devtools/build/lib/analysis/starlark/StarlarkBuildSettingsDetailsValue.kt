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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * This contains information about a list of given Starlark build options, specifically their
 * defaults and the (final) actual values of alias [Label].
 * 
 * 
 * For memory-efficiency reasons, aliasToActual contains only aliases in keys. Other attributes
 * contain only actual build setting as keys.
 * 
 * 
 * Potentially aliased targets can be unaliased with aliasToActual().getWithDefault(raw, raw);
 * 
 * @param buildSettingToDefault Map from each build option to its default value. Does not include
 * aliases.
 * @param buildSettingToType Map from each build option to its type information. Does not include
 * aliases.
 * @param buildSettingIsAllowsMultiple If build option is in this set, is an allows_multiple option.
 * Does not include aliases.
 * @param aliasToActual Map from an alias Label to actual Label it points to.
 * @param customExecScopeValues Map from a build setting Label to the custom exec scope value for
 * that setting. This contains [--foo, default_foo, --host_foo, default_host_foo,
 * scope_type_foo, scope_type_host_foo]
 */
@com.google.errorprone.annotations.CheckReturnValue
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
@AutoCodec
class StarlarkBuildSettingsDetailsValue(
    buildSettingToDefault: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?,
    buildSettingToType: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>?,
    buildSettingIsAllowsMultiple: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?,
    aliasToActual: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>?,
    customExecScopeValues: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>?
) : SkyValue {
    /**
     * Represents a custom exec scope value for a Starlark build setting.
     * 
     * @param flag the label of the build setting, e.g. //:foo
     * @param flagDefault the default value of the build setting
     * @param hostFlag the label of the host flag, e.g. //:host_foo
     * @param hostFlagDefault the default value of the host flag, which is the value that will be used
     * for the build setting in the exec configuration.
     * @param flagScopeType the scope type of the build setting, e.g. "exec:--host_foo"
     * @param hostFlagScopeType the scope type of the host flag, e.g. "default" or "target"
     */
    @AutoCodec
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    class CustomExecScopeValue(
        flag: com.google.devtools.build.lib.cmdline.Label?,
        flagDefault: Any?,
        hostFlag: com.google.devtools.build.lib.cmdline.Label?,
        hostFlagDefault: Any?,
        flagScopeType: String?,
        hostFlagScopeType: String?
    ) {
        val flag: com.google.devtools.build.lib.cmdline.Label?
        val flagDefault: Any?
        val hostFlag: com.google.devtools.build.lib.cmdline.Label?
        val hostFlagDefault: Any?
        val flagScopeType: String?
        val hostFlagScopeType: String?

        init {
            this.flag = flag
            this.flagDefault = flagDefault
            this.hostFlag = hostFlag
            this.hostFlagDefault = hostFlagDefault
            this.flagScopeType = flagScopeType
            this.hostFlagScopeType = hostFlagScopeType
        }
    }

    /** [SkyKey] implementation used for [StarlarkBuildSettingsDetailsValue].  */
    @com.google.errorprone.annotations.CheckReturnValue
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @AutoCodec
    class Key(
        buildSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?,
        hostFlags: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
    ) : SkyKey {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.STARLARK_BUILD_SETTINGS_DETAILS
        }

        val buildSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
        val hostFlags: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?

        init {
            this.hostFlags = hostFlags
            this.buildSettings = buildSettings
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>(
                buildSettings,
                "buildSettings"
            )
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>(
                hostFlags,
                "hostFlags"
            )
        }

        companion object {
            fun create(
                buildSettings: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?,
                hostFlags: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
            ): Key {
                return com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue.Key(
                    buildSettings,
                    hostFlags
                )
            }
        }
    }

    val buildSettingToDefault: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?
    val buildSettingToType: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>?
    val buildSettingIsAllowsMultiple: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?
    val aliasToActual: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>?
    val customExecScopeValues: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>?

    init {
        this.customExecScopeValues = customExecScopeValues
        this.aliasToActual = aliasToActual
        this.buildSettingIsAllowsMultiple = buildSettingIsAllowsMultiple
        this.buildSettingToType = buildSettingToType
        this.buildSettingToDefault = buildSettingToDefault
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>?>(
            buildSettingToDefault,
            "buildSettingToDefault"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>?>(
            buildSettingToType,
            "buildSettingToType"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?>?>(
            buildSettingIsAllowsMultiple,
            "buildSettingIsAllowsMultiple"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>?>(
            aliasToActual,
            "aliasToActual"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>?>(
            customExecScopeValues,
            "customExecScopeValues"
        )
    }

    companion object {
        /**
         * Create a single StarlarkBuildSettingsDetailsValue that can be quickly returned for transitions
         * that use no Starlark build settings
         */
        @kotlin.jvm.JvmField
        val EMPTY: StarlarkBuildSettingsDetailsValue = StarlarkBuildSettingsDetailsValue(
            com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, Any?>(),
            com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>(),
            com.google.common.collect.ImmutableSet.of<com.google.devtools.build.lib.cmdline.Label?>(),
            com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>(),
            com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>()
        )

        fun create(
            buildSettingDefaults: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?>,
            buildSettingToType: MutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>,
            buildSettingIsAllowsMultiple: MutableSet<com.google.devtools.build.lib.cmdline.Label?>,
            aliasToActual: MutableMap<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>,
            customExecScopeValues: MutableMap<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>
        ): StarlarkBuildSettingsDetailsValue {
            return StarlarkBuildSettingsDetailsValue(
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, Any?>(
                    buildSettingDefaults
                ),
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.packages.Type<*>?>(
                    buildSettingToType
                ),
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                    buildSettingIsAllowsMultiple
                ),
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, com.google.devtools.build.lib.cmdline.Label?>(
                    aliasToActual
                ),
                com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.build.lib.cmdline.Label?, CustomExecScopeValue?>(
                    customExecScopeValues
                )
            )
        }

        fun key(
            buildSettings: MutableSet<com.google.devtools.build.lib.cmdline.Label?>,
            hostFlags: MutableSet<com.google.devtools.build.lib.cmdline.Label?>
        ): Key {
            return com.google.devtools.build.lib.analysis.starlark.StarlarkBuildSettingsDetailsValue.Key.Companion.create(
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                    buildSettings
                ),
                com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.cmdline.Label?>(hostFlags)
            )
        }
    }
}
