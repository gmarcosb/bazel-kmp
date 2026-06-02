// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

/**
 * Helper functions for visiting the [Label]s of the loading-phase deps of a [Target]
 * that are entailed by the values of the [Target]'s attributes. Notably, this does *not*
 * include aspect-entailed deps.
 */
object LabelVisitationUtils {
    // An attribute which symbolizes the "toolchains" parameter of rule class definitions
    // (user-specified via the `toolchains` parameter of the starlark rule() function). This is so
    // that labels specified in this `toolchains` parameter may be treated the same as dependencies
    // defined on an implicit rule attribute. This "fake" attribute uses an obscure placeholder name
    // to prevent dependencies on this implementation detail.
    private val TOOLCHAIN_TYPE_ATTR_FOR_FILTERING: com.google.devtools.build.lib.packages.Attribute? =
        com.google.devtools.build.lib.packages.Attribute.Companion.attr<MutableList<Label?>?>(
            "_hidden_toolchain_types",
            BuildType.LABEL_LIST
        )
            .allowedFileTypes(FileTypeSet.NO_FILE)
            .build()

    /**
     * Visits the loading-phase deps of `target` that satisfy `edgeFilter`, feeding each
     * one to `labelProcessor` in a streaming manner.
     */
    fun visitTarget(
        target: com.google.devtools.build.lib.packages.Target?,
        edgeFilter: DependencyFilter,
        labelProcessor: LabelProcessor
    ) {
        if (target is OutputFile) {
            labelProcessor.process(
                target,  /*attribute=*/null, (target as OutputFile).getGeneratingRule().getLabel()
            )
            visitTargetVisibility(target,  /*attribute=*/null, labelProcessor)
            return
        }

        if (target is InputFile) {
            visitTargetVisibility(target,  /*attribute=*/null, labelProcessor)
            return
        }

        if (target is com.google.devtools.build.lib.packages.Rule) {
            visitRuleVisibility(target, edgeFilter, labelProcessor)
            visitRule(target, edgeFilter, labelProcessor)
            visitRuleToolchains(target, edgeFilter, labelProcessor)
            return
        }

        if (target is PackageGroup) {
            visitPackageGroup(target as PackageGroup, labelProcessor)
        }
    }

    private fun visitTargetVisibility(
        target: com.google.devtools.build.lib.packages.Target,
        attribute: com.google.devtools.build.lib.packages.Attribute?,
        labelProcessor: LabelProcessor
    ) {
        for (label in target.getVisibilityDependencyLabels()) {
            labelProcessor.process(target, attribute, label)
        }
    }

    private fun visitRuleVisibility(
        rule: com.google.devtools.build.lib.packages.Rule, edgeFilter: DependencyFilter, labelProcessor: LabelProcessor
    ) {
        val ruleClass: RuleClass = rule.getRuleClassObject()
        val index: Int? = ruleClass.getAttributeProvider().getAttributeIndex("visibility")
        if (index == null) {
            return
        }
        val visibilityAttribute: com.google.devtools.build.lib.packages.Attribute =
            ruleClass.getAttributeProvider().getAttribute(index)
        if (visibilityAttribute.getType() !== BuildType.NODEP_LABEL_LIST) {
            return
        }
        if (edgeFilter.test(rule, visibilityAttribute)) {
            visitTargetVisibility(rule, visibilityAttribute, labelProcessor)
        }
    }

    private fun visitRuleToolchains(
        rule: com.google.devtools.build.lib.packages.Rule, edgeFilter: DependencyFilter, labelProcessor: LabelProcessor
    ) {
        val ruleClass: RuleClass = rule.getRuleClassObject()
        if (edgeFilter.test(rule, TOOLCHAIN_TYPE_ATTR_FOR_FILTERING)) {
            for (t in ruleClass.getToolchainTypes()) {
                labelProcessor.process(rule, TOOLCHAIN_TYPE_ATTR_FOR_FILTERING, t.toolchainType())
            }
        }
    }

    private fun visitRule(
        rule: com.google.devtools.build.lib.packages.Rule?,
        edgeFilter: DependencyFilter?,
        labelProcessor: LabelProcessor
    ) {
        AggregatingAttributeMapper.Companion.of(rule)
            .visitLabels(
                edgeFilter,
                com.google.devtools.build.lib.packages.Type.LabelVisitor { label: Label?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                    if (label == null) {
                        return@visitLabels
                    }
                    labelProcessor.process(rule, attribute, label)
                })
    }

    private fun visitPackageGroup(packageGroup: PackageGroup, labelProcessor: LabelProcessor) {
        for (label in packageGroup.getIncludes()) {
            labelProcessor.process(packageGroup,  /*attribute=*/null, label)
        }
    }

    /** Interface for processing the [Label] of dep, one at a time.  */
    interface LabelProcessor {
        /**
         * Processes the [Label] of a single dep.
         * 
         * @param from the [Target] that has the dep.
         * @param attribute if non-`null`, the [Attribute] whose value entailed the dep.
         * @param to the [Label] of the dep.
         */
        fun process(
            from: com.google.devtools.build.lib.packages.Target?,
            attribute: com.google.devtools.build.lib.packages.Attribute?,
            to: Label?
        )
    }
}
