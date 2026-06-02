// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.repository

import com.google.devtools.build.lib.actions.FileValue

/**
 * Represents a "recorded input" of a repo fetch. We define the "input" of a repo fetch as any
 * entity that could affect the output of the repo fetch (i.e. the repo contents). A "recorded
 * input" is thus any input we can record during the fetch and thus know about only after the fetch.
 * This contrasts with "predeclared inputs", which are known before fetching the repo, and
 * "undiscoverable inputs", which are used during the fetch but is not recorded or recordable.
 * 
 * 
 * Recorded inputs are of particular interest, since in order to determine whether a fetched repo
 * is still up-to-date, the identity of all recorded inputs need to be stored in addition to their
 * values. This contrasts with predeclared inputs; the whole set of predeclared inputs are known
 * before the fetch, so we can simply hash all predeclared input values.
 * 
 * 
 * Recorded inputs and their values are stored in *marker files* for repos. Each recorded
 * input is stored as a string, with a prefix denoting its type, followed by a colon, and then the
 * information identifying that specific input.
 */
abstract class RepoRecordedInput {
    /** Represents a parser for a specific type of recorded inputs.  */
    abstract class Parser {
        /**
         * The prefix that identifies the type of the recorded inputs: for example, the `ENV` part
         * of `ENV:MY_ENV_VAR`.
         */
        abstract val prefix: String?

        /**
         * Parses a recorded input from the post-colon substring that identifies the specific input: for
         * example, the `MY_ENV_VAR` part of `ENV:MY_ENV_VAR`. Returns null if the parsed
         * part is invalid.
         */
        abstract fun parse(s: String?): RepoRecordedInput
    }

    /** A recorded input along with its recorded value.  */
    @AutoCodec
    @kotlin.jvm.JvmRecord
    data class WithValue(val input: RepoRecordedInput?, val value: String?) {
        /** Converts this [WithValue] to a string in a format compatible with [.parse].  */
        override fun toString(): String {
            return input.toString() + " " + escape(value)
        }

        companion object {
            /** Parses a [WithValue] from its string representation.  */
            @kotlin.jvm.JvmStatic
            fun parse(s: String): java.util.Optional<WithValue?> {
                val sChar: Int = s.indexOf(' '.code)
                if (sChar > 0) {
                    val input = RepoRecordedInput.Companion.parse(unescape(s.substring(0, sChar))!!)
                    if (input != NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE) {
                        return java.util.Optional.of<WithValue?>(WithValue(input, unescape(s.substring(sChar + 1))))
                    }
                }
                return java.util.Optional.empty<WithValue?>()
            }

            /**
             * Splits the given list of recorded input values into batches such that within each batch, all
             * recorded inputs's [SkyKey]s can be requested together.
             */
            fun splitIntoBatches(
                recordedInputValues: MutableList<WithValue>
            ): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableList<WithValue?>?> {
                val batches: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableList<WithValue?>?> =
                    com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableList<WithValue?>?>()
                val currentBatch: java.util.ArrayList<WithValue?> = java.util.ArrayList<WithValue?>()
                for (recordedInputValue in recordedInputValues) {
                    if (!recordedInputValue.input!!.canBeRequestedUnconditionally()
                        && !currentBatch.isEmpty()
                    ) {
                        batches.add(com.google.common.collect.ImmutableList.copyOf<WithValue?>(currentBatch))
                        currentBatch.clear()
                    }
                    currentBatch.add(recordedInputValue)
                }
                if (!currentBatch.isEmpty()) {
                    batches.add(com.google.common.collect.ImmutableList.copyOf<WithValue?>(currentBatch))
                }
                return batches.build()
            }
        }
    }

