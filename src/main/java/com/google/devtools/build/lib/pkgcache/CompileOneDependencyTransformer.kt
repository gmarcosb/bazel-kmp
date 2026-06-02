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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.analysis.config.FeatureSet

/** Implementation of --compile_one_dependency.  */
class CompileOneDependencyTransformer(packageProvider: PackageProvider) {
    private val packageProvider: PackageProvider

    init {
        this.packageProvider = packageProvider
    }

    /**
     * For each input file in the original result, returns a rule in the same package which has the
     * input file as a source.
     */
    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    fun transformCompileOneDependency(
        eventHandler: ExtendedEventHandler?, original: ResolvedTargets<com.google.devtools.build.lib.packages.Target?>
    ): ResolvedTargets<com.google.devtools.build.lib.packages.Target?> {
        if (original.hasError()) {
            return original
        }
        val builder: ResolvedTargets.Builder<com.google.devtools.build.lib.packages.Target?> = ResolvedTargets.builder()
        for (target in original.getTargets()) {
            builder.add(transformCompileOneDependency(eventHandler, target))
        }
        return builder.build()
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    private fun transformCompileOneDependency(
        eventHandler: ExtendedEventHandler?,
        target: com.google.devtools.build.lib.packages.Target
    ): com.google.devtools.build.lib.packages.Target {
        if (target !is FileTarget) {
            throw TargetParsingException(
                "--compile_one_dependency target '" + target.getLabel() + "' must be a file",
                TargetPatterns.Code.TARGET_MUST_BE_A_FILE
            )
        }

        var result: com.google.devtools.build.lib.packages.Rule? = null
        val orderedRuleList: Iterable<com.google.devtools.build.lib.packages.Rule>?
        try {
            orderedRuleList = getOrderedRuleList(packageProvider.getPackage(eventHandler, target))
        } catch (e: NoSuchPackageException) {
            // Only possible if lazy macro expansion is enabled, and an error was encountered when loading
            // a different package piece of this package.
            throw TargetParsingException(
                ("Package of --compile_one_dependency target '"
                        + target.getLabel()
                        + "' could not be loaded"),
                e,
                e.getDetailedExitCode()
            )
        }
        for (rule in orderedRuleList) {
            val labels: MutableSet<Label?> = getInputLabels(rule)
            if (listContainsFile(eventHandler, labels, target.getLabel(), HashSet<Label?>())) {
                if (PREFERRED_RULES
                        .getOrDefault(rule.getRuleClass(), com.google.common.base.Predicates.alwaysFalse<String?>())
                        .apply(target.getName())
                ) {
                    result = rule
                    break
                }
                if (result == null) {
                    result = rule
                }
            }
        }

        if (result == null) {
            throw TargetParsingException(
                "Couldn't find dependency on target '" + target.getLabel() + "'",
                TargetPatterns.Code.DEPENDENCY_NOT_FOUND
            )
        }

        // We want a rule where some action processes the input.
        // We should avoid cc_library rules that describe a set of headers but don't compile them.

        // If parse_headers is (probably) enabled, then this rule has a CppCompileHeader action.
        if (hasParseHeadersHeuristic(result)) {
            return result
        }
        // If the rule has source targets, return it: one of those sources will parse the header.
        if (result.getRuleClassObject().getAttributeProvider().hasAttr("srcs", BuildType.LABEL_LIST)
            && !RawAttributeMapper.Companion.of(result).getMergedValues<Label?>("srcs", BuildType.LABEL_LIST).isEmpty()
        ) {
            return result
        }

        // Else, find a rule in the same package that has 'result' as a dependency.
        for (rule in orderedRuleList) {
            val attributes: RawAttributeMapper = RawAttributeMapper.Companion.of(rule)
            // We don't know which path to follow for configurable attributes, so skip them.
            if (attributes.isConfigurable("deps") || attributes.isConfigurable("srcs")) {
                continue
            }
            val ruleClass: RuleClass = rule.getRuleClassObject()
            if (ruleClass.getAttributeProvider().hasAttr("deps", BuildType.LABEL_LIST)
                && ruleClass.getAttributeProvider().hasAttr("srcs", BuildType.LABEL_LIST)
            ) {
                for (dep in attributes.get<MutableList<Label>?>("deps", BuildType.LABEL_LIST)) {
                    if (dep.equals(result.getLabel())) {
                        if (!attributes.get<MutableList<Label?>?>("srcs", BuildType.LABEL_LIST).isEmpty()) {
                            return rule
                        }
                    }
                }
            }
        }

        return result
    }

    fun hasParseHeadersHeuristic(rule: com.google.devtools.build.lib.packages.Rule): Boolean {
        // We want to know whether the "parse_headers" toolchain feature is enabled or disabled.
        // At load time we can't really know, so check for the common static configuration sources.
        // (We ignore parse_headers being disabled through the toolchain & by rule implementations).

        var mergedFeatures: FeatureSet = rule.getPackageDeclarations().getPackageArgs().features()
        val ruleAttrs: RawAttributeMapper = RawAttributeMapper.Companion.of(rule)
        if (ruleAttrs.has<MutableList<String?>?>(
                "features",
                com.google.devtools.build.lib.packages.Types.STRING_LIST
            ) && !ruleAttrs.isConfigurable("features")
        ) {
            val ruleFeatures: FeatureSet? = FeatureSet.parse(
                ruleAttrs.get<T?>(
                    "features",
                    com.google.devtools.build.lib.packages.Types.STRING_LIST
                )
            )
            mergedFeatures = FeatureSet.merge(mergedFeatures, ruleFeatures)
        }

        if (mergedFeatures.on().contains("parse_headers")) {
            return true
        }
        if (mergedFeatures.off().contains("parse_headers")) {
            return false
        }

        // We assume parse_headers is on globally, unless disabled locally.
        return true
    }

    /**
     * Returns a list of rules in the given package sorted by BUILD file order. When multiple rules
     * depend on a target, we choose the first match in this list (after filtering for preferred
     * dependencies - see below).
     */
    private fun getOrderedRuleList(pkg: com.google.devtools.build.lib.packages.Package): Iterable<com.google.devtools.build.lib.packages.Rule> {
        val orderedList: MutableList<com.google.devtools.build.lib.packages.Rule> =
            java.util.ArrayList<com.google.devtools.build.lib.packages.Rule>()
        for (rule in pkg.getTargets<com.google.devtools.build.lib.packages.Rule?>(com.google.devtools.build.lib.packages.Rule::class.java)) {
            orderedList.add(rule)
        }

        Collections.sort<com.google.devtools.build.lib.packages.Rule?>(
            orderedList,
            java.util.Comparator.comparing<com.google.devtools.build.lib.packages.Rule?, net.starlark.java.syntax.Location?>(
                java.util.function.Function { arg: com.google.devtools.build.lib.packages.Rule? -> arg.getLocation() })
        )
        return orderedList
    }

    /**
     * Returns true if a specific rule compiles a specific source. Looks through genrules and
     * filegroups.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun listContainsFile(
        eventHandler: ExtendedEventHandler?,
        srcLabels: MutableCollection<Label?>,
        source: Label?,
        visitedRuleLabels: MutableSet<Label?>
    ): Boolean {
        if (srcLabels.contains(source)) {
            return true
        }
        for (label in srcLabels) {
            if (!visitedRuleLabels.add(label)) {
                continue
            }

            var target: com.google.devtools.build.lib.packages.Target? = null
            try {
                target = packageProvider.getTarget(eventHandler, label)
            } catch (e: NoSuchThingException) {
                // Just ignore failing sources/packages. We could report them here, but as long as we do
                // early return, the presence of this error would then be determined by the order of items
                // in the srcs attribute. A proper error will be created by the subsequent loading.
            }

            if (target == null || target is FileTarget) {
                continue
            }
            val targetRule: com.google.devtools.build.lib.packages.Rule? = target.getAssociatedRule()
            if (targetRule == null) {
                continue
            }

            if ("filegroup" == targetRule.getRuleClass()) {
                val attributeMapper: RawAttributeMapper = RawAttributeMapper.Companion.of(targetRule)
                val srcs: MutableCollection<Label?>? =
                    attributeMapper.getMergedValues<Label?>("srcs", BuildType.LABEL_LIST)
                if (listContainsFile(eventHandler, srcs, source, visitedRuleLabels)) {
                    return true
                }
            } else if ("genrule" == targetRule.getRuleClass()) {
                // TODO(djasper): Likely, it makes much more sense to look at the inputs of a genrule.
                for (file in targetRule.getOutputFiles()) {
                    if (file.getLabel().equals(source)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    companion object {
        private val CC_FILE_TYPE: com.google.devtools.build.lib.util.FileType =
            com.google.devtools.build.lib.util.FileType.of(".cc", ".h", ".c")
        private val JAVA_FILE_TYPE: com.google.devtools.build.lib.util.FileType =
            com.google.devtools.build.lib.util.FileType.of(".java")
        private val PYTHON_FILE_TYPE: com.google.devtools.build.lib.util.FileType =
            com.google.devtools.build.lib.util.FileType.of(".py")

        private val PREFERRED_RULES: com.google.common.collect.ImmutableMap<String?, com.google.common.base.Predicate<String?>?> =
            com.google.common.collect.ImmutableMap.of<String?, com.google.common.base.Predicate<String?>?>(
                "cc_library",
                CC_FILE_TYPE,
                "cc_binary",
                CC_FILE_TYPE,
                "cc_test",
                CC_FILE_TYPE,
                "java_library",
                JAVA_FILE_TYPE,
                "py_library",
                PYTHON_FILE_TYPE
            )

        /** Returns all labels that are contained in direct compile time inputs of `rule`.  */
        private fun getInputLabels(rule: com.google.devtools.build.lib.packages.Rule): MutableSet<Label?> {
            val attributeMapper: RawAttributeMapper = RawAttributeMapper.Companion.of(rule)
            val labels: MutableSet<Label?> = TreeSet<Label?>()
            for (attrName in attributeMapper.getAttributeNames()) {
                if (!attributeMapper.getAttributeDefinition(attrName).isDirectCompileTimeInput()) {
                    continue
                }
                // TODO(djasper): We might also want to look at LABEL types, but there currently is the
                // attribute xcode_config, which leads to test errors in Bazel tests.
                if (rule.isAttrDefined(attrName, BuildType.LABEL_LIST)) {
                    labels.addAll(attributeMapper.getMergedValues<Label?>(attrName, BuildType.LABEL_LIST))
                }
            }
            return labels
        }
    }
}
