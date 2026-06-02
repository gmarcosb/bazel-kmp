// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.BatchCallback.SafeBatchCallback

/** Looks up [RecursivePkgValue]s of given roots in a [WalkableGraph].  */
class RecursivePkgValueRootPackageExtractor : RootPackageExtractor {
    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun streamPackagesFromRoots(
        results: SafeBatchCallback<PackageIdentifier?>,
        graph: WalkableGraph,
        roots: MutableList<Root?>,
        eventHandler: ExtendedEventHandler?,
        repository: RepositoryName?,
        directory: PathFragment,
        ignoredSubdirectories: IgnoredSubdirectories,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>
    ) {
        val filteredIgnoredSubdirectories: IgnoredSubdirectories? =
            ignoredSubdirectories.filterForDirectory(directory)

        for (root in roots) {
            val rootedPath: RootedPath? = RootedPath.toRootedPath(root, directory)
            val lookup: RecursivePkgValue? =
                graph.getValue(
                    RecursivePkgValue.Companion.key(repository, rootedPath, filteredIgnoredSubdirectories)
                ) as RecursivePkgValue?
            if (lookup == null) {
                // A null lookup should only happen during post-analysis queries which have access to
                // --universe_scope logic. For builds lookup should never be null because {@link
                // RecursivePkgFunction} handles all errors in a --keep_going build. In a --nokeep_going
                // build, we should never reach this part of the code.
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    java.lang.String.format(
                        ("Unable to load package '%s' because package is not in scope. Check that all"
                                + " target patterns in query expression are within the --universe_scope of this"
                                + " query."),
                        rootedPath
                    ),
                    Code.TARGET_NOT_IN_UNIVERSE_SCOPE
                )
            }
            val packageIds: com.google.common.collect.ImmutableList.Builder<PackageIdentifier?> =
                com.google.common.collect.ImmutableList.builder<PackageIdentifier?>()
            for (packageName in lookup.getPackages().toList()) {
                // TODO(bazel-team): Make RecursivePkgValue return NestedSet<PathFragment> so this transform
                // is unnecessary.
                val packageNamePathFragment: PathFragment = PathFragment.create(packageName)
                if (!com.google.common.collect.Iterables.any<PathFragment?>(
                        excludedSubdirectories,
                        com.google.common.base.Predicate { other: PathFragment? ->
                            packageNamePathFragment.startsWith(other)
                        })
                ) {
                    packageIds.add(PackageIdentifier.create(repository, packageNamePathFragment))
                }
            }
            results.process(packageIds.build())
        }
    }
}