    /**
     * Returns a human-readable reason for why the given `oldValue` is no longer up-to-date for
     * this recorded input, or an empty Optional if it is still up-to-date.
     * 
     * 
     * The method can request Skyframe evaluations, and if any values are missing, this method can
     * return any value (doesn't matter what, although [.UNDECIDED] is recommended for clarity)
     * and will be reinvoked after a Skyframe restart.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun isOutdated(
        env: SkyFunction.Environment, directories: BlazeDirectories?, oldValue: String?
    ): java.util.Optional<String?> {
        val wrappedNewValue = getValue(env, directories)
        if (env.valuesMissing()) {
            return UNDECIDED
        }
        return when (wrappedNewValue) {
            -> java.util.Optional.of<String?>(reason)
            -> if (oldValue == newValue)
                java.util.Optional.empty<String?>()
            else
                java.util.Optional.of<String?>(describeChange(oldValue, newValue))
        }
    }

    abstract override fun equals(obj: Any?): Boolean

    abstract override fun hashCode(): Int

    /**
     * Returns the string representation of this recorded input, in the format suitable for parsing
     * back via [.parse].
     * 
     * 
     * The returned string never contains spaces or newlines; those characters are escaped.
     */
    override fun toString(): String {
        return this.parser!!.prefix + ":" + escape(toStringInternal())
    }

    /**
     * Represents the possible values returned by [.getValue]: either a valid value (which may
     * be null), or an invalid value with a reason (e.g. due to I/O failure).
     */
    interface MaybeValue {
        /** Represents a valid value, which may be null.  */
        @kotlin.jvm.JvmRecord
        data class Valid(val value: String?) : MaybeValue

        /** Represents an invalid value with a reason (e.g. due to I/O failure).  */
        @kotlin.jvm.JvmRecord
        data class Invalid(val reason: String?) : MaybeValue
        companion object {
            val VALUES_MISSING: MaybeValue =
                com.google.devtools.build.lib.rules.repository.RepoRecordedInput.MaybeValue.Invalid("values missing")
        }
    }

    /**
     * Returns the current value of this input, which may be null, wrapped in a [ ], or a [MaybeValue.Invalid] if the value is known to be invalid.
     * 
     * 
     * The method can request Skyframe evaluations, and if any values are missing, this method can
     * return any value and will be reinvoked after a Skyframe restart.
     */
    @Throws(java.lang.InterruptedException::class)
    abstract fun getValue(env: SkyFunction.Environment?, directories: BlazeDirectories?): MaybeValue?

    /**
     * Returns a human-readable description of the change from `oldValue` to `newValue`.
     */
    protected abstract fun describeChange(oldValue: String?, newValue: String?): String?

    /**
     * Returns the post-colon substring that identifies the specific input: for example, the `MY_ENV_VAR` part of `ENV:MY_ENV_VAR`.
     */
    protected abstract fun toStringInternal(): String?

    /** Returns the parser object for this type of recorded inputs.  */
    protected abstract val parser: Parser?

    /** Returns the [SkyKey] that is necessary to determine [.isOutdated].  */
    protected abstract fun getSkyKey(directories: BlazeDirectories?): SkyKey?

    /**
     * Returns true if the [.getValue] can be requested even if previous recorded inputs have
     * not been verified to be up to date.
     */
    protected abstract fun canBeRequestedUnconditionally(): Boolean

    /**
     * Represents a filesystem path stored in a way that is repo-cache-friendly. That is, if the path
     * happens to point inside the current Bazel workspace (in either the main repo or an external
     * repo), we store the appropriate repo name and the path fragment relative to the repo root,
     * instead of the entire absolute path.
     * 
     * 
     * This is *almost* like storing a label, but includes the extra corner case of files
     * inside a repo but not within any package due to missing BUILD files. For example, the file
     * `@@foo//:abc.bzl` is addressable by a label if the file `@@foo//:BUILD` exists. But
     * if the BUILD file doesn't exist, the `abc.bzl` file should still be "watchable"; it's
     * just that `@@foo//:abc.bzl` is technically not a valid label.
     * 
     * 
     * Of course, when the path is outside the current Bazel workspace, we just store the absolute
     * path.
     */
    @AutoCodec
    class RepoCacheFriendlyPath(repoName: java.util.Optional<RepositoryName?>?, path: PathFragment?) {
        override fun toString(): String {
            // We store `@@foo//abc/def:ghi.bzl` as just `@@foo//abc/def/ghi.bzl`. See class javadoc for
            // more context.
            return this.repoName.map<String?>(java.util.function.Function { repoName: RepositoryName? -> repoName.toString() + "//" + this.path })
                .orElse(this.path.toString())
        }

