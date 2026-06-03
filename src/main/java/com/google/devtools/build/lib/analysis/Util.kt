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

import com.google.devtools.build.lib.analysis.Util.getWorkspaceRelativePath
import com.google.devtools.build.lib.cmdline.Label

/** Utility methods for use by ConfiguredTarget implementations.  */
object Util {
    // ---------- Label and Target related methods
    /**
     * Returns the workspace-relative path of the specified target (file or rule).
     * 
     * 
     * For example, "//foo/bar:wiz" and "//foo:bar/wiz" both result in "foo/bar/wiz".
     */
    fun getWorkspaceRelativePath(target: Target): PathFragment? {
        return getWorkspaceRelativePath(target.getLabel())
    }

    /**
     * Returns the workspace-relative path of the specified target (file or rule).
     * 
     * 
     * For example, "//foo/bar:wiz" and "//foo:bar/wiz" both result in "foo/bar/wiz".
     */
    fun getWorkspaceRelativePath(label: Label): PathFragment {
        return label.getPackageFragment().getRelative(label.getName())
    }

    /**
     * Returns the workspace-relative path of the specified target (file or rule), prepending a prefix
     * and appending a suffix.
     * 
     * 
     * For example, "//foo/bar:wiz" and "//foo:bar/wiz" both result in "foo/bar/wiz".
     */
    fun getWorkspaceRelativePath(target: Target, prefix: String?, suffix: String?): PathFragment {
        return target.getLabel().getPackageFragment().getRelative(prefix + target.getName() + suffix)
    }

    /** Checks if a PathFragment contains a '-'.  */
    fun containsHyphen(path: PathFragment): Boolean {
        return path.getPathString().indexOf('-') >= 0
    }

    // ---------- Implicit dependency extractor
    /**
     * Given a RuleContext, find all the implicit attribute deps aka deps that weren't explicitly set
     * in the build file but are attached behind the scenes to some attribute. This means this
     * function does *not* cover deps attached other ways e.g. toolchain-related implicit deps (see
     * [PostAnalysisQueryEnvironment.targetifyValues] for more info on further implicit deps
     * filtering). note: nodes that are depended on both implicitly and explicitly are considered
     * explicit.
     */
    fun findImplicitDeps(ruleContext: RuleContext): com.google.common.collect.ImmutableList<ConfiguredTargetKey?> {
        val maybeImplicitDeps: MutableSet<ConfiguredTargetKey?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<ConfiguredTargetKey?>()
        val explicitDeps: MutableSet<ConfiguredTargetKey?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<ConfiguredTargetKey?>()
        // Consider rule attribute dependencies.
        val attributes: AttributeMap = ruleContext.attributes()
        for (attrName in attributes.getAttributeNames()) {
            val attrValues: MutableList<ConfiguredTargetAndData>? =
                ruleContext.getPrerequisiteConfiguredTargets(attrName)
            if (attrValues != null && !attrValues.isEmpty()) {
                if (attributes.isAttributeValueExplicitlySpecified(attrName)) {
                    com.google.devtools.build.lib.analysis.Util.addLabelsAndConfigs(explicitDeps, attrValues)
                } else {
                    com.google.devtools.build.lib.analysis.Util.addLabelsAndConfigs(maybeImplicitDeps, attrValues)
                }
            }
        }

        if (ruleContext.getRule().useToolchainResolution()) {
            // Rules that participate in toolchain resolution implicitly depend on the target platform to
            // check whether it matches the constraints in the target_compatible_with attribute.
            if (ruleContext.getConfiguration().hasFragment<T?>(PlatformConfiguration::class.java)) {
                val platformConfiguration: PlatformConfiguration =
                    ruleContext.getConfiguration().getFragment<T>(PlatformConfiguration::class.java)
                maybeImplicitDeps.add(
                    ConfiguredTargetKey.builder()
                        .setLabel(platformConfiguration.getTargetPlatform())
                        .setConfigurationKey(BuildConfigurationKey.create(CommonOptions.EMPTY_OPTIONS))
                        .build()
                )
            }
        }

        val toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>? =
            ruleContext.getToolchainContexts()
        if (toolchainContexts != null) {
            for (toolchainContext in toolchainContexts.contextMap().values()) {
                if (toolchainContext != null) {
                    // This logic should stay up to date with the dep creation logic in
                    // DependencyResolver#partiallyResolveDependencies.
                    val targetConfiguration: BuildConfigurationValue? = ruleContext.getConfiguration()
                    for (toolchain in toolchainContext.resolvedToolchainLabels()) {
                        maybeImplicitDeps.add(
                            ConfiguredTargetKey.builder()
                                .setLabel(toolchain)
                                .setConfiguration(targetConfiguration)
                                .setExecutionPlatformLabel(toolchainContext.executionPlatform().label())
                                .build()
                        )
                    }
                }
            }
        }
        return com.google.common.collect.ImmutableList.sortedCopyOf<ConfiguredTargetKey?>(
            ConfiguredTargetKey.ORDERING,
            com.google.common.collect.Sets.difference<ConfiguredTargetKey?>(maybeImplicitDeps, explicitDeps)
        )
    }

    private fun addLabelsAndConfigs(
        set: MutableSet<ConfiguredTargetKey?>, deps: MutableList<ConfiguredTargetAndData>
    ) {
        for (dep in deps) {
            // Dereference any aliases that might be present.
            set.add(
                ConfiguredTargetKey.builder()
                    .setLabel(dep.getConfiguredTarget().getOriginalLabel())
                    .setConfiguration(dep.getConfiguration())
                    .build()
            )
        }
    }
}
