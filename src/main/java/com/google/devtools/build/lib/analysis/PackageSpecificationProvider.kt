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

import com.google.devtools.build.lib.cmdline.Label

/**
 * A [TransitiveInfoProvider] that describes a set of transitive package specifications used
 * in package groups.
 */
class PackageSpecificationProvider private constructor(packageSpecifications: NestedSet<PackageGroupContents?>) :
    NativeInfo(), TransitiveInfoProvider, PackageSpecificationProviderApi {
    private val packageSpecifications: NestedSet<PackageGroupContents?>

    init {
        this.packageSpecifications = packageSpecifications
    }

    public override fun getProvider(): Provider {
        return PROVIDER
    }

    /** Returns set of transitive package specifications used in package groups.  */
    fun getPackageSpecifications(): NestedSet<PackageGroupContents?> {
        return packageSpecifications
    }

    @Throws(net.starlark.java.eval.EvalException::class, LabelSyntaxException::class)
    public override fun targetInAllowlist(target: Any?): Boolean {
        val targetLabel: Label?
        if (target is String) {
            targetLabel = Label.parseCanonical(target)
        } else if (target is Label) {
            targetLabel = target
        } else {
            throw Starlark.errorf(
                "expected string or label for 'target' instead of %s", Starlark.type(target)
            )
        }

        return Allowlist.isAvailableFor(packageSpecifications, targetLabel)
    }

    companion object {
        private const val STARLARK_NAME = "PackageSpecificationInfo"

        val PROVIDER: BuiltinProvider<PackageSpecificationProvider?> =
            object : BuiltinProvider(STARLARK_NAME, PackageSpecificationProvider::class.java) {}

        val EMPTY: PackageSpecificationProvider =
            PackageSpecificationProvider(NestedSetBuilder.emptySet(Order.STABLE_ORDER))

        /**
         * Creates a `PackageSpecificationProvider` by initializing transitive package
         * specifications from `targetContext` and `packageGroup`.
         */
        fun create(
            targetContext: TargetContext, packageGroup: PackageGroup
        ): PackageSpecificationProvider {
            return PackageSpecificationProvider(getPackageSpecifications(targetContext, packageGroup))
        }

        private fun getPackageSpecifications(
            targetContext: TargetContext, packageGroup: PackageGroup
        ): NestedSet<PackageGroupContents?> {
            val builder: NestedSetBuilder<PackageGroupContents?> = NestedSetBuilder.stableOrder()
            for (includeLabel in packageGroup.getIncludes()) {
                val include: TransitiveInfoCollection? =
                    targetContext.findDirectPrerequisite(
                        includeLabel,
                        java.util.Optional.ofNullable<BuildConfigurationValue?>(targetContext.getConfiguration())
                    )
                val provider: PackageSpecificationProvider? = if (include == null) null else include.get(PROVIDER)
                if (provider == null) {
                    targetContext
                        .getAnalysisEnvironment()
                        .getEventHandler()
                        .handle(
                            com.google.devtools.build.lib.events.Event.error(
                                targetContext.getTarget().getLocation(),
                                java.lang.String.format("Label '%s' does not refer to a package group", includeLabel)
                            )
                        )
                    continue
                }

                builder.addTransitive(provider.getPackageSpecifications())
            }

            builder.add(packageGroup.getPackageSpecifications())
            return builder.build()
        }
    }
}
