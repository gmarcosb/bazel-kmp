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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories
import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.cmdline.ResolvedTargets
import com.google.devtools.build.lib.cmdline.TargetParsingException
import com.google.devtools.build.lib.io.InconsistentFilesystemException
import com.google.devtools.build.lib.io.ProcessPackageDirectoryException
import com.google.devtools.build.lib.vfs.PathFragment

/**
 * A callback that is used during the process of converting target patterns (such as `//foo:all
` * ) into one or more lists of targets (such as `//foo:foo,
 * //foo:bar`). During a call to [TargetPattern.eval], the [TargetPattern] makes
 * calls to this interface to implement the target pattern semantics. The generic type `T` is
 * only for compile-time type safety; there are no requirements to the actual type.
 */
abstract class TargetPatternResolver<T> {
    /** Reports the given warning.  */
    abstract fun warn(msg: String?)

    /**
     * Returns a single target corresponding to the given label, or null. This method may only throw
     * an exception if the current thread was interrupted.
     */
    @Throws(java.lang.InterruptedException::class, InconsistentFilesystemException::class)
    abstract fun getTargetOrNull(label: com.google.devtools.build.lib.cmdline.Label?): T?

    /** Returns a single target corresponding to the given label, or an empty or failed result.  */
    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    abstract fun getExplicitTarget(label: com.google.devtools.build.lib.cmdline.Label?): ResolvedTargets<T?>?

    /**
     * Returns the set containing the targets found in the given package. The specified directory is
     * not necessarily a valid package name. If `rulesOnly` is true, then this method should
     * only return rules in the given package.
     * 
     * @param originalPattern the original target pattern for error reporting purposes
     * @param packageIdentifier the identifier of the package
     * @param rulesOnly whether to return rules only
     */
    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    abstract fun getTargetsInPackage(
        originalPattern: String?, packageIdentifier: PackageIdentifier?, rulesOnly: Boolean
    ): MutableCollection<T?>?

    /**
     * Computes the set containing the targets found below the given `directory`, passing it in
     * batches to `callback`. Conceptually, this method should look for all packages that start
     * with the `directory` (as a proper prefix directory, i.e., "foo/ba" is not a proper prefix
     * of "foo/bar/"), and then collect all targets in each such package (subject to `rulesOnly`) as if calling [.getTargetsInPackage]. The specified directory is not
     * necessarily a valid package name.
     * 
     * 
     * Note that the `directory` can be empty, which corresponds to the "//..." pattern.
     * Implementations may choose not to support this case and throw an [ ] exception instead, or may restrict the set of directories that are
     * considered by default.
     * 
     * 
     * If the `directory` points to a package, then that package should also be part of the
     * result.
     * 
     * @param originalPattern the original target pattern for error reporting purposes
     * @param directory the directory in which to look for packages
     * @param rulesOnly whether to return rules only
     * @param forbiddenSubdirectories a set of transitive subdirectories beneath `directory` to
     * ignore
     * @param excludedSubdirectories another set of transitive subdirectories beneath `directory` to ignore
     * @param callback the callback to receive the result, possibly in multiple batches.
     * @param exceptionClass The class type of the parameterized exception.
     * @throws TargetParsingException under implementation-specific failure conditions
     * @throws ProcessPackageDirectoryException only when called from within Skyframe and an
     * inconsistent filesystem state is observed
     */
    @Throws(
        TargetParsingException::class,
        E::class,
        java.lang.InterruptedException::class,
        ProcessPackageDirectoryException::class
    )
    abstract fun <E>
            findTargetsBeneathDirectory(
        repository: RepositoryName?,
        originalPattern: String?,
        directory: String?,
        rulesOnly: Boolean,
        forbiddenSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
        exceptionClass: java.lang.Class<E?>?
    ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface?

    /**
     * Async version of [.findTargetsBeneathDirectory]. Never call this from within Skyframe
     * evaluation.
     * 
     * 
     * Default implementation is synchronous.
     */
    open fun <E>
            findTargetsBeneathDirectoryAsync(
        repository: RepositoryName?,
        originalPattern: String?,
        directory: String?,
        rulesOnly: Boolean,
        forbiddenSubdirectories: IgnoredSubdirectories?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
        exceptionClass: java.lang.Class<E?>,
        executor: com.google.common.util.concurrent.ListeningExecutorService?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
        try {
            findTargetsBeneathDirectory<E?>(
                repository,
                originalPattern,
                directory,
                rulesOnly,
                forbiddenSubdirectories,
                excludedSubdirectories,
                callback,
                exceptionClass
            )
            return com.google.common.util.concurrent.Futures.immediateVoidFuture()
        } catch (e: TargetParsingException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        } catch (e: java.lang.InterruptedException) {
            return com.google.common.util.concurrent.Futures.immediateCancelledFuture<java.lang.Void?>()
        } catch (e: ProcessPackageDirectoryException) {
            throw java.lang.IllegalStateException(
                ("Async find targets beneath directory isn't called from within Skyframe: traversing "
                        + directory
                        + " for "
                        + originalPattern),
                e
            )
        } catch (e: java.lang.Exception) {
            if (exceptionClass.isInstance(e)) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
            }
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Returns true, if and only if the given package identifier corresponds to a package, i.e., a
     * file with the name `packageName/BUILD` exists in the appropriate repository.
     */
    @Throws(java.lang.InterruptedException::class, InconsistentFilesystemException::class)
    abstract fun isPackage(packageIdentifier: PackageIdentifier?): Boolean

    /** Returns the target kind of the given target, for example `cc_library rule`.  */
    abstract fun getTargetKind(target: T?): String?
}
