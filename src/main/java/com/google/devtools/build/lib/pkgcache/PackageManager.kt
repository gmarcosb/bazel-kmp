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
package com.google.devtools.build.lib.pkgcache

import com.google.devtools.build.lib.concurrent.ThreadSafety

/**
 * A PackageManager keeps state about loaded packages around for quick lookup, and provides related
 * functionality: Recursive package finding, loaded package checking, etc.
 */
interface PackageManager : PackageProvider, CachingPackageLocator {
    fun getAndClearStatistics(): PackageManagerStatistics?

    /**
     * Dumps the contents of the package manager in human-readable form. Used by 'bazel dump' and the
     * BuildTool's unexpected exception handler.
     */
    fun dump(printStream: PrintStream?)

    /**
     * Returns the package locator used by this package manager.
     * 
     * 
     * If you are tempted to call `getPackagePath().getPathEntries().get(0)`, be warned that
     * this is probably not the value you are looking for!  Look at the methods of `BazelRuntime` instead.
     */
    @ThreadSafety.ThreadSafe
    fun getPackagePath(): PathPackageLocator?

    /** Collects statistics of the package manager since the last sync.  */
    interface PackageManagerStatistics {
        /** Returns the number of successfully loaded packages since the last sync.  */
        fun getPackagesSuccessfullyLoaded(): Int

        companion object {
            val ZERO: PackageManagerStatistics = PackageManagerStatistics { 0 }
        }
    }
}
