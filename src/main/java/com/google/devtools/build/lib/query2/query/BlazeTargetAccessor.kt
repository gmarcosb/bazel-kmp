// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * Implementation of [&amp;lt;Target&amp;gt;][TargetAccessor] that uses an
 * [&amp;lt;Target&amp;gt;][AbstractBlazeQueryEnvironment] internally to report issues and resolve
 * targets.
 */
class BlazeTargetAccessor(queryEnvironment: AbstractBlazeQueryEnvironment<Target?>) : TargetAccessor<Target?> {
    private val queryEnvironment: AbstractBlazeQueryEnvironment<Target?>

    init {
        this.queryEnvironment = queryEnvironment
    }

    override fun getTargetKind(target: Target): String {
        return target.getTargetKind()
    }

    override fun getLabel(target: Target): String {
        return target.getLabel().toString()
    }

    override fun getPackage(target: Target): String {
        return target.getPackageMetadata().getName()
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getPrerequisites(
        caller: QueryExpression?, target: Target?, attrName: String?, errorMsgPrefix: String?
    ): Iterable<Target?> {
        com.google.common.base.Preconditions.checkArgument(target is Rule)

        val rule: Rule? = target as Rule?

        val attrMap: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
        val attrType: Type<*>? = attrMap.getAttributeType(attrName)
        if (attrType == null) {
            // Return an empty list if the attribute isn't defined for this rule.
            return com.google.common.collect.ImmutableList.of<Target?>()
        }

        val labels: MutableSet<Label?> = attrMap.getReachableLabels(attrName, false)
        // TODO(nharmata): Figure out how to make use of the package semaphore in the transitive
        // callsites of this method.
        val labelTargetMap: MutableMap<Label?, Target?> = queryEnvironment.getTargets(labels)
        // Optimize for the common-case of no missing targets.
        if (labelTargetMap.size() != labels.size()) {
            for (label in labels) {
                if (!labelTargetMap.containsKey(label)) {
                    // If a target was missing, fetch it directly for the sole purpose of getting a useful
                    // error message.
                    try {
                        queryEnvironment.getTarget(label)
                    } catch (e: TargetNotFoundException) {
                        queryEnvironment.handleError(
                            caller, errorMsgPrefix + e.getMessage(), e.getDetailedExitCode()
                        )
                    }
                }
            }
        }
        return labelTargetMap.values()
    }

    override fun getStringListAttr(target: Target?, attrName: String?): MutableList<String?> {
        return TargetUtils.getStringListAttr(target, attrName)
    }

    override fun getStringAttr(target: Target?, attrName: String?): String {
        return TargetUtils.getStringAttr(target, attrName)
    }

    override fun getAttrAsString(target: Target?, attrName: String?): Iterable<String?> {
        return TargetUtils.getAttrAsString(target, attrName)
    }

    override fun isRule(target: Target?): Boolean {
        return target is Rule
    }

    override fun isExecutableNonTestRule(target: Target?): Boolean {
        return TargetUtils.isExecutableNonTestRule(target)
    }

    override fun isTestRule(target: Target?): Boolean {
        return TargetUtils.isTestRule(target)
    }

    override fun isTestSuite(target: Target?): Boolean {
        return TargetUtils.isTestSuiteRule(target)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getVisibility(
        caller: QueryExpression?,
        target: Target
    ): com.google.common.collect.ImmutableSet<QueryVisibility<Target?>?> {
        val result: com.google.common.collect.ImmutableSet.Builder<QueryVisibility<Target?>?> =
            com.google.common.collect.ImmutableSet.builder<QueryVisibility<Target?>?>()
        result.add(QueryVisibility.Companion.samePackage<Target?>(target, this))
        convertVisibility(caller, result, target)
        return result.build()
    }

    // CAUTION: keep in sync with ConfiguredTargetFactory#convertVisibility()
    // TODO: #19922 - And... it's not in sync with Macro-Aware Visibility for symbolic macros. Fix
    // this. Also mind the samePackage logic in getVisibility above.
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    private fun convertVisibility(
        caller: QueryExpression?,
        packageSpecifications: com.google.common.collect.ImmutableSet.Builder<QueryVisibility<Target?>?>,
        target: Target
    ) {
        val ruleVisibility: RuleVisibility = target.getVisibility()
        if (ruleVisibility.equals(RuleVisibility.PRIVATE)) {
            return
        }
        if (ruleVisibility.equals(RuleVisibility.PUBLIC)) {
            packageSpecifications.add(QueryVisibility.Companion.everything<Target?>())
        } else if (ruleVisibility is PackageGroupsRuleVisibility) {
            for (groupLabel in ruleVisibility.getPackageGroups()) {
                try {
                    addAllPackageGroups(groupLabel, packageSpecifications)
                } catch (e: TargetNotFoundException) {
                    queryEnvironment.handleError(
                        caller,
                        "Invalid visibility label '" + groupLabel.getCanonicalForm() + "': " + e.getMessage(),
                        e.getDetailedExitCode()
                    )
                }
            }
            packageSpecifications.add(
                BlazeQueryVisibility(ruleVisibility.getDirectPackages())
            )
        } else {
            throw java.lang.IllegalStateException("unknown visibility: " + ruleVisibility.getClass())
        }
    }

    /**
     * If `groupLabel` refers to a `package_group`, recursively add the package
     * specifications of it and of all other `package_group`s transitively in its `includes`.
     */
    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        TargetNotFoundException::class,
        java.lang.InterruptedException::class
    )
    private fun addAllPackageGroups(
        groupLabel: Label?,
        packageSpecifications: com.google.common.collect.ImmutableSet.Builder<QueryVisibility<Target?>?>
    ) {
        addAllPackageGroupsRecursive(groupLabel, packageSpecifications, HashSet<Label?>())
    }

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        TargetNotFoundException::class,
        java.lang.InterruptedException::class
    )
    private fun addAllPackageGroupsRecursive(
        groupLabel: Label?,
        packageSpecifications: com.google.common.collect.ImmutableSet.Builder<QueryVisibility<Target?>?>,
        seen: MutableSet<Label?>
    ) {
        if (!seen.add(groupLabel)) {
            // Avoid infinite recursion in case of an illegal package_group that includes itself.
            // The target can't be built, but we'll return a valid result that just ignores the cyclic
            // reference.
            return
        }
        val groupTarget: Target? = queryEnvironment.getTarget(groupLabel)
        if (groupTarget is PackageGroup) {
            for (include in groupTarget.getIncludes()) {
                addAllPackageGroupsRecursive(include, packageSpecifications, seen)
            }
            packageSpecifications.add(
                BlazeQueryVisibility(groupTarget.getPackageSpecifications())
            )
        }
    }
}
