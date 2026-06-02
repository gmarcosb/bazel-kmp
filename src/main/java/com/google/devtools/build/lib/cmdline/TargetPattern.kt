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

import com.google.devtools.build.lib.server.FailureDetails.TargetPatterns

/**
 * Represents a target pattern. Target patterns are a generalization of labels to include wildcards
 * for finding all packages recursively beneath some root, and for finding all targets within a
 * package.
 * 
 * 
 * Note that this class does not handle negative patterns ("-//foo/bar"); these must be handled
 * one level up. In particular, the query language comes with built-in support for negative
 * patterns.
 * 
 * 
 * In order to resolve target patterns, you need an implementation of [ ]. This class is thread-safe if the corresponding instance is thread-safe.
 * 
 * 
 * See lib/blaze/commands/target-syntax.txt for details.
 */
abstract class TargetPattern private constructor(originalPattern: String?) {
    /** Return the string that was parsed into this pattern.  */
    @kotlin.jvm.JvmField
    val originalPattern: String

    init {
        // Don't allow inheritance outside this class.
        this.originalPattern = com.google.common.base.Preconditions.checkNotNull<String>(originalPattern)
    }

    /**
     * Return the type of the pattern. Examples include "below directory" like "foo/..." and "single
     * target" like "//x:y".
     */
    @kotlin.jvm.JvmField
    abstract val type: Type?

