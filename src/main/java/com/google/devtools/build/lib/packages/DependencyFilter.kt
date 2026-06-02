// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.DependencyFilter.AttributeInfoProvider
import com.google.devtools.build.lib.packages.Type.LabelClass

/**
 * A predicate that returns true if a dependency attribute should be included in the result of
 * `blaze query`. Used to implement `--[no]implicit_deps`, `--[no]host_deps`, etc.
 */
interface DependencyFilter :
    java.util.function.BiPredicate<AttributeInfoProvider?, com.google.devtools.build.lib.packages.Attribute?> {
    override fun and(
        other: java.util.function.BiPredicate<in AttributeInfoProvider?, in com.google.devtools.build.lib.packages.Attribute?>?
    ): DependencyFilter {
        return DependencyFilter { t: java.util.function.BiPredicate<in AttributeInfoProvider?, in com.google.devtools.build.lib.packages.Attribute?>? ->
            super.and(
                other
            ).test(t)
        }
    }

    /** Interface to provide information about attributes to dependency filters.  */
    interface AttributeInfoProvider {
        /**
         * Returns true iff the value of the specified attribute is explicitly set in
         * the BUILD file (as opposed to its default value). This also returns true if
         * the value from the BUILD file is the same as the default value.
         */
        fun isAttributeValueExplicitlySpecified(attribute: com.google.devtools.build.lib.packages.Attribute?): Boolean
    }

    companion object {
        /** Dependency predicate that includes all dependencies.  */
        @kotlin.jvm.JvmField
        val ALL_DEPS: DependencyFilter =
            DependencyFilter { infoProvider: AttributeInfoProvider?, attribute: com.google.devtools.build.lib.packages.Attribute? -> true }

        /** Dependency predicate that excludes non-target dependencies.  */
        @kotlin.jvm.JvmField
        val ONLY_TARGET_DEPS: DependencyFilter =
            DependencyFilter { infoProvider: AttributeInfoProvider?, attribute: com.google.devtools.build.lib.packages.Attribute? -> !attribute.isToolDependency() }

        /** Dependency predicate that excludes implicit dependencies.  */
        @kotlin.jvm.JvmField
        val NO_IMPLICIT_DEPS: DependencyFilter =
            DependencyFilter { obj: java.util.function.BiPredicate<in AttributeInfoProvider?, in com.google.devtools.build.lib.packages.Attribute?>? -> obj.isAttributeValueExplicitlySpecified() }

        /**
         * Dependency predicate that excludes those edges that are not present in the loading phase target
         * dependency graph.
         */
        @kotlin.jvm.JvmField
        val NO_NODEP_ATTRIBUTES: DependencyFilter =
            DependencyFilter { infoProvider: AttributeInfoProvider?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                attribute.getType().getLabelClass() != LabelClass.NONDEP_REFERENCE
            }

        /**
         * Dependency predicate that excludes those edges that are not present in the loading phase target
         * dependency graph but *does* include edges from the `visibility` attribute.
         */
        @kotlin.jvm.JvmField
        val NO_NODEP_ATTRIBUTES_EXCEPT_VISIBILITY: DependencyFilter =
            DependencyFilter { infoProvider: AttributeInfoProvider?, attribute: com.google.devtools.build.lib.packages.Attribute? ->
                NO_NODEP_ATTRIBUTES.test(infoProvider, attribute)
                        || attribute.getName() == "visibility"
            }
    }
}
