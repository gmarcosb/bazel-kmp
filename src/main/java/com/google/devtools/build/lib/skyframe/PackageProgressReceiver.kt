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
 * A class that, when being told about start and end of a package being loaded, keeps track of the
 * loading progress and provides it as a human-readable string intended for the progress bar.
 */
class PackageProgressReceiver {
    private var packagesCompleted = 0
    private var pendingSet: LinkedHashSet<PackageIdentifier?> = LinkedHashSet<PackageIdentifier?>()

    /** Register that loading a package has started.  */
    @kotlin.jvm.Synchronized
    fun startReadPackage(packageId: PackageIdentifier?) {
        pendingSet.add(packageId)
    }

    /** Register that loading a package has completed.  */
    @kotlin.jvm.Synchronized
    fun doneReadPackage(packageId: PackageIdentifier?) {
        packagesCompleted++
        pendingSet.remove(packageId)
    }

    /**
     * Reset all instance variables of this object to a state equal to that of a newly
     * constructed object.
     */
    @kotlin.jvm.Synchronized
    fun reset() {
        packagesCompleted = 0
        pendingSet = LinkedHashSet<PackageIdentifier?>()
    }

    /**
     * Return the ordered pair of a consistent snapshot of the state, consisting of a human-readable
     * description of the progress achieved so far and a human readable description of the currently
     * running activities. The later always include the oldest loading package not finished loading.
     */
    @kotlin.jvm.Synchronized
    fun progressState(): com.google.devtools.build.lib.util.Pair<String?, String?> {
        val progress =
            com.google.devtools.build.lib.util.StringUtil.formatCount(packagesCompleted.toLong()) + " packages loaded"
        val activity: java.lang.StringBuffer = java.lang.StringBuffer()
        if (pendingSet.size() > 0) {
            activity
                .append("currently loading: ")
                .append(com.google.common.collect.Iterables.getFirst<PackageIdentifier?>(pendingSet, null).toString())
            if (pendingSet.size() > 1) {
                activity
                    .append(" ... (")
                    .append(com.google.devtools.build.lib.util.StringUtil.formatCount(pendingSet.size().toLong()))
                    .append(" packages)")
            }
        }
        return com.google.devtools.build.lib.util.Pair<String?, String?>(progress, activity.toString())
    }
}
