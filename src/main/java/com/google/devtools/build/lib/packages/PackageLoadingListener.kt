// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.pkgcache.PackageOptions.LazyMacroExpansionPackages
import com.google.devtools.build.lib.vfs.RootedPath

/** Listener for package-loading events.  */
interface PackageLoadingListener {
    /**
     * Metrics about loading a single package.
     * 
     * @param loadTimeNanos the wall time, in ns, that it took to load the package. More precisely,
     * this is the wall time of the call to [PackageFactory.createPackageFromAst]. Notably,
     * this does not include the time to read and parse the package's BUILD file, nor the time to
     * read, parse, or evaluate any of the transitively loaded .bzl files, and it includes time
     * the OS thread is runnable but not running.
     * @param globFilesystemOperationCost cost of the filesystem operations performed across all
     * `glob` calls while loading the package. `stat` operations cost `
     * 1` and `readdir` operations cost `1 + D`, where `D`
     * is the number of dirents.
     */
    @kotlin.jvm.JvmRecord
    data class Metrics(loadTimeNanos: Long, globFilesystemOperationCost: Long) {
        val loadTimeNanos: Long
        val globFilesystemOperationCost: Long

        init {
            this.loadTimeNanos = loadTimeNanos
            this.globFilesystemOperationCost = globFilesystemOperationCost
        }
    }

    /**
     * Called after [com.google.devtools.build.lib.skyframe.PackageFunction] has successfully
     * loaded the given [Package].
     * 
     * @param pkg the loaded [Package]
     * @param starlarkSemantics are the semantics used to load the package
     * @param lazyMacroExpansionPackages determines which packages are loaded with lazy symbolic macro
     * expansion enabled
     */
    fun onLoadingCompleteAndSuccessful(
        pkg: com.google.devtools.build.lib.packages.Package?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        lazyMacroExpansionPackages: LazyMacroExpansionPackages?,
        metrics: Metrics?
    )

    /**
     * Called after [com.google.devtools.build.lib.skyframe.BzlCompileFunction] has successfully
     * parsed the file denoted by the given [RootedPath].
     */
    fun onBzlCompileCompleteAndSuccessful(path: RootedPath?, fileSize: Long) {}

    companion object {
        /** Returns a [PackageLoadingListener] from a composed of the input listeners.  */
        fun create(listeners: MutableList<PackageLoadingListener>): PackageLoadingListener? {
            return when (listeners.size()) {
                0 -> NOOP_LISTENER
                1 -> listeners.get(0)
                else -> PackageLoadingListener { pkg: com.google.devtools.build.lib.packages.Package?, semantics: net.starlark.java.eval.StarlarkSemantics?, lazyMacroExpansionPackages: LazyMacroExpansionPackages?, metrics: Metrics? ->
                    for (listener in listeners) {
                        listener.onLoadingCompleteAndSuccessful(
                            pkg, semantics, lazyMacroExpansionPackages, metrics
                        )
                    }
                }
            }
        }

        @kotlin.jvm.JvmField
        val NOOP_LISTENER: PackageLoadingListener =
            PackageLoadingListener { pkg: com.google.devtools.build.lib.packages.Package?, semantics: net.starlark.java.eval.StarlarkSemantics?, lazyMacroExpansionPackages: LazyMacroExpansionPackages?, metrics: Metrics? -> }
    }
}
