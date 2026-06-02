// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.platform

import com.google.devtools.build.lib.analysis.ProviderCollection
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import com.google.devtools.build.lib.analysis.platform.DeclaredToolchainInfo
import com.google.devtools.build.lib.analysis.platform.ToolchainInfo
import com.google.devtools.build.lib.analysis.platform.ToolchainTypeInfo

/** Utility methods to help locate platform-related providers.  */
object PlatformProviderUtils {
    /** Retrieves and casts the [PlatformInfo] provider from the given target.  */
    fun platform(target: ProviderCollection?): com.google.devtools.build.lib.analysis.platform.PlatformInfo? {
        if (target == null) {
            return null
        }
        return target.get<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>(com.google.devtools.build.lib.analysis.platform.PlatformInfo.Companion.PROVIDER)
    }

    /** Retrieves and casts [PlatformInfo] providers from the given targets.  */
    fun platforms(targets: MutableList<out ProviderCollection?>): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.analysis.platform.PlatformInfo?> {
        return targets.stream()
            .map<com.google.devtools.build.lib.analysis.platform.PlatformInfo?> { obj: PlatformProviderUtils?, target: ProviderCollection? ->
                platform(
                    target
                )
            }
            .filter(com.google.common.base.Predicates.notNull<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>())
            .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.build.lib.analysis.platform.PlatformInfo?>())
    }

    /** Retrieves and casts the [ConstraintSettingInfo] provider from the given target.  */
    fun constraintSetting(target: ProviderCollection?): ConstraintSettingInfo? {
        if (target == null) {
            return null
        }
        return target.get<ConstraintSettingInfo?>(ConstraintSettingInfo.Companion.PROVIDER)
    }

    /** Retrieves and casts the [ConstraintValueInfo] provider from the given target.  */
    fun constraintValue(target: ProviderCollection?): ConstraintValueInfo? {
        if (target == null) {
            return null
        }
        return target.get<ConstraintValueInfo?>(ConstraintValueInfo.Companion.PROVIDER)
    }

    /** Returns if a target provides [ConstraintValueInfo]. *  */
    fun hasConstraintValue(target: ProviderCollection): Boolean {
        return target.get<ConstraintValueInfo?>(ConstraintValueInfo.Companion.PROVIDER) != null
    }

    /** Retrieves and casts [ConstraintValueInfo] providers from the given targets.  */
    fun constraintValues(
        targets: MutableList<out ProviderCollection?>
    ): com.google.common.collect.ImmutableList<ConstraintValueInfo?> {
        return targets.stream()
            .map<ConstraintValueInfo?> { obj: PlatformProviderUtils?, target: ProviderCollection? ->
                constraintValue(
                    target
                )
            }
            .filter(com.google.common.base.Predicates.notNull<ConstraintValueInfo?>())
            .collect(com.google.common.collect.ImmutableList.toImmutableList<ConstraintValueInfo?>())
    }

    /**
     * Retrieves and casts the [DeclaredToolchainInfo] from [ ] rule.
     * 
     * 
     * Returns null if the rule isn't a toolchain.
     */
    fun declaredToolchainInfo(target: ProviderCollection?): DeclaredToolchainInfo? {
        if (target == null) {
            return null
        }
        return target.getProvider<DeclaredToolchainInfo?>(DeclaredToolchainInfo::class.java)
    }

    /** Retrieves and casts the [ToolchainInfo] provider from the given target.  */
    fun toolchain(target: ProviderCollection?): ToolchainInfo? {
        if (target == null) {
            return null
        }
        return target.get<ToolchainInfo?>(ToolchainInfo.Companion.PROVIDER)
    }

    /** Retrieves and casts the [ToolchainTypeInfo] provider from the given target.  */
    fun toolchainType(target: ProviderCollection): ToolchainTypeInfo? {
        return target.get<ToolchainTypeInfo?>(ToolchainTypeInfo.Companion.PROVIDER)
    }
}
