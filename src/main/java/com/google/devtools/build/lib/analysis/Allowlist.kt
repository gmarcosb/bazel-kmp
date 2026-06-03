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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Class used for implementing allowlists using package groups.
 * 
 * 
 * To use add an attribute [to the rule class][]
 */
object Allowlist {
    /**
     * Returns an Attribute.Builder that can be used to add an implicit attribute to a rule containing
     * a package group allowlist.
     * 
     * @param allowlistName The name of the allowlist. This has to comply with attribute naming
     * standards and will be used as a suffix for the attribute name.
     */
    fun getAttributeFromAllowlistName(allowlistName: String?): Attribute.Builder<Label?> {
        val attributeName: String = getAttributeNameFromAllowlistName(allowlistName).iterator().next()
        return attr(attributeName, LABEL)
            .cfg(ExecutionTransitionFactory.Companion.createFactory())
            .mandatoryBuiltinProviders(com.google.common.collect.ImmutableList.of<E?>(PackageSpecificationProvider::class.java))
    }

    /**
     * Returns whether the rule in the given RuleContext *was defined* in a allowlist.
     * 
     * @param ruleContext The context in which this check is being executed.
     * @param allowlistName The name of the allowlist being used.
     */
    fun isAvailableBasedOnRuleLocation(
        ruleContext: RuleContext, allowlistName: String?
    ): Boolean {
        return isAvailableFor(
            ruleContext,
            allowlistName,
            ruleContext.getRule().getRuleClassObject().getRuleDefinitionEnvironmentLabel()
        )
    }

    /**
     * Returns whether the rule in the given RuleContext *was instantiated* in a allowlist.
     * 
     * @param ruleContext The context in which this check is being executed.
     * @param allowlistName The name of the allowlist being used.
     */
    fun isAvailable(ruleContext: RuleContext, allowlistName: String?): Boolean {
        return isAvailableFor(ruleContext, allowlistName, ruleContext.getLabel())
    }

    /**
     * @param ruleContext The context in which this check is being executed.
     * @param allowlistName The name of the allowlist being used.
     * @param relevantLabel The label to check for in the allowlist. This allows features that
     * allowlist on rule definition location and features that allowlist on rule instantiation
     * location to share logic.
     */
    fun isAvailableFor(
        ruleContext: RuleContext, allowlistName: String?, relevantLabel: Label
    ): Boolean {
        val packageSpecificationProvider: PackageSpecificationProvider =
            fetchPackageSpecificationProvider(ruleContext, allowlistName)
        return isAvailableFor(packageSpecificationProvider.getPackageSpecifications(), relevantLabel)
    }

    fun isAvailableFor(
        packageGroupContents: NestedSet<PackageGroupContents?>, relevantLabel: Label
    ): Boolean {
        return packageGroupContents.toList().stream()
            .anyMatch({ p -> p.containsPackage(relevantLabel.getPackageIdentifier()) })
    }

    fun fetchPackageSpecificationProvider(
        ruleContext: RuleContext, allowlistName: String?
    ): PackageSpecificationProvider {
        return com.google.common.base.Preconditions.checkNotNull<PackageSpecificationProvider>(
            fetchPackageSpecificationProviderOrNull(ruleContext, allowlistName),
            "Allowlist argument for %s not found",
            allowlistName
        )
    }

    fun fetchPackageSpecificationProviderOrNull(
        ruleContext: RuleContext, allowlistName: String?
    ): PackageSpecificationProvider? {
        for (attributeName in getAttributeNameFromAllowlistName(allowlistName)) {
            if (!ruleContext.isAttrDefined(attributeName, LABEL)) {
                continue
            }
            com.google.common.base.Preconditions.checkArgument(
                ruleContext.isAttrDefined(attributeName, LABEL),
                attributeName
            )
            val packageGroup: TransitiveInfoCollection? = ruleContext.getPrerequisite(attributeName)
            val packageSpecificationProvider: PackageSpecificationProvider? =
                packageGroup.get(PackageSpecificationProvider.Companion.PROVIDER)
            return requireNonNull(packageSpecificationProvider, packageGroup.getLabel().toString())
        }
        return null
    }

    /**
     * Returns whether the rule from the given rule context has a allowlist by the given name.
     * 
     * @param ruleContext The rule context to check
     * @param allowlistName The name of the allowlist to check for.
     * @return True if the given rule context has the given allowlist.
     */
    fun hasAllowlist(ruleContext: RuleContext, allowlistName: String?): Boolean {
        for (attributeName in getAttributeNameFromAllowlistName(allowlistName)) {
            if (ruleContext.isAttrDefined(attributeName, LABEL)) {
                return true
            }
        }
        return false
    }

    private fun getAttributeNameFromAllowlistName(allowlistName: String?): com.google.common.collect.ImmutableList<String> {
        return com.google.common.collect.ImmutableList.of<String?>(
            String.format("\$whitelist_%s", allowlistName),
            String.format("\$allowlist_%s", allowlistName)
        )
    }
}