    /**
     * Evaluates the current target pattern, excluding targets under directories in both `ignoredSubdirectories` and `excludedSubdirectories`, and returns the result.
     * 
     * @throws InconsistentFilesystemException if `resolver` makes Skyframe calls and discovers
     * a filesystem inconsistency as observed by Skyframe, and this pattern does not have type
     * `Type.TARGETS_BELOW_DIRECTORY`
     * @throws ProcessPackageDirectoryException if `resolver` makes Skyframe calls and discovers
     * a filesystem inconsistency as observed by Skyframe, and this pattern has type `Type.TARGETS_BELOW_DIRECTORY`
     * @throws IllegalArgumentException if either `ignoredSubdirectories` or `excludedSubdirectories` is nonempty and this pattern does not have type `Type.TARGETS_BELOW_DIRECTORY`.
     */
    @Throws(
        TargetParsingException::class,
        E::class,
        java.lang.InterruptedException::class,
        ProcessPackageDirectoryException::class,
        InconsistentFilesystemException::class
    )
    abstract fun <T, E> eval(
        resolver: TargetPatternResolver<T?>?,
        ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
        exceptionClass: java.lang.Class<E?>?
    ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface?

    /**
     * Evaluates this [TargetPattern] synchronously, feeding the result to the given `callback`, and then returns an appropriate immediate [ListenableFuture].
     * 
     * 
     * If the returned [ListenableFuture]'s [ListenableFuture.get] throws an `ExecutionException`, the cause will be an instance of either [TargetParsingException] or
     * the given `exceptionClass`.
     * 
     * 
     * This method must not be called from within Skyframe evaluation. Use [ ] and friends for that.
     */
    fun <T, E>
            evalAdaptedForAsync(
        resolver: TargetPatternResolver<T?>?,
        ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
        exceptionClass: java.lang.Class<E?>
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
        try {
            eval<T?, E?>(resolver, ignoredSubdirectories, excludedSubdirectories, callback, exceptionClass)
            return com.google.common.util.concurrent.Futures.immediateFuture<java.lang.Void?>(null)
        } catch (e: TargetParsingException) {
            return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(e)
        } catch (e: ProcessPackageDirectoryException) {
            throw java.lang.IllegalStateException(
                "Cannot throw filesystem-related exceptions outside of Skyframe evaluation for " + this,
                e
            )
        } catch (e: InconsistentFilesystemException) {
            throw java.lang.IllegalStateException(
                "Cannot throw filesystem-related exceptions outside of Skyframe evaluation for " + this,
                e
            )
        } catch (e: java.lang.InterruptedException) {
            return com.google.common.util.concurrent.Futures.immediateCancelledFuture<java.lang.Void?>()
        } catch (e: java.lang.Exception) {
            if (exceptionClass.isInstance(e)) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                    exceptionClass.cast(
                        e
                    )
                )
            }
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Returns a [ListenableFuture] representing the asynchronous evaluation of this [ ] that feeds the results to the given `callback`.
     * 
     * 
     * If the returned [ListenableFuture]'s [ListenableFuture.get] throws an `ExecutionException`, the cause will be an instance of either [TargetParsingException] or
     * the given `exceptionClass`.
     */
    open fun <T, E> evalAsync(
        resolver: TargetPatternResolver<T?>?,
        ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
        excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
        callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
        exceptionClass: java.lang.Class<E?>,
        executor: com.google.common.util.concurrent.ListeningExecutorService?
    ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
        return evalAdaptedForAsync<T?, E?>(
            resolver, ignoredSubdirectories, excludedSubdirectories, callback, exceptionClass
        )
    }

    open val pathForPathAsTarget: String?
        /**
         * For patterns of type [Type.PATH_AS_TARGET], returns the path in question.
         * 
         * 
         * The interpretation of this path, of course, depends on the existence of packages. See [ ][InterpretPathAsTarget.eval].
         */
        get() {
            throw java.lang.IllegalStateException()
        }

    open val singleTargetLabel: com.google.devtools.build.lib.cmdline.Label?
        /** For patterns of type [Type.SINGLE_TARGET], returns the label to the target.  */
        get() {
            throw java.lang.IllegalStateException()
        }

    open val directory: PackageIdentifier?
        /**
         * For patterns of type [Type.SINGLE_TARGET], [Type.TARGETS_IN_PACKAGE], and [ ][Type.TARGETS_BELOW_DIRECTORY], returns the [PackageIdentifier] of the pattern.
         * 
         * 
         * Note that we are using the [PackageIdentifier] type as a convenience; there may not
         * actually be a package corresponding to this directory!
         * 
         * 
         * Examples:
         * 
         * 
         *  * For pattern `//foo:bar`, returns package identifier `//foo`.
         *  * For pattern `//foo:all`, returns package identifier `//foo`.
         *  * For pattern `//foo/...`, returns package identifier `//foo`.
         * 
         */
        get() {
            throw java.lang.IllegalStateException()
        }

    /** Returns the repository name of the target pattern.  */
    @kotlin.jvm.JvmField
    abstract val repository: RepositoryName?

    /**
     * Returns `true` iff this pattern has type `Type.TARGETS_BELOW_DIRECTORY` or `Type.TARGETS_IN_PACKAGE` and the target pattern suffix specified it should match rules only.
     */
    @kotlin.jvm.JvmField
    abstract val rulesOnly: Boolean

    protected fun toStringHelper(): com.google.common.base.MoreObjects.ToStringHelper {
        return com.google.common.base.MoreObjects.toStringHelper(this).add("originalPattern", originalPattern)
    }

    @com.google.common.annotations.VisibleForTesting
    internal class SingleTarget @com.google.common.annotations.VisibleForTesting constructor(
        originalPattern: String?,
        target: com.google.devtools.build.lib.cmdline.Label?
    ) : TargetPattern(originalPattern) {
        private val target: com.google.devtools.build.lib.cmdline.Label

        init {
            this.target =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.cmdline.Label>(target)
        }

        @Throws(TargetParsingException::class, E::class, java.lang.InterruptedException::class)
        override fun <T, E> eval(
            resolver: TargetPatternResolver<T?>,
            ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
            callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>,
            exceptionClass: java.lang.Class<E?>?
        ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
            callback.process(resolver.getExplicitTarget(target).getTargets())
        }

        override fun getDirectory(): PackageIdentifier? {
            return target.getPackageIdentifier()
        }

        override fun getRepository(): RepositoryName? {
            return target.getRepository()
        }

        override fun getRulesOnly(): Boolean {
            return false
        }

        override fun getSingleTargetLabel(): com.google.devtools.build.lib.cmdline.Label {
            return target
        }

        override fun getType(): Type {
            return com.google.devtools.build.lib.cmdline.TargetPattern.Type.SINGLE_TARGET
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is SingleTarget) {
                return false
            }
            val that = o
            return target == that.target
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(getType(), target)
        }

        override fun toString(): String {
            return toStringHelper().add("target", target).toString()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class InterpretPathAsTarget @com.google.common.annotations.VisibleForTesting constructor(
        originalPattern: String?,
        path: String?
    ) : TargetPattern(originalPattern) {
        private val path: String

        init {
            this.path = normalize(com.google.common.base.Preconditions.checkNotNull<String?>(path))
        }

        @Throws(
            TargetParsingException::class,
            E::class,
            java.lang.InterruptedException::class,
            InconsistentFilesystemException::class
        )
        override fun <T, E> eval(
            resolver: TargetPatternResolver<T?>,
            ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
            callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>,
            exceptionClass: java.lang.Class<E?>?
        ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
            val pathAsPackage: PackageIdentifier = PackageIdentifier.Companion.createInMainRepo(path)
            if (resolver.isPackage(pathAsPackage)) {
                // User has specified a package name. lookout for default target.
                callback.process(
                    resolver
                        .getExplicitTarget(
                            label(pathAsPackage, pathAsPackage.getPackageFragment().getBaseName())
                        )
                        .getTargets()
                )
            } else {
                val pieces: MutableList<String?> = SLASH_SPLITTER.splitToList(path)

                // Interprets the label as a file target.  This loop stops as soon as the
                // first BUILD file is found (i.e. longest prefix match).
                for (i in pieces.indices.reversed()) {
                    val pkg: PackageIdentifier =
                        PackageIdentifier.Companion.createInMainRepo(SLASH_JOINER.join(pieces.subList(0, i)))
                    if (resolver.isPackage(pkg)) {
                        val targetName: String = SLASH_JOINER.join(pieces.subList(i, pieces.size()))
                        callback.process(resolver.getExplicitTarget(label(pkg, targetName)).getTargets())
                        return
                    }
                }

                throw TargetParsingException(
                    "couldn't determine target from filename '" + path + "'",
                    Code.CANNOT_DETERMINE_TARGET_FROM_FILENAME
                )
            }
        }

        override fun getPathForPathAsTarget(): String {
            return path
        }

        override fun getRepository(): RepositoryName {
            // InterpretPathAsTarget is validated by PackageIdentifier.createInMainRepo,
            // therefore it must belong to the main repository.
            return RepositoryName.Companion.MAIN
        }

        override fun getRulesOnly(): Boolean {
            return false
        }

        override fun getType(): Type {
            return com.google.devtools.build.lib.cmdline.TargetPattern.Type.PATH_AS_TARGET
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is InterpretPathAsTarget) {
                return false
            }
            val that = o
            return path == that.path
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(getType(), path)
        }

        override fun toString(): String {
            return toStringHelper().add("path", path).toString()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class TargetsInPackage @com.google.common.annotations.VisibleForTesting constructor(
        originalPattern: String?,
        packageIdentifier: PackageIdentifier,
        suffix: String?,
        wasOriginallyAbsolute: Boolean,
        rulesOnly: Boolean
    ) : TargetPattern(originalPattern) {
        private val packageIdentifier: PackageIdentifier
        private val suffix: String
        private val wasOriginallyAbsolute: Boolean
        private val rulesOnly: Boolean

        init {
            this.packageIdentifier = packageIdentifier
            this.suffix = com.google.common.base.Preconditions.checkNotNull<String>(suffix)
            this.wasOriginallyAbsolute = wasOriginallyAbsolute
            this.rulesOnly = rulesOnly
        }

        @Throws(
            TargetParsingException::class,
            E::class,
            java.lang.InterruptedException::class,
            InconsistentFilesystemException::class
        )
        override fun <T, E> eval(
            resolver: TargetPatternResolver<T?>,
            ignoredSubdirectories: InterruptibleSupplier<IgnoredSubdirectories?>?,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>?,
            callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>,
            exceptionClass: java.lang.Class<E?>?
        ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
            val targets: ResolvedTargets<T?>? = getWildcardConflict<T?>(resolver)
            if (targets != null) {
                callback.process(targets.getTargets())
                return
            }

            callback.process(
                resolver.getTargetsInPackage(this.originalPattern, packageIdentifier, rulesOnly)
            )
        }

        override fun getDirectory(): PackageIdentifier {
            return packageIdentifier
        }

        override fun getRepository(): RepositoryName? {
            return packageIdentifier.getRepository()
        }

        override fun getRulesOnly(): Boolean {
            return rulesOnly
        }

        override fun getType(): Type {
            return com.google.devtools.build.lib.cmdline.TargetPattern.Type.TARGETS_IN_PACKAGE
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is TargetsInPackage) {
                return false
            }
            val that = o
            return wasOriginallyAbsolute == that.wasOriginallyAbsolute && rulesOnly == that.rulesOnly && this.originalPattern == that.originalPattern
                    && packageIdentifier == that.packageIdentifier
                    && suffix == that.suffix
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                getType(),
                this.originalPattern,
                packageIdentifier,
                suffix,
                wasOriginallyAbsolute,
                rulesOnly
            )
        }

        override fun toString(): String {
            return toStringHelper()
                .add("packageIdentifier", packageIdentifier)
                .add("suffix", suffix)
                .add("wasOriginallyAbsolute", wasOriginallyAbsolute)
                .add("rulesOnly", rulesOnly)
                .toString()
        }

        /**
         * There's a potential ambiguity if '//foo/bar:all' refers to an actual target. In this case, we
         * use the target but print a warning.
         * 
         * @return the Target corresponding to the given pattern, if the pattern is absolute and there
         * is such a target. Otherwise, return null.
         */
        @Throws(InconsistentFilesystemException::class, java.lang.InterruptedException::class)
        private fun <T> getWildcardConflict(resolver: TargetPatternResolver<T?>): ResolvedTargets<T?>? {
            if (!wasOriginallyAbsolute) {
                return null
            }

            val target: T?
            val label: com.google.devtools.build.lib.cmdline.Label?
            try {
                label = com.google.devtools.build.lib.cmdline.Label.Companion.create(packageIdentifier, suffix)
                target = resolver.getTargetOrNull(label)
            } catch (e: LabelSyntaxException) {
                return null
            }

            if (target != null) {
                resolver.warn(
                    java.lang.String.format(
                        ("The target pattern '%s' is ambiguous: '%s' is "
                                + "both a wildcard, and the name of an existing %s; "
                                + "using the latter interpretation"),
                        this.originalPattern, ":" + suffix, resolver.getTargetKind(target)
                    )
                )
                try {
                    return resolver.getExplicitTarget(label)
                } catch (e: TargetParsingException) {
                    throw java.lang.IllegalStateException(
                        "getTargetOrNull() returned non-null, so target should exist", e
                    )
                }
            }
            return null
        }
    }

    /**
     * Specialization of [TargetPattern] for [Type.TARGETS_BELOW_DIRECTORY]. Exposed
     * because it has a considerable number of specific methods. If [TargetPattern.getType]
     * returns [Type.TARGETS_BELOW_DIRECTORY] the instance can safely be cast to `TargetsBelowDirectory`.
     */
    class TargetsBelowDirectory @com.google.common.annotations.VisibleForTesting internal constructor(
        originalPattern: String?,
        directory: PackageIdentifier?,
        private val rulesOnly: Boolean
    ) : TargetPattern(originalPattern) {
        private val directory: PackageIdentifier

        init {
            this.directory = com.google.common.base.Preconditions.checkNotNull<PackageIdentifier>(directory)
        }

        @Throws(
            TargetParsingException::class,
            E::class,
            java.lang.InterruptedException::class,
            ProcessPackageDirectoryException::class
        )
        override fun <T, E> eval(
            resolver: TargetPatternResolver<T?>,
            ignoredSubdirectoriesSupplier: InterruptibleSupplier<IgnoredSubdirectories>,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>,
            callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
            exceptionClass: java.lang.Class<E?>?
        ) where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
            com.google.common.base.Preconditions.checkState(
                !excludedSubdirectories.contains(directory.getPackageFragment()),
                "Fully excluded target pattern %s should have already been filtered out (%s)",
                this,
                excludedSubdirectories
            )
            val ignoredSubdirectories: IgnoredSubdirectories = ignoredSubdirectoriesSupplier.get()
            val matchingEntry: String? = ignoredSubdirectories.matchingEntry(directory.getPackageFragment())
            if (warnIfFiltered(matchingEntry, resolver)) {
                return
            }

            val filteredIgnoredSubdirectories: IgnoredSubdirectories =
                ignoredSubdirectories.filterForDirectory(directory.getPackageFragment())

            resolver.findTargetsBeneathDirectory<E?>(
                directory.getRepository(),
                this.originalPattern,
                directory.getPackageFragment().getPathString(),
                rulesOnly,
                filteredIgnoredSubdirectories,
                excludedSubdirectories,
                callback,
                exceptionClass
            )
        }

        override fun <T, E>
                evalAsync(
            resolver: TargetPatternResolver<T?>,
            ignoredSubdirectoriesSupplier: InterruptibleSupplier<IgnoredSubdirectories>,
            excludedSubdirectories: com.google.common.collect.ImmutableSet<PathFragment?>,
            callback: com.google.devtools.build.lib.cmdline.BatchCallback<T?, E?>?,
            exceptionClass: java.lang.Class<E?>?,
            executor: com.google.common.util.concurrent.ListeningExecutorService?
        ): com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>? where E : java.lang.Exception?, E : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
            com.google.common.base.Preconditions.checkState(
                !excludedSubdirectories.contains(directory.getPackageFragment()),
                "Fully excluded target pattern %s should have already been filtered out (%s)",
                this,
                excludedSubdirectories
            )
            val filteredIgnoredSubdirectories: IgnoredSubdirectories?
            try {
                val ignoredSubdirectories: IgnoredSubdirectories = ignoredSubdirectoriesSupplier.get()
                val matchingEntry: String? = ignoredSubdirectories.matchingEntry(directory.getPackageFragment())
                if (warnIfFiltered(matchingEntry, resolver)) {
                    return com.google.common.util.concurrent.Futures.immediateVoidFuture()
                }
                filteredIgnoredSubdirectories =
                    ignoredSubdirectories.filterForDirectory(directory.getPackageFragment())
            } catch (e: java.lang.InterruptedException) {
                return com.google.common.util.concurrent.Futures.immediateCancelledFuture<java.lang.Void?>()
            }
            return resolver.findTargetsBeneathDirectoryAsync<E?>(
                directory.getRepository(),
                this.originalPattern,
                directory.getPackageFragment().getPathString(),
                rulesOnly,
                filteredIgnoredSubdirectories,
                excludedSubdirectories,
                callback,
                exceptionClass,
                executor
            )
        }

