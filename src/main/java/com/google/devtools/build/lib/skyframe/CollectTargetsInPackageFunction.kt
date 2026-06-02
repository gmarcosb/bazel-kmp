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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Declares a dependency on all targets in a package, to ensure those targets are in the graph. Does
 * no error-checking on the package id provided, so callers should have already verified that there
 * is a package with this id.
 */
internal class CollectTargetsInPackageFunction : SkyFunction {
    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val argument: CollectTargetsInPackageKey =
            skyKey.argument() as CollectTargetsInPackageKey
        val packageId: PackageIdentifier = argument.packageId
        val packageValue: PackageValue?
        try {
            packageValue = env.getValueOrThrow<E?>(packageId, NoSuchPackageException::class.java) as PackageValue?
        } catch (e: NoSuchPackageException) {
            // If the argument is a package that doesn't exist, the aggregator function can return
            // a success value immediately.
            return CollectTargetsInPackageValue.INSTANCE
        }
        if (env.valuesMissing()) {
            return null
        }
        val pkg: Package = packageValue.getPackage()
        if (pkg.containsErrors()) {
            env.getListener()
                .handle(
                    Event.error(
                        "package contains errors: " + packageId.getPackageFragment().getPathString()
                    )
                )
        }
        return if (GraphTraversingHelper.declareDependenciesAndCheckIfValuesMissing(
                env,
                com.google.common.collect.Iterables.transform<F?, T?>(
                    TargetPatternResolverUtil.resolvePackageTargets(pkg, argument.filteringPolicy),
                    TO_TRANSITIVE_TRAVERSAL_KEY
                )
            )
        )
            null
        else
            CollectTargetsInPackageValue.INSTANCE
    }

    companion object {
        private val TO_TRANSITIVE_TRAVERSAL_KEY: com.google.common.base.Function<Target?, SkyKey?> =
            object : com.google.common.base.Function<Target?, SkyKey?> {
                override fun apply(target: Target): SkyKey? {
                    return TransitiveTraversalValue.key(target.getLabel())
                }
            }
    }
}