        /** Returns the rooted path corresponding to this "repo-friendly path".  */
        fun getRootedPath(directories: BlazeDirectories): RootedPath? {
            val root: Root?
            if (this.repoName.isEmpty()) {
                root = Root.absoluteRoot(directories.getOutputBase().getFileSystem())
            } else if (this.repoName.get().isMain()) {
                root = Root.fromPath(directories.getWorkspace())
            } else {
                // This path is from an external repo. We just directly fabricate the path here instead of
                // requesting the appropriate RepositoryDirectoryValue, since we can rely on the various
                // other SkyFunctions (such as FileStateFunction and DirectoryListingStateFunction) to do
                // that for us instead. This also sidesteps an awkward situation when the external repo in
                // question is not defined.
                root =
                    Root.fromPath(
                        directories
                            .getOutputBase()
                            .getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
                            .getRelative(this.repoName.get().name)
                    )
            }
            return RootedPath.toRootedPath(root, this.path)
        }

        /** Returns true if the path points into an external repository.  */
        fun inExternalRepo(): Boolean {
            return this.repoName.isPresent() && !this.repoName.get().isMain()
        }

        val repoName: java.util.Optional<RepositoryName?>?
        val path: PathFragment?

        init {
            this.path = path
            this.repoName = repoName
            java.util.Objects.requireNonNull<java.util.Optional<RepositoryName?>?>(repoName, "repoName")
            java.util.Objects.requireNonNull<PathFragment?>(path, "path")
        }

        companion object {
            fun createInsideWorkspace(
                repoName: RepositoryName, path: PathFragment
            ): RepoCacheFriendlyPath {
                com.google.common.base.Preconditions.checkArgument(
                    !path.isAbsolute(), "the provided path should be relative to the repo root: %s", path
                )
                return RepoCacheFriendlyPath(java.util.Optional.of<RepositoryName?>(repoName), path)
            }

            fun createOutsideWorkspace(path: PathFragment): RepoCacheFriendlyPath {
                com.google.common.base.Preconditions.checkArgument(
                    path.isAbsolute(), "the provided path should be absolute in the filesystem: %s", path
                )
                return RepoCacheFriendlyPath(java.util.Optional.empty<RepositoryName?>(), path)
            }

            @Throws(LabelSyntaxException::class)
            fun parse(s: String): RepoCacheFriendlyPath {
                if (LabelValidator.isAbsolute(s)) {
                    val doubleSlash: Int = s.indexOf("//")
                    val skipAts = if (s.startsWith("@@")) 2 else if (s.startsWith("@")) 1 else 0
                    return createInsideWorkspace(
                        RepositoryName.create(s.substring(skipAts, doubleSlash)),
                        PathFragment.create(s.substring(doubleSlash + 2))
                    )
                }
                return createOutsideWorkspace(PathFragment.create(s))
            }
        }
    }

    /**
     * Represents a file input accessed during the repo fetch. Despite being named just "file", this
     * can represent a file or a directory on the filesystem, and it does not need to exist. The value
     * of the input contains whether this is a file or a directory or nonexistent, and if it's a file,
     * the digest of its contents.
     */
    class File(private val path: RepoCacheFriendlyPath) : RepoRecordedInput() {
        public override fun getParser(): Parser {
            return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.File.Companion.PARSER
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is File) {
                return false
            }
            return path == o.path
        }

        override fun hashCode(): Int {
            return path.hashCode()
        }

        public override fun toStringInternal(): String? {
            return path.toString()
        }

