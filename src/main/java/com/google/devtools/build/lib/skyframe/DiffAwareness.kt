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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Interface for computing modifications of files under a package path entry.
 * 
 * 
 *  Skyframe has a [DiffAwareness] instance per package-path entry, and each instance is
 * responsible for all files under its path entry. At the beginning of each incremental build,
 * skyframe queries for changes using [.getDiff]. Ideally, [.getDiff] should be
 * constant-time; if it were linear in the number of files of interest, we might as well just
 * detect modifications manually.
 */
interface DiffAwareness : java.io.Closeable {
    /** Factory for creating [DiffAwareness] instances.  */
    interface Factory {
        /**
         * Returns a [DiffAwareness] instance suitable for managing changes to files under the
         * given package path entry, or `null` if this factory cannot create such an instance. The
         * instance will not report any changes to files within the given set of ignored paths.
         * 
         * 
         * Skyframe has a collection of factories, and will create a [DiffAwareness] instance
         * per package path entry using one of the factories that returns a non-null value.
         */
        fun maybeCreate(
            pathEntry: Root?,
            ignoredPaths: IgnoredSubdirectories?,
            optionsProvider: com.google.devtools.common.options.OptionsProvider?
        ): DiffAwareness?
    }

    /** Opaque view of the filesystem under a package path entry at a specific point in time.  */
    interface View {
        val workspaceInfo: WorkspaceInfoFromDiff?
            /** Returns workspace info unanimously associated with the package path or null.  */
            get() = null
    }

    /**
     * Returns the live view of the filesystem under the package path entry.
     * 
     * @throws BrokenDiffAwarenessException if something is wrong and the caller should discard this
     * [DiffAwareness] instance. The [DiffAwareness] is expected to close itself in
     * this case.
     */
    @Throws(BrokenDiffAwarenessException::class)
    fun getCurrentView(options: com.google.devtools.common.options.OptionsProvider?): View?

    /**
     * Returns the set of files of interest that have been modified between the given two views.
     * 
     * 
     * The given views must have come from previous calls to [.getCurrentView] on the [ ] instance (i.e. using a [View] from another instance is not supported).
     * 
     * @throws IncompatibleViewException if the given views are not compatible with this [     ] instance. This probably indicates a bug.
     * @throws BrokenDiffAwarenessException if something is wrong and the caller should discard this
     * [DiffAwareness] instance. The [DiffAwareness] is expected to close itself in
     * this case.
     */
    @Throws(
        IncompatibleViewException::class,
        java.lang.InterruptedException::class,
        BrokenDiffAwarenessException::class
    )
    fun getDiff(oldView: View?, newView: View?): ModifiedFileSet?

    /** @return the name of this implementation
     */
    fun name(): String?

    /**
     * Must be called whenever the [DiffAwareness] object is to be discarded. Using a
     * [DiffAwareness] instance after calling [.close] on it is unspecified behavior.
     */
    override fun close()
}
