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

import com.google.devtools.build.lib.cmdline.Label

/**
 * API for retrieving targets.
 * 
 * 
 * **Concurrency**: Implementations should be thread safe.
 */
interface TargetProvider {
    /**
     * Returns the Target identified by "label", loading, parsing and evaluating the package if it is
     * not already loaded.
     * 
     * @throws NoSuchPackageException if the package could not be found
     * @throws NoSuchTargetException if the package was loaded successfully, but the specified [     ] was not found in it
     * @throws InterruptedException if the package loading was interrupted
     */
    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    fun getTarget(eventHandler: ExtendedEventHandler?, label: Label?): com.google.devtools.build.lib.packages.Target?

    /** Returns the BUILD file target of the package which contains the given target.  */
    @Throws(java.lang.InterruptedException::class)
    fun getBuildFile(target: com.google.devtools.build.lib.packages.Target?): InputFile?

    /**
     * Returns all targets in the package which contains the given target.
     * 
     * @throws NoSuchPackageException if the lazy macro expansion is enabled and some of the pieces of
     * the full package could not be loaded.
     * @throws InterruptedException if the package loading was interrupted
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun getSiblingTargetsInPackage(
        eventHandler: ExtendedEventHandler?, target: com.google.devtools.build.lib.packages.Target?
    ): com.google.common.collect.ImmutableCollection<com.google.devtools.build.lib.packages.Target?>?
}
