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

import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder
import com.google.devtools.build.lib.packages.PackageSpecification
import com.google.devtools.build.lib.packages.PackageSpecification.PackageGroupContents

/**
 * Provider class for configured targets that have a visibility.
 * 
 * 
 * This is the analysis-time equivalent of the visibility attribute, with package groups
 * recursively expanded. This provider also tracks a bit indicating whether the target was created
 * in a symbolic macro, which is not necessarily otherwise available in the prerequisite object at
 * analysis time.
 * 
 * 
 * The contents of this provider are determined in [ ][ConfiguredTargetFactory.convertVisibility]. It is consumed by the visibility check in [ ][CommonPrerequisiteValidator.isVisibleToLocation].
 */
interface VisibilityProvider : com.google.devtools.build.lib.analysis.TransitiveInfoProvider {
    /**
     * Returns the target's visibility, as determined from recursively resolving and expanding the
     * package groups it references.
     * 
     * 
     * Morally, this should represent the expansion of the target's [ ][Target.getActualVisibility]. However, as an optimization, for targets that
     * are *not* declared within a symbolic macro, we substitute the [&quot;raw&quot;][Target.getVisibility] for the actual visibility. The optimized version omits the package
     * where the target was instantiated, avoiding extra allocations in the common case of a target
     * that has public or private visibility. The caller must compensate for this optimization by
     * allowing visibility to the target's own package if the target was not created in a macro.
     */
    @kotlin.jvm.JvmField
    val visibility: NestedSet<PackageGroupContents?>?

    /**
     * Returns whether this target was instantiated in one or more symbolic macros.
     * 
     * 
     * This information can be determined from the [Rule] object, but that's not necessarily
     * available from a prerequisite object at analysis time.
     * 
     * 
     * This bit is used by the [CommonPrerequisiteValidator.isSameLogicalPackage] hook, which
     * powers the hack that targets in `//javatests/foo` are allowed to see targets in `//java/foo`. (This feature is only active within Google and disabled for OSS Bazel.) The
     * semantics are that targets created in symbolic macros are never automatically visible to `//javatests/foo` packages, regardless of the package or declaration location.
     * 
     * 
     * This bit is also used to work around the optimization mentioned above for [ ][.getVisibility].
     */
    @kotlin.jvm.JvmField
    val isCreatedInSymbolicMacro: Boolean

    companion object {
        @kotlin.jvm.JvmField
        val PUBLIC_VISIBILITY: NestedSet<PackageGroupContents?>? = NestedSetBuilder.create<PackageGroupContents?>(
            com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER,
            PackageGroupContents.create(
                com.google.common.collect.ImmutableList.of<PackageSpecification?>(
                    PackageSpecification.everything()
                )
            )
        )

        val PRIVATE_VISIBILITY: NestedSet<PackageGroupContents?>? =
            NestedSetBuilder.emptySet<PackageGroupContents?>(com.google.devtools.build.lib.collect.nestedset.Order.STABLE_ORDER)
    }
}
