// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.AbridgedModule
import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionUsagesValue
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * A simple SkyFunction that takes the information needed by a [SingleExtensionEvalFunction]
 * out of [BazelModuleResolutionValue] and stores it in a SkyValue.
 * 
 * 
 * The whole raison d'être of this function is to avoid unnecessary reruns of module extensions.
 * Whenever any information in the whole dependency graph changes, [ ] is rerun, producing a new [BazelModuleResolutionValue]. If
 * [SingleExtensionEvalFunction] were to directly depend on [ ], any such change would cause ALL module extensions to be rerun.
 * Instead, by storing the input needed by a single [SingleExtensionEvalFunction], we can rely
 * on Skyframe's change pruning feature to make sure that we only rerun the module extension whose
 * input data actually changed.
 */
class SingleExtensionUsagesFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val bazelDepGraphValue: BazelDepGraphValue? =
            env.getValue(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue?
        if (bazelDepGraphValue == null) {
            return null
        }

        val id: ModuleExtensionId? = skyKey.argument() as ModuleExtensionId?
        // We never request an extension without usages in Skyframe.
        val usagesTable: com.google.common.collect.ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?> =
            bazelDepGraphValue.getExtensionUsagesTable()
        return SingleExtensionUsagesValue.Companion.create(
            usagesTable.row(id),
            bazelDepGraphValue.getExtensionUniqueNames()
                .get(id),  // Filter abridged modules down to only those that actually used this extension.
            bazelDepGraphValue.getAbridgedModules().stream()
                .filter(java.util.function.Predicate { module: AbridgedModule? ->
                    usagesTable.contains(
                        id,
                        module.getKey()
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<AbridgedModule?>()),  // TODO(wyv): Maybe cache these mappings?
            usagesTable.row(id).keySet().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<ModuleKey?, ModuleKey?, com.google.devtools.build.lib.cmdline.RepositoryMapping?>(
                        java.util.function.Function { key: ModuleKey? -> key },
                        java.util.function.Function { key: ModuleKey? -> bazelDepGraphValue.getFullRepoMapping(key) })
                ),
            bazelDepGraphValue.getRepoOverrides().row(id)
        )
    }
}