        public override fun getSkyKey(directories: BlazeDirectories): SkyKey {
            return FileValue.key(path.getRootedPath(directories))
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            // Requesting files in external repositories can result in cycles if the external repo now
            // transitively depends on the requesting repo.
            return !path.inExternalRepo()
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getValue(env: SkyFunction.Environment, directories: BlazeDirectories): MaybeValue {
            val skyKey: SkyKey = getSkyKey(directories)
            try {
                val fileValue: FileValue? =
                    env.getValueOrThrow<IOException?>(skyKey, IOException::class.java) as FileValue?
                if (fileValue == null) {
                    return MaybeValue.Companion.VALUES_MISSING
                }
                return Valid(
                    com.google.devtools.build.lib.rules.repository.RepoRecordedInput.File.Companion.fileValueToMarkerValue(
                        skyKey.argument() as RootedPath?,
                        fileValue
                    )
                )
            } catch (e: IOException) {
                return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.MaybeValue.Invalid(
                    "failed to stat %s: %s".formatted(
                        path,
                        e.getMessage()
                    )
                )
            }
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            return "file info or contents of %s changed".formatted(path)
        }

        companion object {
            val PARSER: Parser = object : Parser() {
                override fun getPrefix(): String {
                    return "FILE"
                }

                override fun parse(s: String): RepoRecordedInput? {
                    try {
                        return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.File(
                            RepoCacheFriendlyPath.Companion.parse(s)
                        )
                    } catch (e: LabelSyntaxException) {
                        // malformed inputs cause refetch
                        return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
                    }
                }
            }

            /**
             * Convert to a [com.google.devtools.build.lib.actions.FileValue] to a String appropriate
             * for placing in a repository marker file. The file need not exist, and can be a file or a
             * directory.
             */
            @com.google.common.annotations.VisibleForTesting
            @Throws(IOException::class)
            fun fileValueToMarkerValue(rootedPath: RootedPath?, fileValue: FileValue): String {
                if (fileValue.isDirectory()) {
                    return "DIR"
                }
                if (!fileValue.exists()) {
                    return "ENOENT"
                }
                // Return the file content digest in hex. fileValue may or may not have the digest available.
                var digest: ByteArray? = fileValue.realFileStateValue().getDigest()
                if (digest == null) {
                    // Fast digest not available, or it would have been in the FileValue.
                    digest = fileValue.realRootedPath(rootedPath).asPath().getDigest()
                }
                return com.google.common.io.BaseEncoding.base16().lowerCase().encode(digest)
            }
        }
    }

