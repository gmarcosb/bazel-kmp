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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.PackageRoots

/** A [PackageRoots] backed by a map of package identifiers to paths.  */
internal class MapAsPackageRoots(packageRootsMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>) :
    PackageRoots {
    private val packageRootsMap: com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?>

    init {
        this.packageRootsMap = packageRootsMap
    }

    public override fun getPackageRootsMap(): com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> {
        return packageRootsMap
    }

    val packageRootLookup: PackageRootLookup
        get() {
            val deduper: MutableMap<Root?, Root?> = HashMap<Root?, Root?>()
            val realPackageRoots: MutableMap<PackageIdentifier?, Root?> =
                HashMap<PackageIdentifier?, Root?>()
            for (entry in packageRootsMap.entries) {
                val newRoot: Root? = entry.value
                val oldRoot: Root? = deduper.putIfAbsent(newRoot, newRoot)
                realPackageRoots.put(entry.key, if (oldRoot == null) newRoot else oldRoot)
            }
            if (deduper.size == 1) {
                // This will return a root more often than in the multi-root case, which only returns a root
                // for an exact match, but there are no negative consequences to being *more* informed about
                // a file's potential root.
                val onlyRoot: Root? = com.google.common.collect.Iterables.getOnlyElement<Root?>(deduper.keys)
                return PackageRootLookup { k -> onlyRoot }
            }
            return PackageRootLookup { key: Any? -> realPackageRoots.get(key) }
        }
}
