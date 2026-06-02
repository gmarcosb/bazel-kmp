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
 * A bridge class that implements the legacy semantics of [.getLoadedTarget] using a normal
 * [PackageProvider] instance.
 * 
 * 
 * DO NOT USE! It will be removed when the transition to Skyframe is complete.
 */
class LoadedPackageProvider(packageProvider: PackageProvider, eventHandler: ExtendedEventHandler?) {
    private val packageProvider: PackageProvider
    private val eventHandler: ExtendedEventHandler?

    init {
        this.packageProvider = packageProvider
        this.eventHandler = eventHandler
    }

    fun getEventHandler(): ExtendedEventHandler? {
        return eventHandler
    }

    /**
     * Returns a target if it was recently loaded, i.e., since the most recent cache sync. This throws
     * an exception if the target was not loaded or not validated, even if it exists in the
     * surrounding package. If the surrounding package is in error, still attempts to retrieve the
     * target.
     */
    @Throws(NoSuchPackageException::class, NoSuchTargetException::class, java.lang.InterruptedException::class)
    fun getLoadedTarget(label: Label?): com.google.devtools.build.lib.packages.Target? {
        return packageProvider.getTarget(eventHandler, label)
    }
}