    /** Represents the list of entries under a directory accessed during the fetch.  */
    class Dirents(private val path: RepoCacheFriendlyPath) : RepoRecordedInput() {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Dirents) {
                return false
            }
            return path == o.path
        }

        override fun hashCode(): Int {
            return path.hashCode()
        }

        public override fun toStringInternal(): String? {
            return path.toString()
        }

        public override fun getParser(): Parser {
            return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.Dirents.Companion.PARSER
        }

        private fun directoryListingValueToMarkerValue(directoryListingValue: DirectoryListingValue): String {
            val fp: Fingerprint = Fingerprint()
            fp.addStrings(
                directoryListingValue.getDirents().stream()
                    .map<String?>(java.util.function.Function { obj: com.google.devtools.build.lib.vfs.Dirent? -> obj.getName() })
                    .sorted()
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
            )
            return fp.hexDigestAndReset()
        }

        public override fun getSkyKey(directories: BlazeDirectories): SkyKey? {
            return DirectoryListingValue.key(path.getRootedPath(directories))
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            // Requesting directories in external repositories can result in cycles if the external repo
            // transitively depends on the requesting repo.
            return !path.inExternalRepo()
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getValue(env: SkyFunction.Environment, directories: BlazeDirectories): MaybeValue {
            val skyKey: SkyKey? = getSkyKey(directories)
            val directoryListingValue: DirectoryListingValue? = env.getValue(skyKey) as DirectoryListingValue?
            if (directoryListingValue == null) {
                return MaybeValue.Companion.VALUES_MISSING
            }
            return Valid(directoryListingValueToMarkerValue(directoryListingValue))
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            return "directory entries of %s changed".formatted(path)
        }

        companion object {
            val PARSER: Parser = object : Parser() {
                override fun getPrefix(): String {
                    return "DIRENTS"
                }

                override fun parse(s: String): RepoRecordedInput? {
                    try {
                        return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.Dirents(
                            RepoCacheFriendlyPath.Companion.parse(s)
                        )
                    } catch (e: LabelSyntaxException) {
                        // malformed inputs cause refetch
                        return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
                    }
                }
            }
        }
    }

    /**
     * Represents an entire directory tree accessed during the fetch. Anything under the tree changing
     * (including adding/removing/renaming files or directories and changing file contents) will cause
     * it to go out of date.
     * 
     * 
     * Files can be excluded from the out-of-date check with the given `excludes` glob
     * patterns.
     */
    class DirTree(private val path: RepoCacheFriendlyPath, excludes: com.google.common.collect.ImmutableList<String?>) :
        RepoRecordedInput() {
        /** The glob patterns to exclude from watch/change detection.  */
        private val excludes: com.google.common.collect.ImmutableList<String?>

        init {
            this.excludes = excludes
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is DirTree) {
                return false
            }
            return path == o.path && excludes == o.excludes
        }

        override fun hashCode(): Int {
            val hash = path.hashCode()
            if (!excludes.isEmpty()) {
                return hash + excludes.hashCode()
            }
            return hash
        }

        public override fun toStringInternal(): String? {
            if (this.excludes.isEmpty()) {
                return path.toString()
            } else {
                // Excludes parameters represented as a query string.
                val sb: java.lang.StringBuilder = java.lang.StringBuilder(path.toString())
                sb.append(METADATA_DELIMITER)
                sb.append(
                    this.excludes.stream()
                        .map<String?>(java.util.function.Function { s: String? ->
                            URLEncoder.encode(
                                s,
                                java.nio.charset.StandardCharsets.UTF_8
                            )
                        })
                        .collect(Collectors.joining(","))
                )
                return sb.toString()
            }
        }

        public override fun getParser(): Parser {
            return PARSER
        }

        public override fun getSkyKey(directories: BlazeDirectories): SkyKey? {
            val rootedPath: RootedPath? = path.getRootedPath(directories)
            return DirectoryTreeDigestValue.key(rootedPath, rootedPath, excludes)
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            // Requesting directory trees in external repositories can result in cycles if the external
            // repo now transitively depends on the requesting repo.
            return !path.inExternalRepo()
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getValue(env: SkyFunction.Environment, directories: BlazeDirectories): MaybeValue {
            val skyKey: SkyKey? = getSkyKey(directories)
            try {
                val directoryTreeDigestValue: DirectoryTreeDigestValue? =
                    env.getValueOrThrow<IOException?>(skyKey, IOException::class.java) as DirectoryTreeDigestValue?
                if (directoryTreeDigestValue == null) {
                    return MaybeValue.Companion.VALUES_MISSING
                }
                return Valid(directoryTreeDigestValue.hexDigest)
            } catch (e: IOException) {
                return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.MaybeValue.Invalid(
                    "failed to digest directory tree at %s: %s".formatted(path, e.getMessage())
                )
            }
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            return "directory tree at %s changed".formatted(path)
        }

        companion object {
            /**
             * A string sequence to delimit extra metadata on a directory path. This borrows web's query
             * string to represent the extra options, but instead of a '?' to serve as the separator between
             * the path and the query string, '?/../' is used instead. This takes advantage of the fact that
             * the underlying path representation is a [PathFragment] which is normalized - meaning
             * that a '/../' could never appear in the valid path.
             * 
             * 
             * Since there is currently only one parameter (excludes), it is hardcoded with the
             * delimiter.
             * 
             * 
             * For a DirTree with path '/foo/bar' and an exclude list of 'abc/ **' and 'file,with,commas',
             * the serialized form is:
             * 
             * 
             * `/foo/bar?/../excludes=abc%2F**,file%2Cwith%2Ccommas`
             */
            const val METADATA_DELIMITER: String = "?/../excludes="

            val PARSER: Parser = object : Parser() {
                override fun getPrefix(): String {
                    return "DIRTREE"
                }

                override fun parse(s: String): RepoRecordedInput? {
                    try {
                        val metadataIndex: Int = s.indexOf(METADATA_DELIMITER)
                        if (metadataIndex != -1) {
                            // Parse out the query string.
                            val excludesQueryString: String =
                                s.substring(metadataIndex + METADATA_DELIMITER.length())
                            val excludesArray: Array<String?> = excludesQueryString.split(",")
                            val excludesList: com.google.common.collect.ImmutableList<String?> =
                                java.util.Arrays.stream<String?>(excludesArray)
                                    .map<String?>(java.util.function.Function { exclude: String? ->
                                        URLDecoder.decode(
                                            exclude,
                                            java.nio.charset.StandardCharsets.UTF_8
                                        )
                                    })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                            return DirTree(
                                RepoCacheFriendlyPath.Companion.parse(s.substring(0, metadataIndex)), excludesList
                            )
                        } else {
                            return DirTree(
                                RepoCacheFriendlyPath.Companion.parse(s),
                                com.google.common.collect.ImmutableList.of<String?>()
                            )
                        }
                    } catch (e: LabelSyntaxException) {
                        // malformed inputs cause refetch
                        return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
                    }
                }
            }
        }
    }

    /** Represents an environment variable accessed during the repo fetch.  */
    class EnvVar(val name: String) : RepoRecordedInput() {
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is EnvVar) {
                return false
            }
            return name == o.name
        }

        override fun hashCode(): Int {
            return name.hashCode()
        }

        public override fun getParser(): Parser {
            return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.EnvVar.Companion.PARSER
        }

        public override fun toStringInternal(): String {
            return name
        }

        public override fun getSkyKey(directories: BlazeDirectories?): SkyKey? {
            return RepoEnvironmentFunction.key(name)
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            // Environment variables are static data injected into Skyframe, so there is no risk of
            // cycles.
            return true
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getValue(env: SkyFunction.Environment, directories: BlazeDirectories?): MaybeValue {
            val value: EnvironmentVariableValue? = env.getValue(getSkyKey(directories)) as EnvironmentVariableValue?
            if (value == null) {
                return MaybeValue.Companion.VALUES_MISSING
            }
            return Valid(value.value)
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            return "environment variable %s changed: %s -> %s"
                .formatted(
                    name,
                    if (oldValue == null) "<unset>" else "'%s'".formatted(oldValue),
                    if (newValue == null) "<unset>" else "'%s'".formatted(newValue)
                )
        }

        companion object {
            val PARSER: Parser = object : Parser() {
                override fun getPrefix(): String {
                    return "ENV"
                }

                override fun parse(s: String): RepoRecordedInput {
                    return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.EnvVar(s)
                }
            }

            fun wrap(
                envVars: MutableMap<String?, java.util.Optional<String?>?>
            ): com.google.common.collect.ImmutableMap<EnvVar?, java.util.Optional<String?>?> {
                return envVars.entrySet().stream()
                    .sorted(TODO("Cannot convert element")) < String
                java.util.Map.Entry.comparingByKey<K?, Any?>()
                TODO(
                    """
                    |Cannot convert element
                    |With text:
                    |collect(<Map.Entry<String, Optional<String>>, EnvVar, Optional<String>>toImmutableMap(e -> new EnvVar(e.getKey()), Map.Entry::getValue)
                    """.trimMargin()
                )
            }
        }
    }

    /** Represents a repo mapping entry that was used during the repo fetch.  */
    class RecordedRepoMapping(sourceRepo: RepositoryName, apparentName: String) : RepoRecordedInput() {
        val sourceRepo: RepositoryName
        val apparentName: String

        init {
            this.sourceRepo = sourceRepo
            this.apparentName = apparentName
        }

        fun apparentName(): String {
            return apparentName
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is RecordedRepoMapping) {
                return false
            }
            return sourceRepo == o.sourceRepo
                    && apparentName == o.apparentName
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(sourceRepo, apparentName)
        }

        public override fun getParser(): Parser {
            return PARSER
        }

        public override fun toStringInternal(): String {
            return (sourceRepo.name + ',').toString() + apparentName
        }

        public override fun getSkyKey(directories: BlazeDirectories?): SkyKey? {
            return RepositoryMappingValue.key(sourceRepo)
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            // Starlark can only request the mapping of the repo it is currently executing from, which
            // means that the repo has already been fetched (either to execute the code or to verify the
            // transitive .bzl hash). Further cycles aren't possible.
            return true
        }

        @Throws(java.lang.InterruptedException::class)
        override fun getValue(env: SkyFunction.Environment, directories: BlazeDirectories?): MaybeValue {
            val repoMappingValue: RepositoryMappingValue? =
                env.getValue(getSkyKey(directories)) as RepositoryMappingValue?
            if (repoMappingValue == RepositoryMappingValue.NOT_FOUND_VALUE) {
                return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.MaybeValue.Invalid(
                    "source repo %s doesn't exist anymore".formatted(
                        sourceRepo
                    )
                )
            }
            val canonicalName: RepositoryName? = repoMappingValue.repositoryMapping.get(apparentName)
            return Valid(if (canonicalName != null) canonicalName.name else null)
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            return "canonical name for @%s in %s changed: %s -> %s"
                .formatted(
                    apparentName,
                    sourceRepo,
                    if (oldValue == null) "<doesn't exist>" else oldValue,
                    if (newValue == null) "<doesn't exist>" else newValue
                )
        }

        companion object {
            val PARSER: Parser = object : Parser() {
                override fun getPrefix(): String {
                    return "REPO_MAPPING"
                }

                override fun parse(s: String): RepoRecordedInput? {
                    val parts: MutableList<String?> = com.google.common.base.Splitter.on(',').limit(2).splitToList(s)
                    try {
                        return RecordedRepoMapping(RepositoryName.create(parts.get(0)), parts.get(1)!!)
                    } catch (e: LabelSyntaxException) {
                        // malformed inputs cause refetch
                        return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
                    } catch (e: java.lang.IndexOutOfBoundsException) {
                        return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
                    }
                }
            }
        }
    }

    /** A sentinel "input" that's always out-of-date for a given reason.  */
    class NeverUpToDateRepoRecordedInput(private val reason: String?) : RepoRecordedInput() {
        override fun equals(obj: Any?): Boolean {
            return this === obj
        }

        override fun hashCode(): Int {
            return 12345678
        }

        public override fun toStringInternal(): String? {
            throw java.lang.UnsupportedOperationException("this sentinel input should never be serialized")
        }

        public override fun getParser(): Parser? {
            throw java.lang.UnsupportedOperationException("this sentinel input should never be parsed")
        }

        public override fun getSkyKey(directories: BlazeDirectories?): SkyKey? {
            // Return a random SkyKey to satisfy the contract.
            return PrecomputedValue.STARLARK_SEMANTICS.getKey()
        }

        override fun canBeRequestedUnconditionally(): Boolean {
            return true
        }

        override fun getValue(env: SkyFunction.Environment?, directories: BlazeDirectories?): MaybeValue {
            return com.google.devtools.build.lib.rules.repository.RepoRecordedInput.MaybeValue.Invalid(reason)
        }

        public override fun describeChange(oldValue: String?, newValue: String?): String? {
            throw java.lang.UnsupportedOperationException(
                "the value for this sentinel input is always invalid"
            )
        }

        companion object {
            /** A sentinel "input" that's always out-of-date to signify parse failure.  */
            val PARSE_FAILURE: RepoRecordedInput =
                NeverUpToDateRepoRecordedInput("malformed marker file entry encountered")
        }
    }

    companion object {
        /**
         * Parses a recorded input from its string representation.
         * 
         * @param s the string representation
         * @return The parsed recorded input object, or [     ][NeverUpToDateRepoRecordedInput.PARSE_FAILURE] if the string representation is invalid
         */
        fun parse(s: String): RepoRecordedInput {
            val parts: MutableList<String?> = com.google.common.base.Splitter.on(':').limit(2).splitToList(s)
            if (parts.size() < 2) {
                return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
            }
            for (parser in arrayOf<Parser>(
                com.google.devtools.build.lib.rules.repository.RepoRecordedInput.File.Companion.PARSER,
                com.google.devtools.build.lib.rules.repository.RepoRecordedInput.Dirents.Companion.PARSER,
                DirTree.Companion.PARSER,
                com.google.devtools.build.lib.rules.repository.RepoRecordedInput.EnvVar.Companion.PARSER,
                RecordedRepoMapping.Companion.PARSER
            )) {
                if (parts.get(0) == parser.prefix) {
                    return parser.parse(parts.get(1))
                }
            }
            return NeverUpToDateRepoRecordedInput.Companion.PARSE_FAILURE
        }

        /**
         * Returns whether all values are still up-to-date for each recorded input or a human-readable
         * reason for why that's not the case. If Skyframe values are missing, the return value should be
         * ignored; callers are responsible for checking `env.valuesMissing()` and triggering a
         * Skyframe restart if needed.
         */
        @Throws(java.lang.InterruptedException::class)
        fun isAnyValueOutdated(
            env: SkyFunction.Environment, directories: BlazeDirectories?, recordedInputValues: MutableList<WithValue>
        ): java.util.Optional<String?> {
            prefetch(
                env,
                directories,
                com.google.common.collect.Collections2.transform<WithValue?, RepoRecordedInput?>(
                    recordedInputValues,
                    WithValue::input
                )
            )
            if (env.valuesMissing()) {
                return UNDECIDED
            }
            for (recordedInput in recordedInputValues) {
                val reason: java.util.Optional<String?> =
                    recordedInput.input!!.isOutdated(env, directories, recordedInput.value)
                if (reason.isPresent()) {
                    return reason
                }
            }
            return java.util.Optional.empty<String?>()
        }

        /**
         * Requests the information from Skyframe that is required by future calls to [ ][.isAnyValueOutdated] for the given set of inputs.
         */
        @Throws(java.lang.InterruptedException::class)
        fun prefetch(
            env: SkyFunction.Environment,
            directories: BlazeDirectories?,
            recordedInputs: MutableCollection<RepoRecordedInput?>
        ) {
            val keys: com.google.common.collect.ImmutableSet<SkyKey?> =
                recordedInputs.stream()
                    .map<SkyKey?>(java.util.function.Function { rri: RepoRecordedInput? -> rri!!.getSkyKey(directories) })
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
            if (env.valuesMissing()) {
                return
            }
            val results: SkyframeLookupResult = env.getValuesAndExceptions(keys)
            // Per the contract of Environment.getValuesAndExceptions, we need to access the results to
            // actually find all missing values.
            for (key in keys) {
                val unused: SkyValue? = results.get(key)
            }
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun escape(str: String?): String? {
            return if (str == null) "\\0" else str.replace("\\", "\\\\").replace("\n", "\\n").replace(" ", "\\s")
        }

        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun unescape(str: String): String? {
            if (str == "\\0") {
                return null // \0 == null string
            }
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            var escaped = false
            for (i in 0..<str.length()) {
                val c: Char = str.charAt(i)
                if (escaped) {
                    if (c == 'n') { // n means new line
                        result.append("\n")
                    } else if (c == 's') { // s means space
                        result.append(" ")
                    } else { // Any other escaped characters are just un-escaped
                        result.append(c)
                    }
                    escaped = false
                } else if (c == '\\') {
                    escaped = true
                } else {
                    result.append(c)
                }
            }
            return result.toString()
        }

        private val UNDECIDED: java.util.Optional<String?> = java.util.Optional.of<String?>("values missing")
    }
}