        private fun warnIfFiltered(matchingEntry: String?, resolver: TargetPatternResolver<*>): Boolean {
            if (matchingEntry != null) {
                resolver.warn(
                    ("Pattern '"
                            + this.originalPattern
                            + "' was filtered out by ignored directory '"
                            + matchingEntry
                            + "'")
                )
                return true
            }
            return false
        }

        /** Is `containingDirectory` an ancestor of or equal to this [.directory]?  */
        fun containedIn(containingDirectory: PathFragment?): Boolean {
            return directory.getPackageFragment().startsWith(containingDirectory)
        }

        /**
         * Returns true if `containedDirectory` is contained by or equals this pattern's
         * directory.
         * 
         * 
         * For example, returns `true` for `this = TargetPattern ("//...")` and `directory = "foo")`.
         */
        fun containsAllTransitiveSubdirectories(containedDirectory: PackageIdentifier): Boolean {
            // Note that merely checking to see if the directory startsWith the TargetsBelowDirectory's
            // directory is insufficient. "food" begins with "foo", but "//foo/..." does not contain
            // "//food/...".
            return containedDirectory.getRepository() == directory.getRepository()
                    && containedDirectory.getPackageFragment().startsWith(directory.getPackageFragment())
        }

        /**
         * Determines how, if it all, the evaluation of this pattern with a directory exclusion of the
         * given `containedPattern`'s directory relates to the evaluation of the subtraction of
         * the given `containedPattern` from this one.
         */
        fun contains(containedPattern: TargetsBelowDirectory): ContainsResult {
            if (containsAllTransitiveSubdirectories(containedPattern.directory)) {
                return if (!rulesOnly && containedPattern.rulesOnly)
                    ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_TOO_BROAD
                else
                    ContainsResult.DIRECTORY_EXCLUSION_WOULD_BE_EXACT
            } else {
                return ContainsResult.NOT_CONTAINED
            }
        }

