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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/** A rule visibility that allows visibility to a list of package groups.  */
@AutoValue
abstract class PackageGroupsRuleVisibility : RuleVisibility() {
    abstract fun getPackageGroups(): com.google.common.collect.ImmutableList<Label?>?

    abstract fun getDirectPackages(): PackageGroupContents?

    abstract override fun getDeclaredLabels(): com.google.common.collect.ImmutableList<Label?>?

    override fun getDependencyLabels(): com.google.common.collect.ImmutableList<Label?>? {
        return getPackageGroups()
    }

    companion object {
        /**
         * Creates a [PackageGroupsRuleVisibility] from a non-empty list of labels, which must have
         * been previously validated and simplified by [RuleVisibility.validateAndSimplify], and
         * which must not be ["//visibility:public"] or ["//visibility:private"].
         * 
         * 
         * To parse a public or private visibility, use [RuleVisibility.parseIfConstant].
         */
        fun create(labels: MutableList<Label>): PackageGroupsRuleVisibility {
            val directPackageBuilder: com.google.common.collect.ImmutableList.Builder<PackageSpecification?> =
                com.google.common.collect.ImmutableList.builder<PackageSpecification?>()
            val packageGroupBuilder: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()

            com.google.common.base.Preconditions.checkArgument(!labels.isEmpty(), "labels must not be empty")
            for (label in labels) {
                val specification: PackageSpecification? = PackageSpecification.Companion.fromLabel(label)
                if (specification != null) {
                    directPackageBuilder.add(specification)
                } else {
                    com.google.common.base.Preconditions.checkArgument(
                        !label.equals(RuleVisibility.Companion.PUBLIC_LABEL)
                                && !label.equals(RuleVisibility.Companion.PRIVATE_LABEL),
                        "labels list %s must %s",
                        labels,
                        if (labels.size() == 1)
                            "not equal [\"//visibility:public\"] or [\"//visibility:private\"]"
                        else
                            "be validated and simplified"
                    )
                    packageGroupBuilder.add(label)
                }
            }

            return AutoValue_PackageGroupsRuleVisibility(
                packageGroupBuilder.build(),
                PackageGroupContents.Companion.create(directPackageBuilder.build()),
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (labels)
            )
        }
    }
}
