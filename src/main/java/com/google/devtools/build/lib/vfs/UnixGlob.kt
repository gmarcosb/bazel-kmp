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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.profiler.Profiler

/**
 * Implementation of a subset of UNIX-style file globbing, expanding "*" and "?" as wildcards, but
 * not [a-z] ranges.
 * 
 * 
 * `**` gets special treatment in include patterns. If it is used as a complete path
 * segment it matches the filenames in subdirectories recursively.
 * 
 * 
 * Importantly, note that the glob matches are in an unspecified order.
 */
object UnixGlob {
    private val DEFAULT_DISCRIMINATOR: UnixGlobPathDiscriminator = object : UnixGlobPathDiscriminator {}

    @Throws(IOException::class, java.lang.InterruptedException::class, BadPattern::class)
    private fun globInternal(
        base: com.google.devtools.build.lib.vfs.Path,
        patterns: MutableCollection<String>,
        pathDiscriminator: UnixGlobPathDiscriminator,
        filesystemOps: FilesystemOps,
        executor: java.util.concurrent.Executor?
    ): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
        val visitor = GlobVisitor(executor)
        return visitor.glob(base, patterns, pathDiscriminator, filesystemOps)
    }

    @Throws(IOException::class, BadPattern::class)
    private fun globInternalUninterruptible(
        base: com.google.devtools.build.lib.vfs.Path,
        patterns: MutableCollection<String>,
        pathDiscriminator: UnixGlobPathDiscriminator,
        filesystemOps: FilesystemOps,
        executor: java.util.concurrent.Executor?
    ): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
        val visitor = GlobVisitor(executor)
        return visitor.globUninterruptible(base, patterns, pathDiscriminator, filesystemOps)
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, BadPattern::class)
    private fun globInternalAndReturnNumGlobTasksForTesting(
        base: com.google.devtools.build.lib.vfs.Path,
        patterns: MutableCollection<String>,
        pathDiscriminator: UnixGlobPathDiscriminator,
        filesystemOps: FilesystemOps,
        executor: java.util.concurrent.Executor?
    ): Long {
        val visitor = GlobVisitor(executor)
        val unused: MutableList<com.google.devtools.build.lib.vfs.Path?>? =
            visitor.glob(base, patterns, pathDiscriminator, filesystemOps)
        return visitor.numGlobTasksForTesting
    }

    @Throws(BadPattern::class)
    private fun globAsyncInternal(
        base: com.google.devtools.build.lib.vfs.Path,
        patterns: MutableCollection<String>,
        pathDiscriminator: UnixGlobPathDiscriminator,
        filesystemOps: FilesystemOps,
        executor: java.util.concurrent.Executor?
    ): java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>?> {
        com.google.common.base.Preconditions.checkNotNull<java.util.concurrent.Executor?>(
            executor,
            "%s %s",
            base,
            patterns
        )
        return GlobVisitor(executor).globAsync(base, patterns, pathDiscriminator, filesystemOps)
    }

    /**
     * Checks that each pattern is valid, splits it into segments and checks that each segment
     * contains only valid wildcards.
     * 
     * @throws BadPattern on encountering a malformed pattern.
     * @return list of segment arrays
     */
    @Throws(BadPattern::class)
    private fun checkAndSplitPatterns(patterns: MutableCollection<String>): MutableList<Array<String?>?> {
        val list: MutableList<Array<String?>?> =
            com.google.common.collect.Lists.newArrayListWithCapacity<Array<String?>?>(patterns.size())
        for (pattern in patterns) {
            val error = checkPatternForError(pattern)
            if (error != null) {
                throw BadPattern(error + " (in glob pattern '" + pattern + "')")
            }
            val segments: Iterable<String> = com.google.common.base.Splitter.on('/').split(pattern)
            list.add(com.google.common.collect.Iterables.toArray<String?>(segments, String::class.java))
        }
        return list
    }

    /**
     * @return whether or not `pattern` contains illegal characters
     */
    fun checkPatternForError(pattern: String): String? {
        if (pattern.isEmpty()) {
            return "pattern cannot be empty"
        }
        if (pattern.charAt(0) == '/') {
            return "pattern cannot be absolute"
        }
        val segments: Iterable<String> = com.google.common.base.Splitter.on('/').split(pattern)
        for (segment in segments) {
            if (segment.isEmpty()) {
                return "empty segment not permitted"
            }
            if (segment == "." || segment == "..") {
                return "segment '" + segment + "' not permitted"
            }
            if (segment.contains("**") && segment != "**") {
                return "recursive wildcard must be its own segment"
            }
        }
        return null
    }

    /** Returns whether `pattern` matches `path`.  */
    fun matches(pattern: Array<String?>, path: Array<String?>): Boolean {
        return matchesPattern(pattern, path, 0, 0, null, MatchMode.EXACT)
    }

    /**
     * Returns whether `str` matches the glob pattern `pattern`. This method may use the
     * `patternCache` to speed up the matching process.
     * 
     * @param pattern a glob pattern
     * @param str the string to match
     * @param patternCache a cache from patterns to compiled Pattern objects, or `null` to skip
     * caching
     */
    /** Calls [matches(pattern, str, null)][.matches]  */
    @kotlin.jvm.JvmOverloads
    fun matches(
        pattern: String,
        str: String,
        patternCache: MutableMap<String?, java.util.regex.Pattern?>? = null
    ): Boolean {
        if (pattern.length() == 0 || str.length() == 0) {
            return false
        }

        // Common case: **
        if (pattern == "**") {
            return true
        }

        // Common case: *
        if (pattern == "*") {
            return true
        }

        // If a filename starts with '.', this char must be matched explicitly.
        if (str.charAt(0) == '.' && pattern.charAt(0) != '.') {
            return false
        }

        // Common case: *.xyz
        if (pattern.charAt(0) == '*' && pattern.lastIndexOf('*'.code) == 0) {
            return str.endsWith(pattern.substring(1))
        }
        // Common case: xyz*
        val lastIndex: Int = pattern.length() - 1
        // The first clause of this if statement is unnecessary, but is an
        // optimization--charAt runs faster than indexOf.
        if (pattern.charAt(lastIndex) == '*' && pattern.indexOf('*'.code) == lastIndex) {
            return str.startsWith(pattern.substring(0, lastIndex))
        }

        val regex: java.util.regex.Pattern =
            if (patternCache == null)
                makePatternFromWildcard(pattern)
            else
                patternCache.computeIfAbsent(
                    pattern,
                    java.util.function.Function { p: String? -> UnixGlob.makePatternFromWildcard(p!!) })
        return regex.matcher(str).matches()
    }

    /**
     * Returns a regular expression implementing a matcher for "pattern", in which
     * "*" and "?" are wildcards.
     * 
     * 
     * e.g. "foo*bar?.java" -> "foo.*bar.\\.java"
     */
    private fun makePatternFromWildcard(pattern: String): java.util.regex.Pattern {
        val regexp: java.lang.StringBuilder = java.lang.StringBuilder()
        var i = 0
        val len: Int = pattern.length()
        while (i < len) {
            val c: Char = pattern.charAt(i)
            when (c) {
                '*' -> {
                    val toIncrement = 0
                    if (len > i + 1 && pattern.charAt(i + 1) == '*') {
                        // The pattern '**' is interpreted to match 0 or more directory separators, not 1 or
                        // more. We skip the next * and then find a trailing/leading '/' and get rid of it.
                        toIncrement = 1
                        if (len > i + 2 && pattern.charAt(i + 2) == '/') {
                            // We have '**/' -- skip the '/'.
                            toIncrement = 2
                        } else if (len == i + 2 && i > 0 && pattern.charAt(i - 1) == '/') {
                            // We have '/**' -- remove the '/'.
                            regexp.delete(regexp.length() - 1, regexp.length())
                        }
                    }
                    regexp.append(".*")
                    i += toIncrement
                }

                '?' -> regexp.append('.')
                '^', '$', '|', '+', '{', '}', '[', ']', '\\', '.' -> {
                    // escape the regexp special characters that are allowed in wildcards
                    regexp.append('\\')
                    regexp.append(c)
                }

                '(', ')' -> {}
                else -> regexp.append(c)
            }
            i++
        }
        return java.util.regex.Pattern.compile(regexp.toString())
    }

    /**
     * Filters out exclude patterns from a Set of paths. Common cases such as wildcard-free patterns
     * or suffix patterns are special-cased to make this function efficient.
     */
    @Throws(BadPattern::class)
    fun removeExcludes(paths: MutableSet<String?>, excludes: MutableCollection<String>) {
        val complexPatterns: java.util.ArrayList<String> = java.util.ArrayList<String>(excludes.size())
        val starstarSlashStarHeadTailPairs: MutableMap<String?, MutableList<String?>?> =
            HashMap<String?, MutableList<String?>?>()
        for (exclude in excludes) {
            if (isWildcardFree(exclude)) {
                paths.remove(exclude)
                continue
            }
            val patternPos: Int = exclude.indexOf("**/*")
            if (patternPos != -1) {
                val head: String = exclude.substring(0, patternPos)
                val tail: String = exclude.substring(patternPos + 4)
                if (isWildcardFree(head) && isWildcardFree(tail)) {
                    starstarSlashStarHeadTailPairs.computeIfAbsent(
                        head,
                        java.util.function.Function { h: String? -> java.util.ArrayList<String?>() }).add(tail)
                    continue
                }
            }
            complexPatterns.add(exclude)
        }
        for (headTailPair in starstarSlashStarHeadTailPairs.entrySet()) {
            paths.removeIf(
                java.util.function.Predicate { path: String? ->
                    if (path.startsWith(headTailPair.getKey())) {
                        for (tail in headTailPair.getValue()) {
                            if (path.endsWith(tail)) {
                                return@removeIf true
                            }
                        }
                    }
                    false
                })
        }
        if (complexPatterns.isEmpty()) {
            return
        }
        // TODO: b/361409364 - Fully validate exclude patterns. This is a breaking change, so there
        // needs to first be a depot cleanup.
        val splitPatterns = checkAndSplitPatterns(complexPatterns)
        val patternCache: HashMap<String?, java.util.regex.Pattern?> = HashMap<String?, java.util.regex.Pattern?>()
        paths.removeIf(
            java.util.function.Predicate { path: String? ->
                val segments: Array<String?> = com.google.common.collect.Iterables.toArray<String?>(
                    com.google.common.base.Splitter.on('/').split(path), String::class.java
                )
                for (splitPattern in splitPatterns) {
                    if (UnixGlob.matchesPattern(splitPattern!!, segments, 0, 0, patternCache, MatchMode.EXACT)) {
                        return@removeIf true
                    }
                }
                false
            })
    }

    /** Returns whether any path under `path` can match `pattern`.  */
    fun canMatchChild(pattern: Array<String?>, path: Array<String?>): Boolean {
        return matchesPattern(pattern, path, 0, 0, null, MatchMode.CAN_MATCH_CHILD)
    }

    /** Returns whether `pattern` matches a prefix of `path`.  */
    fun matchesPrefix(pattern: Array<String?>, path: Array<String?>): Boolean {
        return matchesPattern(pattern, path, 0, 0, null, MatchMode.PREFIX)
    }

    /** Returns true if `pattern` matches `path` starting from the given segments.  */
    private fun matchesPattern(
        pattern: Array<String?>,
        path: Array<String?>,
        i: Int,
        j: Int,
        patternCache: MutableMap<String?, java.util.regex.Pattern?>?,
        matchMode: MatchMode?
    ): Boolean {
        if (i == pattern.size) {
            return matchMode == MatchMode.PREFIX || j == path.size
        }
        if (pattern[i] == "**") {
            return matchesPattern(pattern, path, i + 1, j, patternCache, matchMode)
                    || (j < path.size && matchesPattern(pattern, path, i, j + 1, patternCache, matchMode))
        }
        if (j == path.size) {
            return matchMode == MatchMode.CAN_MATCH_CHILD
        }
        if (UnixGlob.matches(pattern[i]!!, path[j]!!, patternCache)) {
            return matchesPattern(pattern, path, i + 1, j + 1, patternCache, matchMode)
        }
        return false
    }

    private fun isWildcardFree(pattern: String): Boolean {
        return !pattern.contains("*") && !pattern.contains("?")
    }

    /** Indicates an invalid glob pattern.  */
    class BadPattern private constructor(message: String?) : java.lang.Exception(message)

    /** Narrow interface of filesystem operations needed by [UnixGlob].  */
    interface FilesystemOps {
        /** Returns the stat() for the given path, or null. Follows symlinks.  */
        @Throws(IOException::class)
        fun statIfFound(path: com.google.devtools.build.lib.vfs.Path?): FileStatus?

        /** Gets directory entries and their types. Does not follow symlinks.  */
        @Throws(IOException::class)
        fun readdir(path: com.google.devtools.build.lib.vfs.Path?): MutableCollection<com.google.devtools.build.lib.vfs.Dirent>?

        companion object {
            @kotlin.jvm.JvmField
            val DIRECT: FilesystemOps = object : FilesystemOps {
                @Throws(IOException::class)
                override fun statIfFound(path: com.google.devtools.build.lib.vfs.Path): FileStatus? {
                    return path.statIfFound(Symlinks.NOFOLLOW)
                }

                @Throws(IOException::class)
                override fun readdir(path: com.google.devtools.build.lib.vfs.Path): MutableCollection<com.google.devtools.build.lib.vfs.Dirent?>? {
                    return path.readdir(Symlinks.NOFOLLOW)
                }
            }
        }
    }

    /**
     * Builder class for UnixGlob.
     * 
     * 
     */
    class Builder(base: com.google.devtools.build.lib.vfs.Path, filesystemOps: FilesystemOps) {
        private val base: com.google.devtools.build.lib.vfs.Path
        private val patterns: MutableList<String>
        private val filesystemOps: FilesystemOps
        private var pathDiscriminator: UnixGlobPathDiscriminator = DEFAULT_DISCRIMINATOR
        private var executor: java.util.concurrent.Executor? = null

        /** Creates a glob builder with the given base path.  */
        init {
            this.base = base
            this.filesystemOps = filesystemOps
            this.patterns = java.util.ArrayList<String>()
        }

        /**
         * Adds a pattern to include to the glob builder.
         * 
         * 
         * For a description of the syntax of the patterns, see [UnixGlob].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPattern(pattern: String?): Builder {
            this.patterns.add(pattern!!)
            return this
        }

        /**
         * Adds a pattern to include to the glob builder.
         * 
         * 
         * For a description of the syntax of the patterns, see [UnixGlob].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPatterns(vararg patterns: String?): Builder {
            Collections.addAll<String?>(this.patterns, *patterns)
            return this
        }

        /**
         * Adds a pattern to include to the glob builder.
         * 
         * 
         * For a description of the syntax of the patterns, see [UnixGlob].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPatterns(patterns: MutableCollection<String?>?): Builder {
            this.patterns.addAll(patterns)
            return this
        }

        /**
         * Sets the executor to use for parallel glob evaluation. If unset, evaluation is done
         * in-thread.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExecutor(pool: java.util.concurrent.Executor?): Builder {
            this.executor = pool
            return this
        }

        /**
         * Sets the UnixGlobPathDiscriminator which determines how to handle Path entries encountered
         * during glob traversal. The interface determines if Paths should be added to the `List<Path>` results and whether to traverse a given directory during recursion.
         * 
         * 
         * The UnixGlobPathDiscriminator should only be called with Paths that have been resolved to
         * a regular file or regular directory, it will not properly handle symlinks or special files.
         * 
         * 
         * This is used for handling the previous use case of 'excludeDirectories' where we wish to
         * exclude files from the glob and decide which directories to traverse, like skipping sub-dirs
         * containing BUILD files.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setPathDiscriminator(pathDiscriminator: UnixGlobPathDiscriminator): Builder {
            this.pathDiscriminator = pathDiscriminator
            return this
        }

        /** Executes the glob.  */
        @Throws(IOException::class, BadPattern::class)
        fun glob(): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
            return globInternalUninterruptible(
                base, patterns, pathDiscriminator, filesystemOps, executor
            )
        }

        /**
         * Executes the glob and returns the result.
         * 
         * @throws InterruptedException if the thread is interrupted.
         */
        @Throws(IOException::class, java.lang.InterruptedException::class, BadPattern::class)
        fun globInterruptible(): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
            return globInternal(base, patterns, pathDiscriminator, filesystemOps, executor)
        }

        @com.google.common.annotations.VisibleForTesting
        @Throws(IOException::class, java.lang.InterruptedException::class, BadPattern::class)
        fun globInterruptibleAndReturnNumGlobTasksForTesting(): Long {
            return globInternalAndReturnNumGlobTasksForTesting(
                base, patterns, pathDiscriminator, filesystemOps, executor
            )
        }

        /**
         * Executes the glob asynchronously. [.setExecutor] must have been called already with a
         * non-null argument.
         */
        @Throws(BadPattern::class)
        fun globAsync(): java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>?> {
            return globAsyncInternal(base, patterns, pathDiscriminator, filesystemOps, executor)
        }
    }

    /**
     * Adapts the result of the glob visitation as a Future.
     */
    private class GlobFuture(private val visitor: GlobVisitor) :
        com.google.common.util.concurrent.ForwardingListenableFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?>() {
        private val delegate: com.google.common.util.concurrent.SettableFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?> =
            com.google.common.util.concurrent.SettableFuture.create<MutableList<com.google.devtools.build.lib.vfs.Path?>?>()

        override fun delegate(): com.google.common.util.concurrent.ListenableFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?> {
            return delegate
        }

        fun setException(throwable: Throwable) {
            delegate.setException(throwable)
        }

        fun set(paths: MutableList<com.google.devtools.build.lib.vfs.Path?>?) {
            delegate.set(paths)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            // Best-effort interrupt of the in-flight visitation.
            visitor.cancel()
            return true
        }

        fun markCanceled() {
            super.cancel(true)
        }
    }

    /**
     * GlobVisitor executes a glob using parallelism, which is useful when
     * the glob() requires many readdir() calls on high latency filesystems.
     */
    private class GlobVisitor(executor: java.util.concurrent.Executor?) {
        // These collections are used across workers and must therefore be thread-safe.
        private val results: MutableCollection<com.google.devtools.build.lib.vfs.Path?> =
            com.google.common.collect.Sets.newConcurrentHashSet<com.google.devtools.build.lib.vfs.Path?>()
        private val cache: ConcurrentHashMap<String?, java.util.regex.Pattern?> =
            ConcurrentHashMap<String?, java.util.regex.Pattern?>()

        private val result: GlobFuture
        private val executor: java.util.concurrent.Executor?
        private val totalOps: AtomicLong = AtomicLong(0)
        private val pendingOps: AtomicLong = AtomicLong(0)
        private val ioException: AtomicReference<IOException?> = AtomicReference<IOException?>()
        private val runtimeException: AtomicReference<java.lang.RuntimeException?> =
            AtomicReference<java.lang.RuntimeException?>()
        private val error: AtomicReference<java.lang.Error?> = AtomicReference<java.lang.Error?>()

        @kotlin.concurrent.Volatile
        private var canceled = false

        init {
            this.executor = executor
            this.result = GlobFuture(this)
        }

        /**
         * Performs wildcard globbing: returns the list of filenames that match any of `patterns`
         * relative to `base`. Directories are traversed if and only if they return true from
         * `pathDiscriminator.shouldTraverseDirectory`. The predicate is also called for the root
         * of the traversal. `pathDiscriminator.shouldIncludePathInResult` is called to determine
         * if a directory result should be included in the output. The The order of the returned list is
         * unspecified.
         * 
         * 
         * Patterns may include "*" and "?", but not "[a-z]".
         * 
         * 
         * `**` gets special treatment in include patterns. If it is used as a complete
         * path segment it matches the filenames in subdirectories recursively.
         * 
         * @throws IllegalArgumentException if any glob pattern [     ][.checkPatternForError] or if any include pattern segment contains
         * `**` but not equal to it.
         */
        @Throws(IOException::class, java.lang.InterruptedException::class, BadPattern::class)
        fun glob(
            base: com.google.devtools.build.lib.vfs.Path,
            patterns: MutableCollection<String>,
            pathDiscriminator: UnixGlobPathDiscriminator,
            filesystemOps: FilesystemOps
        ): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
            try {
                return globAsync(base, patterns, pathDiscriminator, filesystemOps).get()
            } catch (e: ExecutionException) {
                val cause: Throwable = e.getCause()
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                com.google.common.base.Throwables.throwIfUnchecked(cause)
                throw java.lang.RuntimeException(e)
            }
        }

        @Throws(IOException::class, BadPattern::class)
        fun globUninterruptible(
            base: com.google.devtools.build.lib.vfs.Path,
            patterns: MutableCollection<String>,
            pathDiscriminator: UnixGlobPathDiscriminator,
            filesystemOps: FilesystemOps
        ): MutableList<com.google.devtools.build.lib.vfs.Path?>? {
            try {
                return com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly<MutableList<com.google.devtools.build.lib.vfs.Path?>?>(
                    globAsync(base, patterns, pathDiscriminator, filesystemOps)
                )
            } catch (e: ExecutionException) {
                val cause: Throwable = e.getCause()
                com.google.common.base.Throwables.throwIfInstanceOf<IOException?>(cause, IOException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<BadPattern?>(cause, BadPattern::class.java)
                com.google.common.base.Throwables.throwIfUnchecked(cause)
                throw java.lang.RuntimeException(e)
            }
        }

        /**
         * Same as [.glob], except does so asynchronously and returns a [Future] for the
         * result.
         */
        @Throws(BadPattern::class)
        fun globAsync(
            base: com.google.devtools.build.lib.vfs.Path,
            patterns: MutableCollection<String>,
            pathDiscriminator: UnixGlobPathDiscriminator,
            filesystemOps: FilesystemOps
        ): java.util.concurrent.Future<MutableList<com.google.devtools.build.lib.vfs.Path?>?> {
            val baseStat: FileStatus?
            try {
                baseStat = filesystemOps.statIfFound(base)
            } catch (e: IOException) {
                return com.google.common.util.concurrent.Futures.immediateFailedFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?>(
                    e
                )
            }
            if (baseStat == null || patterns.isEmpty()) {
                return com.google.common.util.concurrent.Futures.immediateFuture<MutableList<com.google.devtools.build.lib.vfs.Path?>?>(
                    Collections.emptyList<com.google.devtools.build.lib.vfs.Path?>()
                )
            }

            // TODO(adonovan): validate pattern unconditionally, before I/O (potentially breaking change).
            val splitPatterns = checkAndSplitPatterns(patterns)

            // We do a dumb loop, even though it will likely duplicate logical work (note that the
            // physical filesystem operations are cached). In order to optimize, we would need to keep
            // track of which patterns shared sub-patterns and which did not (for example consider the
            // glob [*/*.java, sub/*.java, */*.txt]).
            pendingOps.incrementAndGet()
            try {
                for (splitPattern in splitPatterns) {
                    var numRecursivePatterns = 0
                    for (pattern in splitPattern!!) {
                        if (isRecursivePattern(pattern)) {
                            ++numRecursivePatterns
                        }
                    }
                    val context =
                        if (numRecursivePatterns > 1)
                            RecursiveGlobTaskContext(splitPattern, pathDiscriminator, filesystemOps)
                        else
                            GlobTaskContext(splitPattern, pathDiscriminator, filesystemOps)
                    context.queueGlob(base, baseStat.isDirectory(), 0)
                }
            } finally {
                decrementAndCheckDone()
            }

            return result
        }

        val mostSeriousThrowableSoFar: Throwable?
            get() {
                if (error.get() != null) {
                    return error.get()
                }
                if (runtimeException.get() != null) {
                    return runtimeException.get()
                }
                if (ioException.get() != null) {
                    return ioException.get()
                }
                return null
            }

        /** Should only be called by link [GlobTaskContext].  */
        fun queueGlob(
            base: com.google.devtools.build.lib.vfs.Path, baseIsDir: Boolean, idx: Int, context: GlobTaskContext
        ) {
            enqueue(
                object : java.lang.Runnable {
                    override fun run() {
                        try {
                            Profiler.instance().profile(ProfilerTask.VFS_GLOB, base.getPathString()).use { c ->
                                reallyGlob(base, baseIsDir, idx, context)
                            }
                        } catch (e: IOException) {
                            ioException.set(e)
                        } catch (e: java.lang.RuntimeException) {
                            runtimeException.set(e)
                        } catch (e: java.lang.Error) {
                            error.set(e)
                        }
                    }

                    override fun toString(): String? {
                        return java.lang.String.format(
                            "%s glob(include=[%s])",
                            base.getPathString(),
                            "\"" + com.google.common.base.Joiner.on("\", \"").join(context.patternParts) + "\""
                        )
                    }
                })
        }

        /** Should only be called by link [GlobTaskContext].  */
        fun queueTask(runnable: java.lang.Runnable) {
            enqueue(runnable)
        }

        fun enqueue(r: java.lang.Runnable) {
            totalOps.incrementAndGet()
            pendingOps.incrementAndGet()

            val wrapped: java.lang.Runnable =
                java.lang.Runnable {
                    try {
                        if (!canceled && this.mostSeriousThrowableSoFar == null) {
                            r.run()
                        }
                    } finally {
                        decrementAndCheckDone()
                    }
                }

            if (executor == null) {
                wrapped.run()
            } else {
                executor.execute(wrapped)
            }
        }

        val numGlobTasksForTesting: Long
            get() = totalOps.get()

        fun cancel() {
            this.canceled = true
        }

        fun decrementAndCheckDone() {
            if (pendingOps.decrementAndGet() == 0L) {
                // We get to 0 iff we are done all the relevant work. This is because we always increment
                // the pending ops count as we're enqueuing, and don't decrement until the task is complete
                // (which includes accounting for any additional tasks that one enqueues).

                val mostSeriousThrowable = this.mostSeriousThrowableSoFar
                if (canceled) {
                    result.markCanceled()
                } else if (mostSeriousThrowable != null) {
                    result.setException(mostSeriousThrowable)
                } else {
                    result.set(
                        com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.vfs.Path?>(
                            results
                        )
                    )
                }
            }
        }

        /** A context for evaluating all the subtasks of a single top-level glob task.  */
        private open inner class GlobTaskContext(
            private val patternParts: Array<String>,
            pathDiscriminator: UnixGlobPathDiscriminator,
            filesystemOps: FilesystemOps
        ) {
            private val pathDiscriminator: UnixGlobPathDiscriminator
            private val filesystemOps: FilesystemOps

            init {
                this.pathDiscriminator = pathDiscriminator
                this.filesystemOps = filesystemOps
            }

            open fun queueGlob(base: com.google.devtools.build.lib.vfs.Path, baseIsDir: Boolean, patternIdx: Int) {
                this@GlobVisitor.queueGlob(base, baseIsDir, patternIdx, this)
            }

            fun queueTask(runnable: java.lang.Runnable) {
                this@GlobVisitor.queueTask(runnable)
            }
        }

        /**
         * A special implementation of [GlobTaskContext] that dedupes glob subtasks. Our naive
         * implementation of recursive patterns means there are multiple ways to enqueue the same
         * logical subtask.
         */
        private inner class RecursiveGlobTaskContext(
            patternParts: Array<String>,
            pathDiscriminator: UnixGlobPathDiscriminator,
            filesystemOps: FilesystemOps
        ) : GlobTaskContext(patternParts, pathDiscriminator, filesystemOps) {
            private inner class GlobTask(base: com.google.devtools.build.lib.vfs.Path, patternIdx: Int) {
                private val base: com.google.devtools.build.lib.vfs.Path
                private val patternIdx: Int

                init {
                    this.base = base
                    this.patternIdx = patternIdx
                }

                override fun equals(obj: Any?): Boolean {
                    if (obj !is GlobTask) {
                        return false
                    }
                    return base == obj.base && patternIdx == obj.patternIdx
                }

                override fun hashCode(): Int {
                    return java.util.Objects.hash(base, patternIdx)
                }
            }

            private val visitedGlobSubTasks: MutableSet<GlobTask?> =
                com.google.common.collect.Sets.newConcurrentHashSet<GlobTask?>()

            protected override fun queueGlob(
                base: com.google.devtools.build.lib.vfs.Path,
                baseIsDir: Boolean,
                patternIdx: Int
            ) {
                if (visitedGlobSubTasks.add(GlobTask(base, patternIdx))) {
                    // This is a unique glob task. For example of how duplicates can arise, consider:
                    //   glob(['**/a/**/foo.txt'])
                    // with the only file being
                    //   a/a/foo.txt
                    //
                    // there are multiple ways to reach a/a/foo.txt: one route starts by recursively globbing
                    // 'a/**/foo.txt' in the base directory of the package, and another route starts by
                    // recursively globbing '**/a/**/foo.txt' in subdirectory 'a'.
                    super.queueGlob(base, baseIsDir, patternIdx)
                }
            }
        }

        /**
         * Expressed in Haskell:
         * 
         * <pre>
         * reallyGlob base []     = { base }
         * reallyGlob base [x:xs] = union { reallyGlob(f, xs) | f results "base/x" }
        </pre> * 
         */
        @Throws(IOException::class)
        fun reallyGlob(
            base: com.google.devtools.build.lib.vfs.Path,
            baseIsDir: Boolean,
            idx: Int,
            context: GlobTaskContext
        ) {
            if (idx == context.patternParts.size) { // Base case.
                maybeAddResult(context, base, baseIsDir)
                return
            }

            // Do an early readdir() call here if the pattern contains a wildcard (* or ?). The reason is
            // that we'll do so later anyway and doing this early avoids an additional stat to determine
            // the existence of a build file as part of the shouldTraverseDirectory() call below (globs
            // will no recurse into sub-packages, i.e. directories that contain a build file). This
            // optimizes for the common case where there is no build file in the sub directory.
            val pattern = context.patternParts[idx]
            val patternContainsWildcard = pattern.contains("*") || pattern.contains("?")
            var dents: MutableCollection<com.google.devtools.build.lib.vfs.Dirent>? = null
            if (baseIsDir && patternContainsWildcard) {
                dents = context.filesystemOps.readdir(base)
            }

            if (baseIsDir && !context.pathDiscriminator.shouldTraverseDirectory(base)) {
                if (areAllRemainingPatternsDoubleStar(context, idx)) {
                    // For SUBPACKAGES, we encounter this when all remaining patterns in the glob expression
                    // are `**`s. In that case we should include the subpackage's PathFragment (relative to
                    // the package fragment) in the matching results.
                    maybeAddResult(context, base, baseIsDir)
                }
                return
            }

            if (!baseIsDir) {
                // Nothing to find here.
                return
            }

            // ** is special: it can match nothing at all.
            // For example, x/** matches x, **/y matches y, and x/**/y matches x/y.
            if (isRecursivePattern(pattern)) {
                context.queueGlob(base, baseIsDir, idx + 1)
            }

            if (!patternContainsWildcard) {
                // We do not need to do a readdir in this case, just a stat.
                val child: com.google.devtools.build.lib.vfs.Path = base.getChild(pattern)
                val status: FileStatus? = context.filesystemOps.statIfFound(child)
                if (status == null || (!status.isDirectory() && !status.isFile())) {
                    // The file is a dangling symlink, fifo, does not exist, etc.
                    return
                }

                context.queueGlob(child, status.isDirectory(), idx + 1)
                return
            }

            for (dent in dents!!) {
                val childType: com.google.devtools.build.lib.vfs.Dirent.Type = dent.getType()
                if (childType == com.google.devtools.build.lib.vfs.Dirent.Type.UNKNOWN) {
                    // The file is a special file (fifo, etc.). No need to even match against the pattern.
                    continue
                }
                if (matches(pattern, dent.getName(), cache)) {
                    val child: com.google.devtools.build.lib.vfs.Path = base.getChild(dent.getName())

                    if (childType == com.google.devtools.build.lib.vfs.Dirent.Type.SYMLINK) {
                        processSymlink(child, idx, context)
                    } else {
                        processFileOrDirectory(
                            child,
                            childType == com.google.devtools.build.lib.vfs.Dirent.Type.DIRECTORY,
                            idx,
                            context
                        )
                    }
                }
            }
        }

        fun maybeAddResult(
            context: GlobTaskContext,
            base: com.google.devtools.build.lib.vfs.Path?,
            isDirectory: Boolean
        ) {
            if (context.pathDiscriminator.shouldIncludePathInResult(base, isDirectory)) {
                results.add(base)
            }
        }

        /**
         * Process symlinks asynchronously. If we should used readdir(..., Symlinks.FOLLOW), that would
         * result in a sequential symlink resolution with many file system implementations. If the
         * underlying file system is networked and a single directory contains many symlinks, that can
         * lead to substantial slowness.
         */
        fun processSymlink(path: com.google.devtools.build.lib.vfs.Path, idx: Int, context: GlobTaskContext) {
            context.queueTask(
                java.lang.Runnable {
                    try {
                        val status: FileStatus? = context.filesystemOps.statIfFound(path)
                        if (status != null) {
                            processFileOrDirectory(path, status.isDirectory(), idx, context)
                        }
                    } catch (e: IOException) {
                        ioException.compareAndSet(null, e)
                    }
                })
        }

        fun processFileOrDirectory(
            path: com.google.devtools.build.lib.vfs.Path, isDir: Boolean, idx: Int, context: GlobTaskContext
        ) {
            val isRecursivePattern = isRecursivePattern(context.patternParts[idx])
            if (isDir) {
                context.queueGlob(path,  /* baseIsDir= */true, idx + (if (isRecursivePattern) 0 else 1))
            } else if (idx + 1 == context.patternParts.size) {
                maybeAddResult(context, path,  /* isDirectory= */false)
            }
        }

        companion object {
            private fun isRecursivePattern(pattern: String?): Boolean {
                return "**" == pattern
            }

            private fun areAllRemainingPatternsDoubleStar(
                context: GlobTaskContext, startIdx: Int
            ): Boolean {
                return java.util.Arrays.stream<String?>(context.patternParts, startIdx, context.patternParts.size)
                    .allMatch(java.util.function.Predicate { anObject: String? -> "**".equals(anObject) })
            }
        }
    }

    /** How `#matchesPattern()` should work  */
    private enum class MatchMode {
        EXACT,  // The path should exactly match the pattern
        PREFIX,  // The pattern should match a prefix of the path
        CAN_MATCH_CHILD,  // Whether there can be any path under the prefix that matches the pattern
    }
}
