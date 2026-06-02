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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.cmdline.Label

/** A standalone library for performing Bazel package loading.  */
interface PackageLoader : java.lang.AutoCloseable {
    /**
     * Loads and returns a single package. This method is a simplified shorthand for [ ][LoadingContext.loadPackages] when just a single [Package] and nothing else is desired.
     */
    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    fun loadPackage(pkgId: PackageIdentifier?): Package?

    /**
     * A reusable context for loading packages and Starlark modules (.bzl or .scl files).
     * 
     * 
     * Instances of this class are not thread-safe; but the values loaded and returned by its
     * methods are thread-safe. The intention is for LoadingContext to be used e.g. as a local
     * variable in a single-threaded wrapper method of a higher-level abstraction.
     */
    @javax.annotation.concurrent.NotThreadSafe
    interface LoadingContext {
        /**
         * Returns the result of loading a collection of packages. Note that the returned [ ]s may contain errors - see [Package.containsErrors] for details.
         * 
         * 
         * A call to this method clears the list of events accumulated by previous [ ][.loadPackages] and [.loadModules] calls.
         */
        @Throws(java.lang.InterruptedException::class)
        fun loadPackages(
            pkgIds: Iterable<PackageIdentifier?>?
        ): Result<PackageIdentifier?, Package?, NoSuchPackageException?>?

        /**
         * Returns the result of loading a collection of Starlark modules (i.e. .bzl or .scl files);
         * intended for use by standalone implementations of the starlark_doc_extract rule.
         * 
         * 
         * A call to this method clears the list of events accumulated by previous [ ][.loadPackages] and [.loadModules] calls.
         */
        @Throws(java.lang.InterruptedException::class)
        fun loadModules(labels: Iterable<Label?>?): Result<Label?, net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?

        @kotlin.jvm.JvmField
        @get:Throws(java.lang.InterruptedException::class)
        val repositoryMapping: RepositoryMapping?
    }

    /** An exception thrown when we fail to load a Starlark module.  */
    class StarlarkModuleLoadingException : java.lang.Exception {
        internal constructor(message: String?) : super(message)

        internal constructor(cause: BzlLoadFailedException?) : super(cause)

        val failureDetail: java.util.Optional<FailureDetail?>
            /** The [FailureDetail] of the underlying exception, if one is available.  */
            get() {
                if (getCause() is DetailedException) {
                    return java.util.Optional.of<T?>(
                        (getCause() as DetailedException).detailedExitCode.getFailureDetail()
                    )
                } else {
                    return java.util.Optional.empty<FailureDetail?>()
                }
            }
    }

    /** Returns a new [LoadingContext].  */
    @Throws(java.lang.InterruptedException::class)
    fun makeLoadingContext(): LoadingContext?

    /**
     * Shut down the internal threadpools used by the [PackageLoader].
     * 
     * 
     * Call this method when you are completely done with the [PackageLoader] instance,
     * otherwise there may be resource leaks.
     */
    override fun close()

    /**
     * Contains the result of a [LoadingContext.loadPackages] or [ ][LoadingContext.loadModules] call.
     */
    class Result<K, V, E : java.lang.Exception?> internal constructor(
        loadedValues: com.google.common.collect.ImmutableMap<K?, ValueOrException<V?, E?>?>?,
        events: com.google.common.collect.ImmutableList<Event?>?
    ) {
        private val loadedValues: com.google.common.collect.ImmutableMap<K?, ValueOrException<V?, E?>?>?
        private val events: com.google.common.collect.ImmutableList<Event?>?

        init {
            this.loadedValues = loadedValues
            this.events = events
        }

        /**
         * Returns the map from the requested keys to the corresponding values (packages or modules)
         * which were loaded, or exceptions encountered while attempting to load a given value.
         */
        fun getLoadedValues(): com.google.common.collect.ImmutableMap<K?, ValueOrException<V?, E?>?>? {
            return loadedValues
        }

        /**
         * Returns the events generated by the [LoadingContext.loadPackages] or [ ][LoadingContext.loadModules] call.
         */
        fun getEvents(): com.google.common.collect.ImmutableList<Event?>? {
            return events
        }
    }
}