        /** A tristate return value for [.contains].  */
        enum class ContainsResult {
            /**
             * Evaluating this pattern with a directory exclusion of the other pattern's directory would
             * result in exactly the same set of targets as evaluating the subtraction of the other
             * pattern from this one.
             */
            DIRECTORY_EXCLUSION_WOULD_BE_EXACT,

            /**
             * A directory exclusion of the other pattern's directory would be too broad because this
             * pattern is not "rules only" and the other one is, meaning that this pattern potentially
             * matches more targets underneath the directory in question than the other one does. Thus, a
             * directory exclusion would incorrectly exclude non-rule targets.
             */
            DIRECTORY_EXCLUSION_WOULD_BE_TOO_BROAD,

            /** None of the above. The other pattern isn't contained by this pattern.  */
            NOT_CONTAINED,
        }

        override fun getDirectory(): PackageIdentifier {
            return directory
        }

        override fun getRepository(): RepositoryName? {
            return directory.getRepository()
        }

        override fun getRulesOnly(): Boolean {
            return rulesOnly
        }

        override fun getType(): Type {
            return com.google.devtools.build.lib.cmdline.TargetPattern.Type.TARGETS_BELOW_DIRECTORY
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is TargetsBelowDirectory) {
                return false
            }
            val that = o
            return rulesOnly == that.rulesOnly && this.originalPattern == that.originalPattern
                    && directory == that.directory
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(getType(), this.originalPattern, directory, rulesOnly)
        }

        override fun toString(): String {
            return toStringHelper().add("directory", directory).add("rulesOnly", rulesOnly).toString()
        }
    }

    @javax.annotation.concurrent.Immutable
    class Parser(
        relativeDirectory: PathFragment,
        currentRepo: RepositoryName,
        repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping
    ) {
        /**
         * Directory prefix to use when resolving relative labels (rather than absolute ones). For
         * example, if the working directory is "<workspace root>/foo", then this should be "foo", which
         * will make patterns such as "bar:bar" be resolved as "//foo/bar:bar". This makes the command
         * line a bit more convenient to use.
        </workspace> */
        private val relativeDirectory: PathFragment

        // The repo to use for any repo-relative target patterns (so "//foo" becomes
        // "@currentRepo//foo").
        private val currentRepo: RepositoryName

        // The repo mapping to use for the @repo part of target patterns.
        private val repoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping

        /** Creates a new parser with the given offset for relative patterns.  */
        init {
            com.google.common.base.Preconditions.checkArgument(
                currentRepo.isMain() || relativeDirectory.isEmpty(),
                "parsing target patterns in a non-main repo with a relative directory is unsupported"
            )
            this.relativeDirectory = relativeDirectory
            this.currentRepo = currentRepo
            this.repoMapping = repoMapping
        }

        /**
         * Parses the given pattern, and throws an exception if the pattern is invalid.
         * 
         * @return a target pattern corresponding to the pattern parsed
         * @throws TargetParsingException if the pattern is invalid
         */
        @Throws(TargetParsingException::class)
        fun parse(pattern: String): TargetPattern {
            val parts: Parts?
            try {
                parts = Parts.Companion.parse(pattern)
            } catch (e: LabelSyntaxException) {
                throw TargetParsingException(e.getMessage(), TargetPatterns.Code.LABEL_SYNTAX_ERROR)
            }

            // Special case: For a target pattern that just looks like `foo/bar/baz`, we treat this as a
            // file path. LabelParser parses it as `:foo/bar/baz`, so we need to distinguish this case by
            // checking if the original pattern contains a colon.
            if (!parts.pkgIsAbsolute() && currentRepo.isMain()
                && parts.pkg().isEmpty()
                && !parts.pkgEndsWithTripleDots() && !pattern.contains(":")
            ) {
                return InterpretPathAsTarget(
                    pattern, relativeDirectory.getRelative(parts.target()).getPathString()
                )
            }

            val packageIdentifier: PackageIdentifier = createPackageIdentifierFromParts(parts)
            if (parts.pkgEndsWithTripleDots()) {
                if (parts.target()
                        .isEmpty() || com.google.devtools.build.lib.cmdline.TargetPattern.Parser.Companion.ALL_RULES_IN_SUFFIXES.contains(
                        parts.target()
                    )
                ) {
                    return TargetsBelowDirectory(pattern, packageIdentifier, true)
                } else if (com.google.devtools.build.lib.cmdline.TargetPattern.Parser.Companion.ALL_TARGETS_IN_SUFFIXES.contains(
                        parts.target()
                    )
                ) {
                    return TargetsBelowDirectory(pattern, packageIdentifier, false)
                }
                throw TargetParsingException(
                    "Invalid target pattern " + pattern + ": '...' can only be used with wildcard targets",
                    Code.LABEL_SYNTAX_ERROR
                )
            }

            if (pattern.contains(":") && com.google.devtools.build.lib.cmdline.TargetPattern.Parser.Companion.ALL_RULES_IN_SUFFIXES.contains(
                    parts.target()
                )
            ) {
                return TargetsInPackage(
                    pattern, packageIdentifier, parts.target(), parts.pkgIsAbsolute(), true
                )
            }

            if (pattern.contains(":") && com.google.devtools.build.lib.cmdline.TargetPattern.Parser.Companion.ALL_TARGETS_IN_SUFFIXES.contains(
                    parts.target()
                )
            ) {
                return TargetsInPackage(
                    pattern, packageIdentifier, parts.target(), parts.pkgIsAbsolute(), false
                )
            }

            return SingleTarget(
                pattern,
                com.google.devtools.build.lib.cmdline.Label.Companion.createUnvalidated(
                    packageIdentifier,
                    parts.target()
                )
            )
        }

        private fun createPackageIdentifierFromParts(parts: Parts): PackageIdentifier {
            val repo: RepositoryName?
            if (parts.repo() == null) {
                repo = currentRepo
            } else if (parts.repoIsCanonical()) {
                repo = RepositoryName.Companion.createUnvalidated(parts.repo())
            } else {
                repo = repoMapping.get(parts.repo())
            }

            val packagePathFragment: PathFragment? =
                if (parts.pkgIsAbsolute())
                    PathFragment.create(parts.pkg())
                else
                    relativeDirectory.getRelative(parts.pkg())
            return PackageIdentifier.Companion.create(repo, packagePathFragment)
        }

        fun getRepoMapping(): com.google.devtools.build.lib.cmdline.RepositoryMapping {
            return repoMapping
        }

        fun getCurrentRepo(): RepositoryName {
            return currentRepo
        }

        fun getRelativeDirectory(): PathFragment {
            return relativeDirectory
        }

        /**
         * Parses a constant string TargetPattern, throwing IllegalStateException on invalid pattern.
         */
        @com.google.errorprone.annotations.CheckReturnValue
        fun parseConstantUnchecked(@com.google.errorprone.annotations.CompileTimeConstant pattern: String): TargetPattern {
            try {
                return parse(pattern)
            } catch (e: TargetParsingException) {
                throw java.lang.IllegalStateException(e)
            }
        }

        /**
         * Absolutizes the target pattern to the offset. Patterns starting with "//" are absolute and
         * not modified. Assumes the given pattern is not invalid wrt leading "/"s.
         * 
         * 
         * If the offset is "foo": absolutize(":bar") --> "//foo:bar" absolutize("bar") -->
         * "//foo/bar" absolutize("//biz/bar") --> "//biz/bar" (absolute) absolutize("biz:bar") -->
         * "//foo/biz:bar"
         * 
         * @param pattern The target pattern to parse.
         * @return the pattern, absolutized to the offset if approprate.
         */
        fun absolutize(pattern: String): String {
            if (pattern.startsWith("//")) {
                return pattern
            }

            // PathFragment#getRelative doesn't work when the pattern starts with ":".
            // "foo".getRelative(":all") would return "foo/:all", where we really want "foo:all".
            return if (pattern.startsWith(":") || relativeDirectory.isEmpty())
                "//" + relativeDirectory.getPathString() + pattern
            else
                "//" + relativeDirectory.getPathString() + "/" + pattern
        }

        companion object {
            /**
             * The set of target-pattern suffixes which indicate wildcards over all *rules* in a
             * single package.
             */
            private val ALL_RULES_IN_SUFFIXES: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>("all")

            /**
             * The set of target-pattern suffixes which indicate wildcards over all *targets* in a
             * single package.
             */
            private val ALL_TARGETS_IN_SUFFIXES: com.google.common.collect.ImmutableList<String?> =
                com.google.common.collect.ImmutableList.of<String?>("*", "all-targets")
        }
    }

    /** The target pattern type (targets below package, in package, explicit target, etc.)  */
    enum class Type {
        /** A path interpreted as a target, eg "foo/bar/baz"  */
        PATH_AS_TARGET,

        /** An explicit target, eg "//foo:bar."  */
        SINGLE_TARGET,

        /** Targets below a directory, eg "foo/...".  */
        TARGETS_BELOW_DIRECTORY,

        /** Target in a package, eg "foo:all".  */
        TARGETS_IN_PACKAGE
    }

    companion object {
        private val SLASH_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on('/')
        private val SLASH_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on('/')

        private val DEFAULT_PARSER = mainRepoParser(PathFragment.EMPTY_FRAGMENT)

        /**
         * Returns a parser defaulting to the main repo, with no offset or repo mapping. Note that the
         * Parser class is immutable, so this method may return the same instance on subsequent calls.
         */
        @kotlin.jvm.JvmStatic
        fun defaultParser(): Parser {
            return DEFAULT_PARSER
        }

        /**
         * Returns a parser defaulting to the main repo, with repo mapping, but using the given offset.
         */
        // NOTE(wyv): This is only strictly correct within a monorepo. If external repos exist, there
        // should always be a proper repo mapping. We should audit calls to this function and add a repo
        // mapping wherever appropriate.
        fun mainRepoParser(offset: PathFragment): Parser {
            return com.google.devtools.build.lib.cmdline.TargetPattern.Parser(
                offset,
                RepositoryName.Companion.MAIN,
                com.google.devtools.build.lib.cmdline.RepositoryMapping.Companion.EMPTY
            )
        }

        /**
         * Normalizes the given relative path by resolving `//`, `/./` and `x/../`
         * pieces. Note that leading `".."` segments are not removed, so the returned string can
         * have leading `".."` segments.
         * 
         * @throws IllegalArgumentException if the path is absolute, i.e. starts with `/`
         */
        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun normalize(path: String): String {
            com.google.common.base.Preconditions.checkArgument(!path.startsWith("/"), path)
            com.google.common.base.Preconditions.checkArgument(!path.startsWith("@"), path)
            val it: MutableIterator<String> = SLASH_SPLITTER.split(path).iterator()
            val pieces: MutableList<String?> = java.util.ArrayList<String?>()
            while (it.hasNext()) {
                val piece = it.next()
                if ("." == piece || piece.isEmpty()) {
                    continue
                }
                if (".." == piece) {
                    if (pieces.isEmpty()) {
                        pieces.add(piece)
                        continue
                    }
                    val predecessor: String? = pieces.remove(pieces.size() - 1)
                    if (".." == predecessor) {
                        pieces.add(piece)
                        pieces.add(piece)
                    }
                    continue
                }
                pieces.add(piece)
            }
            return SLASH_JOINER.join(pieces)
        }

        // Creates a label from parts, mapping LabelSyntaxException into TargetParsingException.
        @Throws(TargetParsingException::class)
        private fun label(pkg: PackageIdentifier?, targetName: String?): com.google.devtools.build.lib.cmdline.Label? {
            try {
                return com.google.devtools.build.lib.cmdline.Label.Companion.create(pkg, targetName)
            } catch (e: LabelSyntaxException) {
                throw TargetParsingException(
                    ("invalid target name: '"
                            + com.google.devtools.build.lib.util.StringUtilities.sanitizeControlChars(targetName)
                            + "'; "
                            + com.google.devtools.build.lib.util.StringUtilities.sanitizeControlChars(e.getMessage())),
                    TargetPatterns.Code.TARGET_FORMAT_INVALID
                )
            }
        }
    }
}
