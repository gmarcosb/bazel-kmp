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
package com.google.devtools.build.lib.starlarkbuildapi.cpp

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.starlarkbuildapi.core.Bootstrap
import net.starlark.java.eval.Starlark

/** [Bootstrap] for Starlark objects related to cpp rules.  */
class CcBootstrap(ccModule: CcModuleApi<out StarlarkActionFactoryApi?, out FileApi?, out FeatureConfigurationApi?, out CcToolchainVariablesApi?, out ConstraintValueInfoApi?, out StarlarkRuleContextApi<out ConstraintValueInfoApi?>?>?) :
    Bootstrap {
    override fun addBindingsToBuilder(builder: ImmutableMap.Builder<String?, Any?>) {
        builder.put(
            "cc_common",
            ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
                BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
                Starlark.NONE,
                allowedRepositories
            )
        )
        builder.put(
            "CcToolchainConfigInfo",
            ContextAndFlagGuardedValue.onlyInAllowedReposOrWhenIncompatibleFlagIsFalse(
                BuildLanguageOptions.INCOMPATIBLE_STOP_EXPORTING_LANGUAGE_MODULES,
                Starlark.NONE,
                allowedRepositories
            )
        )
    }

    companion object {
        private val allowedRepositories: ImmutableSet<PackageIdentifier?> = ImmutableSet.of<E?>(
            PackageIdentifier.createUnchecked("_builtins", ""),
            PackageIdentifier.createUnchecked("bazel_tools", ""),
            PackageIdentifier.createUnchecked("local_config_cc", ""),
            PackageIdentifier.createUnchecked("rules_cc", ""),
            PackageIdentifier.createUnchecked("", "tools/build_defs/cc")
        )
    }
}
