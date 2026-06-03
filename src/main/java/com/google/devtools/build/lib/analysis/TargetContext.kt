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

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * A helper class for building [ConfiguredTarget] instances, in particular for non-rule ones.
 * For [com.google.devtools.build.lib.analysis.configuredtargets.RuleConfiguredTarget]
 * instances, use [RuleContext] instead, which is a subclass of this class.
 * 
 * 
 * The class is intended to be sub-classed by RuleContext, in order to share the code. However,
 * it's not intended for sub-classing beyond that, and the constructor is intentionally package
 * private to enforce that.
 */
open class TargetContext internal constructor(
    env: AnalysisEnvironment,
    target: Target,
    configuration: BuildConfigurationValue?,
    directPrerequisites: MutableSet<ConfiguredTargetAndData?>?,
    visibility: NestedSet<PackageGroupContents?>?,
    transitiveVisibility: PackageSpecificationProvider?
) {
    private val env: AnalysisEnvironment
    private val target: Target
    private val configuration: BuildConfigurationValue?

    /**
     * This only contains prerequisites that are not declared in rule attributes, with the exception
     * of visibility (i.e., visibility is represented here, even though it is a rule attribute in case
     * of a rule). Rule attributes are handled by the [RuleContext] subclass.
     */
    private val directPrerequisites: com.google.common.collect.ListMultimap<Label, ConfiguredTargetAndData>

    private val visibility: NestedSet<PackageGroupContents?>?

    private val transitiveVisibilityImposedByThisPackage: PackageSpecificationProvider?

    /**
     * The constructor is intentionally package private.
     * 
     * 
     * directPrerequisites is expected to be ordered.
     */
    init {
        this.env = env
        this.target = target
        this.configuration = configuration
        this.directPrerequisites =
            com.google.common.collect.Multimaps.index(directPrerequisites, ConfiguredTargetAndData::getTargetLabel)
        this.visibility = visibility
        this.transitiveVisibilityImposedByThisPackage = transitiveVisibility
    }

    fun getAnalysisEnvironment(): AnalysisEnvironment {
        return env
    }

    fun getActionKeyContext(): ActionKeyContext? {
        return env.getActionKeyContext()
    }

    fun getTarget(): Target {
        return target
    }

    fun getLabel(): Label {
        return target.getLabel()
    }

    fun getPackageContext(): Label.PackageContext {
        return Label.PackageContext.of(
            getLabel().getPackageIdentifier(), target.getPackageMetadata().repositoryMapping()
        )
    }

    /**
     * Returns the configuration for this target. This may return null if the target is supposed to be
     * configuration-independent (like an input file, or a visibility rule). However, this is
     * guaranteed to be non-null for rules and for output files.
     */
    fun getConfiguration(): BuildConfigurationValue? {
        return configuration
    }

    fun getVisibility(): NestedSet<PackageGroupContents?>? {
        return visibility
    }

    fun getTransitiveVisibilityImposedByThisPackage(): PackageSpecificationProvider? {
        return transitiveVisibilityImposedByThisPackage
    }

    /**
     * Returns the prerequisite with the given label and configuration, or null if no such
     * prerequisite exists. If configuration is absent, return the first prerequisite with the given
     * label.
     */
    fun findDirectPrerequisite(
        label: Label?, config: java.util.Optional<BuildConfigurationValue?>
    ): TransitiveInfoCollection? {
        if (directPrerequisites.containsKey(label)) {
            val prerequisites: MutableList<ConfiguredTargetAndData> = directPrerequisites.get(label)
            // If the config is present, find the prereq with that configuration. Otherwise, return the
            // first.
            if (!config.isPresent()) {
                if (prerequisites.isEmpty()) {
                    return null
                }
                return com.google.common.collect.Iterables.getFirst<ConfiguredTargetAndData?>(prerequisites, null)
                    .getConfiguredTarget()
            }
            for (prerequisite in prerequisites) {
                if (com.google.common.base.Objects.equal(prerequisite.getConfiguration(), config.get())) {
                    return prerequisite.getConfiguredTarget()
                }
            }
        }
        return null
    }
}
