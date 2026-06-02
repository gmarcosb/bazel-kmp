// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.base.Ascii
import com.google.common.base.Preconditions
import com.google.common.base.Splitter
import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSortedMap
import com.google.common.collect.Maps
import com.google.common.util.concurrent.Futures
import com.google.devtools.build.lib.analysis.BlazeDirectories
import com.google.devtools.build.lib.bazel.repository.cache.DownloadCache
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum
import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.profiler.Profiler
import com.google.devtools.build.lib.profiler.ProfilerTask
import com.google.devtools.build.lib.profiler.SilentCloseable
import com.google.devtools.build.lib.rules.repository.RepoRecordedInput
import com.google.devtools.build.lib.vfs.FileSystemUtils
import com.google.devtools.build.lib.vfs.Path
import com.google.errorprone.annotations.CanIgnoreReturnValue
import com.google.errorprone.annotations.ForOverride
import net.starlark.java.annot.Param
import net.starlark.java.annot.ParamType
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.*
import net.starlark.java.syntax.Location
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.*
import java.util.concurrent.Future
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.collections.MutableSet

/** A common base class for Starlark "ctx" objects related to external dependencies.  */
abstract class StarlarkBaseExternalContext protected constructor(
    protected val workingDirectory: Path,
    directories: BlazeDirectories,
    env: SkyFunction.Environment,
    repoEnv: ImmutableMap<String?, String?>,
    nonstrictRepoEnv: ImmutableMap<String?, String?>?,
    downloadManager: DownloadManager,
    timeoutScaling: Double,
    processWrapper: ProcessWrapper?,
    starlarkSemantics: StarlarkSemantics,
    identifyingStringForLogging: String?,
    remoteExecutor: RepositoryRemoteExecutor?,
    allowWatchingPathsOutsideWorkspace: Boolean
) : AutoCloseable, StarlarkValue {
    /**
     * An asynchronous task run as part of fetching the repository.
     * 
     * 
     * The main property of such tasks is that they should under no circumstances keep running
     * after fetching the repository is finished, whether successfully or not. To this end, the [ ][.cancel] method may be called to interrupt the work and [.close] must be called to
     * wait for all such work to finish.
     */
    private interface AsyncTask : SilentCloseable {
        /** Returns a user-friendly description of the task.  */
        val description: String?

        /** Returns where the task was started from.  */
        val location: Location?

        /**
         * Cancels the task, if not done yet. Returns false if the task was still in progress.
         * 
         * 
         * Note that the task may still be running after this method returns, the task has just got a
         * signal to interrupt. Call [.close] to wait for the task to finish.
         * 
         * 
         * No means of error reporting is provided. Any errors should be reported by other means. The
         * only possible error reported as a consequence of calling this method is one that tells the
         * user that they didn't wait for an async task they should have waited for.
         */
        fun cancel(): Boolean

        /**
         * Waits uninterruptibly until the task is no longer running, even in case it was cancelled but
         * its underlying thread is still running.
         */
        override fun close()
    }

    protected val directories: BlazeDirectories
    protected val env: SkyFunction.Environment
    protected val repoEnv: ImmutableMap<String?, String?>
    protected val nonstrictRepoEnv: ImmutableMap<String?, String?>?
    private val osObject: StarlarkOS
    protected val downloadManager: DownloadManager
    protected val timeoutScaling: Double
    private val processWrapper: ProcessWrapper?
    protected val starlarkSemantics: StarlarkSemantics
    protected val identifyingStringForLogging: String?
    protected val repoMappingRecorder: RepoMappingRecorder
    private val recordedInputs: LinkedHashMap<RepoRecordedInput?, String?> =
        LinkedHashMap<RepoRecordedInput?, String?>()
    private val remoteExecutor: RepositoryRemoteExecutor?
    private val asyncTasks: MutableList<AsyncTask>
    private val allowWatchingPathsOutsideWorkspace: Boolean
    private val executorService: ExecutorService

    private var wasSuccessful = false

    /**
     * Mark the evaluation using this context as otherwise successful. This is used to determine how
     * to clean up resources in [.close].
     */
    fun markSuccessful() {
        wasSuccessful = true
    }

    @Throws(EvalException::class, IOException::class)
    override fun close() {
        // Cancel all pending async tasks.
        val hadPendingItems = cancelPendingAsyncTasks()
        // Wait for all (cancelled) async tasks to complete before cleaning up the working directory.
        // This is necessary because downloads may still be in progress and could end up writing to the
        // working directory during deletion, which would cause an error.
        // Note that just calling executorService.close() doesn't suffice as it considers tasks to be
        // completed immediately after they are cancelled, without waiting for their underlying thread
        // to complete.
        executorService.close()
        asyncTasks.forEach(Consumer { obj: AsyncTask? -> obj!!.close() })

        if (shouldDeleteWorkingDirectoryOnClose(wasSuccessful)) {
            workingDirectory.deleteTree()
        }
        if (hadPendingItems && wasSuccessful) {
            throw Starlark.errorf(
                "Pending asynchronous work after %s finished execution", identifyingStringForLogging
            )
        }
    }

    fun storeRepoMappingRecorderInThread(thread: StarlarkThread) {
        repoMappingRecorder.storeInThread(thread)
    }

    protected fun recordInputWithValue(input: RepoRecordedInput?, value: String?) {
        check(!(recordedInputs.containsKey(input) && recordedInputs.get(input) != value)) {
            "Conflicting values recorded for input %s: '%s' vs. '%s'"
                .formatted(input, recordedInputs.get(input), value)
        }
        recordedInputs.put(input, value)
    }

    @CanIgnoreReturnValue
    @Throws(InterruptedException::class, NeedsSkyframeRestartException::class, IOException::class)
    protected fun getValueAndRecordInput(input: RepoRecordedInput): String? {
        val maybeValue: MaybeValue? = input.getValue(env, directories)
        if (env.valuesMissing()) {
            throw NeedsSkyframeRestartException()
        }
        return when (maybeValue) {
            -> throw IOException(reason)
            -> {
                recordInputWithValue(input, value)
                value
            }
        }
    }

    private fun cancelPendingAsyncTasks(): Boolean {
        var hadPendingItems = false
        for (task in asyncTasks) {
            if (!task.cancel()) {
                hadPendingItems = true
                if (wasSuccessful) {
                    env.getListener()
                        .handle(
                            Event.error(
                                task.location,
                                java.lang.String.format(
                                    "Work pending after %s finished execution: %s",
                                    identifyingStringForLogging, task.description
                                )
                            )
                        )
                }
            }
        }

        return hadPendingItems
    }

    // There is no unregister(). We don't have that many futures in each repository and it just
    // introduces the failure mode of erroneously unregistering async work that's not done.
    private fun registerAsyncTask(task: AsyncTask?) {
        asyncTasks.add(task!!)
    }

    @ForOverride
    protected abstract fun shouldDeleteWorkingDirectoryOnClose(successful: Boolean): Boolean

    /** Returns all recorded inputs in the order they were recorded.  */
    fun getRecordedInputs(): ImmutableList<WithValue?> {
        return recordedInputs.entrySet().stream()
            .map<WithValue?>(Function { e: MutableMap.MutableEntry<RepoRecordedInput?, String?>? ->
                WithValue(
                    e.getKey(),
                    e.getValue()
                )
            })
            .collect(ImmutableList.toImmutableList<WithValue?>())
    }

    @Throws(RepositoryFunctionException::class)
    protected fun checkInOutputDirectory(operation: String?, path: StarlarkPath) {
        if (!path.getPath().startsWith(workingDirectory)) {
            throw RepositoryFunctionException(
                Starlark.errorf(
                    "Cannot %s outside of the repository directory for path %s", operation, path
                ),
                Transience.PERSISTENT
            )
        }
    }

    private fun warnAboutChecksumError(urls: MutableList<URI?>, errorMessage: String?) {
        // Inform the user immediately, even though the file will still be downloaded.
        // This cannot be done by a regular error event, as all regular events are recorded
        // and only shown once the execution of the repository rule is finished.
        // So we have to provide the information as update on the progress
        val url: String? = if (urls.isEmpty()) "(unknown)" else urls.get(0).toString()
        reportProgress("Will fail after download of " + url + ". " + errorMessage)
    }

    @Throws(RepositoryFunctionException::class, EvalException::class)
    private fun validateChecksum(sha256: String, integrity: String, urls: MutableList<URI?>): Optional<Checksum> {
        if (!sha256.isEmpty()) {
            if (!integrity.isEmpty()) {
                throw Starlark.errorf("Expected either 'sha256' or 'integrity', but not both")
            }
            try {
                return Optional.of<Checksum?>(Checksum.Companion.fromString(DownloadCache.KeyType.SHA256, sha256))
            } catch (e: InvalidChecksumException) {
                warnAboutChecksumError(urls, e.getMessage())
                throw RepositoryFunctionException(
                    Starlark.errorf(
                        "Checksum error in %s: %s", identifyingStringForLogging, e.getMessage()
                    ),
                    Transience.PERSISTENT
                )
            }
        }

        if (integrity.isEmpty()) {
            return Optional.empty<Checksum?>()
        }

        try {
            return Optional.of<Checksum?>(Checksum.Companion.fromSubresourceIntegrity(integrity))
        } catch (e: InvalidChecksumException) {
            warnAboutChecksumError(urls, e.getMessage())
            throw RepositoryFunctionException(
                Starlark.errorf("Checksum error in %s: %s", identifyingStringForLogging, e.getMessage()),
                Transience.PERSISTENT
            )
        }
    }

    @Throws(IOException::class, InterruptedException::class)
    private fun calculateChecksum(originalChecksum: Optional<Checksum>, path: Path): Checksum {
        if (originalChecksum.isPresent()) {
            // The checksum is checked on download, so if we got here, the user provided checksum is good
            return originalChecksum.get()
        }
        try {
            return Checksum.Companion.fromString(
                DownloadCache.KeyType.SHA256, DownloadCache.Companion.getChecksum(
                    DownloadCache.KeyType.SHA256, path
                )
            )
        } catch (e: InvalidChecksumException) {
            throw IllegalStateException(
                "Unexpected invalid checksum from internal computation of SHA-256 checksum on "
                        + path.getPathString(),
                e
            )
        }
    }

    @Throws(InterruptedException::class, RepositoryFunctionException::class)
    private fun calculateDownloadResult(checksum: Optional<Checksum>, downloadedPath: Path): StructImpl {
        val finalChecksum: Checksum
        val size: Long
        try {
            finalChecksum = calculateChecksum(checksum, downloadedPath)
            size = downloadedPath.getFileSize()
        } catch (e: IOException) {
            throw RepositoryFunctionException(
                IOException(
                    "Couldn't hash downloaded file (" + downloadedPath.getPathString() + ")", e
                ),
                Transience.PERSISTENT
            )
        }

        val out = ImmutableMap.builder<String?, Any?>()
        out.put("success", true)
        out.put("integrity", finalChecksum.toSubresourceIntegrity())

        // For compatibility with older Bazel versions that don't support non-SHA256 checksums.
        if (finalChecksum.getKeyType() == DownloadCache.KeyType.SHA256) {
            out.put("sha256", finalChecksum.toString())
        }
        out.put("size_bytes", StarlarkInt.of(size))
        return StarlarkInfo.create(StructProvider.STRUCT, out.buildOrThrow())
    }

    private inner class PendingDownload(
        private val executable: Boolean,
        private val allowFail: Boolean,
        private val outputPath: StarlarkPath,
        private val checksum: Optional<Checksum>,
        checksumValidation: RepositoryFunctionException?,
        future: Future<Path?>,
        downloadPhaser: Phaser,
        location: Location?
    ) : StarlarkValue, AsyncTask {
        private val checksumValidation: RepositoryFunctionException?
        private val future: Future<Path?>
        private val downloadPhaser: Phaser
        private val location: Location?

        init {
            this.checksumValidation = checksumValidation
            this.future = future
            this.downloadPhaser = downloadPhaser
            this.location = location
        }

        override fun getDescription(): String? {
            return java.lang.String.format("downloading to '%s'", outputPath)
        }

        override fun getLocation(): Location? {
            return location
        }

        override fun cancel(): Boolean {
            return !future.cancel(false)
        }

        override fun close() {
            if (downloadPhaser.register() != 0) {
                // Not in the download phase, either the download completed normally or
                // it has completed after a cancellation.
                return
            }
            Profiler.instance().profile("Cancelling download " + outputPath).use { c ->
                downloadPhaser.arriveAndAwaitAdvance()
            }
        }

        @StarlarkMethod(
            name = "wait", doc = """
            Blocks until the completion of the download and returns or throws as blocking <code>download()</code> call would.
            
            """.trimIndent()
        )
        @Throws(InterruptedException::class, RepositoryFunctionException::class)
        fun await(): StructImpl {
            return completeDownload(this)
        }

        override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            printer.append(java.lang.String.format("<pending download to '%s'>", outputPath))
        }

        override fun debugPrint(printer: Printer, thread: StarlarkThread?) {
            printer.append(
                java.lang.String.format("<pending download (state: %s) to '%s'>", future.state(), outputPath)
            )
        }
    }

    @Throws(RepositoryFunctionException::class, InterruptedException::class)
    private fun completeDownload(pendingDownload: PendingDownload): StructImpl {
        var downloadedPath: Path
        try {
            downloadedPath = downloadManager.finalizeDownload(pendingDownload.future)
            if (pendingDownload.executable) {
                pendingDownload.outputPath.getPath().setExecutable(true)
            }
        } catch (e: IOException) {
            if (pendingDownload.allowFail) {
                val struct =
                    ImmutableMap.of<String?, Any?>("success", false, "error", e.toString())
                return StarlarkInfo.create(StructProvider.STRUCT, struct)
            } else {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            }
        } catch (e: InvalidPathException) {
            throw RepositoryFunctionException(
                Starlark.errorf(
                    "Could not create output path %s: %s", pendingDownload.outputPath, e.getMessage()
                ),
                Transience.PERSISTENT
            )
        } finally {
            pendingDownload.close()
        }
        if (pendingDownload.checksumValidation != null) {
            throw pendingDownload.checksumValidation
        }

        return calculateDownloadResult(pendingDownload.checksum, downloadedPath)
    }

    @StarlarkMethod(
        name = "download",
        doc = """
Downloads a file to the output path for the provided url and returns a struct containing <code>success</code>, a flag which is <code>true</code> if the download completed successfully, and if successful, a hash of the file with the fields <code>sha256</code> and <code>integrity</code>, as well as <code>size_bytes</code>, which contains the size of the downloaded file in bytes as an integer. If the value of the <code>success</code> field is false, the <code>error</code> field will be set with a message indicating why the download failed. The message in the <code>error</code> field is for debugging purposes only and should not be relied upon as a stable API (the format of the string can change between patch versions of Bazel). When <code>sha256</code> or <code>integrity</code> is user specified, setting an explicit <code>canonical_id</code> is highly recommended. e.g. <a href='/rules/lib/repo/cache#get_default_canonical_id'><code>get_default_canonical_id</code></a>

""".trimIndent(),
        useStarlarkThread = true,
        parameters = [Param(
            name = "url",
            allowedTypes = [ParamType(type = String::class), ParamType(
                type = Iterable::class,
                generic1 = String::class
            )],
            named = true,
            doc = "List of mirror URLs referencing the same file."
        ), Param(
            name = "output",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            defaultValue = "''",
            named = true,
            doc = "path to the output file, relative to the repository directory."
        ), Param(
            name = "sha256", defaultValue = "''", named = true, doc = """
                The expected SHA-256 hash of the file downloaded. This must match the SHA-256 hash of the file downloaded. It is a security risk to omit the SHA-256 as remote files can change. At best omitting this field will make your build non-hermetic. It is optional to make development easier but should be set before shipping. If provided, the repository cache will first be checked for a file with the given hash; a download will only be attempted if the file was not found in the cache. After a successful download, the file will be added to the cache.
                
                """.trimIndent()
        ), Param(
            name = "executable",
            defaultValue = "False",
            named = true,
            doc = "Set the executable flag on the created file, false by default."
        ), Param(
            name = "allow_fail", defaultValue = "False", named = true, doc = """
                If set, indicate the error in the return value instead of raising an error for failed downloads.
                
                """.trimIndent()
        ), Param(
            name = "canonical_id", defaultValue = "''", named = true, doc = """
                If set, restrict cache hits to those cases where the file was added to the cache with the same canonical id. By default caching uses the checksum (<code>sha256</code> or <code>integrity</code>).
                
                """.trimIndent()
        ), Param(
            name = "auth",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying authentication information for some of the URLs."
        ), Param(
            name = "headers",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying http headers for all URLs."
        ), Param(
            name = "integrity", defaultValue = "''", named = true, positional = false, doc = """
                Expected checksum of the file downloaded, in Subresource Integrity format. This must match the checksum of the file downloaded. It is a security risk to omit the checksum as remote files can change. At best omitting this field will make your build non-hermetic. It is optional to make development easier but should be set before shipping. If provided, the repository cache will first be checked for a file with the given checksum; a download will only be attempted if the file was not found in the cache. After a successful download, the file will be added to the cache.
                
                """.trimIndent()
        ), Param(
            name = "block", defaultValue = "True", named = true, positional = false, doc = """
                If set to false, the call returns immediately and instead of the regular return value, it returns a token with one single method, wait(), which blocks until the download is finished and returns the usual return value or throws as usual.
                
                """.trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun download(
        url: Any?,
        output: Any,
        sha256: String,
        executable: Boolean,
        allowFail: Boolean,
        canonicalId: String?,
        authUnchecked: Dict<*, *>?,  // <String, Dict> expected
        headersUnchecked: Dict<*, *>?,  // <String, List<String> | String> expected
        integrity: String,
        block: Boolean,
        thread: StarlarkThread
    ): Any? {
        var download: PendingDownload? = null
        val authHeaders: ImmutableMap<URI?, MutableMap<String?, MutableList<String?>?>?> =
            getAuthHeaders(getAuthContents(authUnchecked, "auth"))

        val headers: ImmutableMap<String?, MutableList<String?>?> = getHeaderContents(headersUnchecked, "headers")

        val urls: ImmutableList<URI?> =
            getUrls(
                url,  /* ensureNonEmpty= */
                !allowFail,  /* checksumGiven= */
                !Strings.isNullOrEmpty(sha256)
                        || !Strings.isNullOrEmpty(integrity)
            )
        var checksum: Optional<Checksum>? = null
        var checksumValidation: RepositoryFunctionException? = null
        try {
            checksum = validateChecksum(sha256, integrity, urls)
        } catch (e: RepositoryFunctionException) {
            checksum = Optional.empty<Checksum?>()
            checksumValidation = e
        }

        val outputPath = getPath(output)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newDownloadEvent(
                urls,
                output.toString(),
                sha256,
                integrity,
                executable,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)

        try {
            checkInOutputDirectory("write", outputPath)
            makeDirectories(outputPath.getPath())
        } catch (e: IOException) {
            val downloadPhaser: Phaser = Phaser()
            download =
                StarlarkBaseExternalContext.PendingDownload(
                    executable,
                    allowFail,
                    outputPath,
                    checksum,
                    checksumValidation,
                    Futures.immediateFailedFuture<Path?>(e),
                    downloadPhaser,
                    thread.getCallerLocation()
                )
        }
        if (download == null) {
            val downloadPhaser: Phaser = Phaser()
            val downloadFuture =
                downloadManager.startDownload(
                    executorService,
                    urls,
                    headers,
                    authHeaders,
                    checksum,
                    canonicalId,
                    Optional.empty<String?>(),
                    outputPath.getPath(),
                    nonstrictRepoEnv,
                    identifyingStringForLogging,
                    downloadPhaser,  // The repo rule may modify the file after the download, so we cannot guarantee that
                    // hardlinking is safe.
                    /* mayHardlink= */
                    false
                )
            download =
                StarlarkBaseExternalContext.PendingDownload(
                    executable,
                    allowFail,
                    outputPath,
                    checksum,
                    checksumValidation,
                    downloadFuture,
                    downloadPhaser,
                    thread.getCallerLocation()
                )
            registerAsyncTask(download)
        }
        if (!block) {
            return download
        } else {
            return completeDownload(download!!)
        }
    }

    init {
        this.directories = directories
        this.env = env
        this.repoEnv = repoEnv
        this.nonstrictRepoEnv = nonstrictRepoEnv
        this.osObject = StarlarkOS(this.repoEnv)
        this.downloadManager = downloadManager
        this.timeoutScaling = timeoutScaling
        this.processWrapper = processWrapper
        this.starlarkSemantics = starlarkSemantics
        this.identifyingStringForLogging = identifyingStringForLogging
        this.remoteExecutor = remoteExecutor
        this.asyncTasks = ArrayList<AsyncTask>()
        this.allowWatchingPathsOutsideWorkspace = allowWatchingPathsOutsideWorkspace
        this.executorService =
            Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual()
                    .name("downloads[" + identifyingStringForLogging + "]-", 0)
                    .factory()
            )
        // This is used by the `Label()` constructor in Starlark, to record any attempts to resolve
        // apparent repo names to canonical repo names. See #20721 for why this is necessary.
        this.repoMappingRecorder =
            RepoMappingRecorder { fromRepo: RepositoryName?, apparentRepoName: String?, canonicalRepoName: RepositoryName? ->
                recordInputWithValue(
                    RecordedRepoMapping(fromRepo, apparentRepoName),
                    if (canonicalRepoName.isVisible()) canonicalRepoName.getName() else null
                )
            }
    }

    @StarlarkMethod(
        name = "download_and_extract",
        doc = """
Downloads a file to the output path for the provided url, extracts it, and returns a struct containing <code>success</code>, a flag which is <code>true</code> if the download completed successfully, and if successful, a hash of the file with the fields <code>sha256</code> and <code>integrity</code>, as well as the <code>size_bytes</code> of the downloaded file in bytes as an integer. If the value of the <code>success</code> field is false, the <code>error</code> field will be set with a message indicating why the download failed. The message in the <code>error</code> field is for debugging purposes only and should not be relied upon as a stable API (the format of the string can change between patch versions of Bazel). When <code>sha256</code> or <code>integrity</code> is user specified, setting an explicit <code>canonical_id</code> is highly recommended. e.g. <a href='/rules/lib/repo/cache#get_default_canonical_id'><code>get_default_canonical_id</code></a>

""".trimIndent(),
        useStarlarkThread = true,
        parameters = [Param(
            name = "url",
            allowedTypes = [ParamType(type = String::class), ParamType(
                type = Iterable::class,
                generic1 = String::class
            )],
            named = true,
            doc = "List of mirror URLs referencing the same file."
        ), Param(
            name = "output",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            defaultValue = "''",
            named = true,
            doc = """
                Path to the directory where the archive will be unpacked, relative to the repository directory.
                
                """.trimIndent()
        ), Param(
            name = "sha256", defaultValue = "''", named = true, doc = """
                The expected SHA-256 hash of the file downloaded. This must match the SHA-256 hash of the file downloaded. It is a security risk to omit the SHA-256 as remote files can change. At best omitting this field will make your build non-hermetic. It is optional to make development easier but should be set before shipping. If provided, the repository cache will first be checked for a file with the given hash; a download will only be attempted if the file was not found in the cache. After a successful download, the file will be added to the cache.
                
                """.trimIndent()
        ), Param(
            name = "type",
            defaultValue = "''",
            named = true,  // Since this is an annotation label, the SUPPORTED_DECOMPRESSION_FORMATS string
            // must be a compile time constant (we can't call a method to get it).
            doc = ("""
                The archive type of the downloaded file. By default, the archive type is determined from the file extension of the URL. If the file has no extension, you can explicitly specify either 
                """
                .trimIndent()
                    + SUPPORTED_DECOMPRESSION_FORMATS
                    + " here.")
        ), Param(
            name = "strip_prefix", defaultValue = "''", named = true, doc = """
                A directory prefix to strip from the extracted files. Many archives contain a
                top-level directory that contains all files in the archive. Instead of needing to
                specify this prefix over and over in the <code>build_file</code>, this field can
                be used to strip it from extracted files.

                <p>For compatibility, this parameter may also be used under the deprecated name
                <code>stripPrefix</code>. Only one of <code>strip_prefix</code> or
                <code>strip_components</code> can be used.
                
                """.trimIndent()
        ), Param(
            name = "allow_fail", defaultValue = "False", named = true, doc = """
                If set, indicate the error in the return value instead of raising an error for failed downloads.
                
                """.trimIndent()
        ), Param(
            name = "canonical_id", defaultValue = "''", named = true, doc = """
                If set, restrict cache hits to those cases where the file was added to the cache with the same canonical id. By default caching uses the checksum
                (<code>sha256</code> or <code>integrity</code>).
                
                """.trimIndent()
        ), Param(
            name = "auth",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying authentication information for some of the URLs."
        ), Param(
            name = "headers",
            defaultValue = "{}",
            named = true,
            doc = "An optional dict specifying http headers for all URLs."
        ), Param(
            name = "integrity", defaultValue = "''", named = true, positional = false, doc = """
                Expected checksum of the file downloaded, in Subresource Integrity format. This must match the checksum of the file downloaded. It is a security risk to omit the checksum as remote files can change. At best omitting this field will make your build non-hermetic. It is optional to make development easier but should be set before shipping. If provided, the repository cache will first be checked for a file with the given checksum; a download will only be attempted if the file was not found in the cache. After a successful download, the file will be added to the cache. 
                """.trimIndent()
        ), Param(
            name = "rename_files", defaultValue = "{}", named = true, positional = false, doc = """
An optional dict specifying files to rename during the extraction. Archive entries with names exactly matching a key will be renamed to the value, prior to any directory prefix adjustment. This can be used to extract archives that contain non-Unicode filenames, or which have files that would extract to the same path on case-insensitive filesystems.

""".trimIndent()
        ), Param(
            name = "stripPrefix",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "''"
        ), Param(
            name = "strip_components", positional = false, named = true, defaultValue = "0", doc = """
Strip the given number of leading components from file paths on extraction. Only one of
<code>strip_components</code> or <code>strip_prefix</code> can be used.

""".trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, InterruptedException::class, EvalException::class)
    fun downloadAndExtract(
        url: Any?,
        output: Any,
        sha256: String,
        type: String,
        stripPrefix: String,
        allowFail: Boolean,
        canonicalId: String?,
        authUnchecked: Dict<*, *>?,  // <String, Dict> expected
        headersUnchecked: Dict<*, *>?,  // <String, List<String> | String> expected
        integrity: String,
        renameFiles: Dict<*, *>?,  // <String, String> expected
        oldStripPrefix: String,
        stripComponentsI: StarlarkInt?,
        thread: StarlarkThread
    ): StructImpl {
        var stripPrefix = stripPrefix
        stripPrefix = renamedStripPrefix("download_and_extract", stripPrefix, oldStripPrefix)
        val stripComponents = Starlark.toInt(stripComponentsI, "strip_components")
        validateStripping("download_and_extract", stripPrefix, stripComponents)
        val authHeaders: ImmutableMap<URI?, MutableMap<String?, MutableList<String?>?>?> =
            getAuthHeaders(getAuthContents(authUnchecked, "auth"))

        val headers: ImmutableMap<String?, MutableList<String?>?> = getHeaderContents(headersUnchecked, "headers")

        val urls: ImmutableList<URI?> =
            getUrls(
                url,  /* ensureNonEmpty= */
                !allowFail,  /* checksumGiven= */
                !Strings.isNullOrEmpty(sha256)
                        || !Strings.isNullOrEmpty(integrity)
            )
        var checksum: Optional<Checksum>
        var checksumValidation: RepositoryFunctionException? = null
        try {
            checksum = validateChecksum(sha256, integrity, urls)
        } catch (e: RepositoryFunctionException) {
            checksum = Optional.empty<Checksum?>()
            checksumValidation = e
        }

        val renameFilesMap: MutableMap<String?, String?> =
            Dict.cast<String?, String?>(renameFiles, String::class.java, String::class.java, "rename_files")

        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newDownloadAndExtractEvent(
                urls,
                output.toString(),
                sha256,
                integrity,
                type,
                stripPrefix,
                renameFilesMap,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )

        val outputPath = getPath(output)
        checkInOutputDirectory("write", outputPath)
        createDirectory(outputPath.getPath())

        val downloadedPath: Path
        val downloadDirectory: Path
        try {
            // Download to temp directory inside the outputDirectory and delete it after extraction
            downloadDirectory = outputPath.getPath().createTempDirectory("temp")

            val downloadPhaser: Phaser = Phaser()
            val pendingDownload =
                downloadManager.startDownload(
                    executorService,
                    urls,
                    headers,
                    authHeaders,
                    checksum,
                    canonicalId,
                    Optional.of<String?>(type),
                    downloadDirectory,
                    nonstrictRepoEnv,
                    identifyingStringForLogging,
                    downloadPhaser,  // The archive is not going to be modified and not accessible to the user, so its safe
                    // to hardlink.
                    /* mayHardlink= */
                    true
                )
            // Ensure that the download is cancelled if the repo rule is restarted as it runs in its own
            // executor.
            val pendingTask: PendingDownload =
                StarlarkBaseExternalContext.PendingDownload( /* executable= */
                    false,
                    allowFail,
                    outputPath,
                    checksum,
                    checksumValidation,
                    pendingDownload,
                    downloadPhaser,
                    thread.getCallerLocation()
                )
            registerAsyncTask(pendingTask)
            downloadedPath = downloadManager.finalizeDownload(pendingDownload)
        } catch (e: IOException) {
            env.getListener().post(w)
            if (allowFail) {
                val struct =
                    ImmutableMap.of<String?, Any?>("success", false, "error", e.toString())
                return StarlarkInfo.create(StructProvider.STRUCT, struct)
            } else {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            }
        }
        if (checksumValidation != null) {
            throw checksumValidation
        }
        env.getListener().post(w)
        Profiler.instance().profile("extracting: " + identifyingStringForLogging).use { c ->
            env.getListener()
                .post(
                    ExtractProgress(
                        outputPath.getPath().toString(), "Extracting " + downloadedPath.getBaseName()
                    )
                )
            DecompressorValue.Companion.decompress(
                DecompressorDescriptor.Companion.builder()
                    .setContext(identifyingStringForLogging)
                    .setArchivePath(downloadedPath)
                    .setDestinationPath(outputPath.getPath())
                    .setPrefix(stripPrefix)
                    .setStripComponents(stripComponents)
                    .setRenameFiles(renameFilesMap)
                    .build(),  // Type does NOT need to be passed here, as the existing code renames the archive path to
                // include the type extension. The decompression code then uses the file extension to get
                // the proper decompressor.
                /* forceDecompressorType= */
                Optional.empty<String?>()
            )
            env.getListener().post(ExtractProgress(outputPath.getPath().toString()))
        }
        val downloadResult: StructImpl = calculateDownloadResult(checksum, downloadedPath)
        deleteTreeWithRetries(downloadDirectory)
        return downloadResult
    }

    @StarlarkMethod(
        name = "extract",
        doc = "Extract an archive to the repository directory.",
        useStarlarkThread = true,
        parameters = [Param(
            name = "archive",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            named = true,
            doc = "path to the archive that will be unpacked,"
                    + " relative to the repository directory."
        ), Param(
            name = "output",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            defaultValue = "''",
            named = true,
            doc = "path to the directory where the archive will be unpacked,"
                    + " relative to the repository directory."
        ), Param(
            name = "strip_prefix", defaultValue = "''", named = true, doc = """
                a directory prefix to strip from the extracted files. Many archives contain a
                top-level directory that contains all files in the archive. Instead of needing to
                specify this prefix over and over in the <code>build_file</code>, this field can be
                used to strip it from extracted files.

                <p>For compatibility, this parameter may also be used under the deprecated name
                <code>stripPrefix</code>. Only one of <code>strip_prefix</code> or
                <code>strip_components</code> can be set.
                
                """.trimIndent()
        ), Param(
            name = "rename_files",
            defaultValue = "{}",
            named = true,
            positional = false,
            doc = ("An optional dict specifying files to rename during the extraction. Archive entries"
                    + " with names exactly matching a key will be renamed to the value, prior to"
                    + " any directory prefix adjustment. This can be used to extract archives that"
                    + " contain non-Unicode filenames, or which have files that would extract to"
                    + " the same path on case-insensitive filesystems.")
        ), Param(
            name = "watch_archive",
            defaultValue = "'auto'",
            positional = false,
            named = true,
            doc = ("whether to <a href=\"#watch\">watch</a> the archive file. Can be the string "
                    + "'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking "
                    + "the <a href=\"#watch\"><code>watch()</code></a> method; passing 'no' does "
                    + "not attempt to watch the file; passing 'auto' will only attempt to watch "
                    + "the file when it is legal to do so (see <code>watch()</code> docs for more "
                    + "information.")
        ), Param(
            name = "stripPrefix",
            documented = false,
            positional = false,
            named = true,
            defaultValue = "''"
        ), Param(
            name = "strip_components", positional = false, named = true, defaultValue = "0", doc = """
Strip the given number of leading components from file paths on extraction. Only one of
<code>strip_components</code> or <code>strip_prefix</code> can be set.

""".trimIndent()
        ), Param(
            name = "type",
            defaultValue = "''",
            named = true,
            positional = false,  // Since this is an annotation label, the SUPPORTED_DECOMPRESSION_FORMATS string
            // must be a compile time constant (we can't call a method to get it).
            doc = ("""
                The archive type of the downloaded file. By default, the archive type is determined from the file extension of the URL. If the file has no extension, you can explicitly specify either 
                """
                .trimIndent()
                    + SUPPORTED_DECOMPRESSION_FORMATS
                    + " here.")
        )]
    )
    @Throws(RepositoryFunctionException::class, InterruptedException::class, EvalException::class)
    fun extract(
        archive: Any,
        output: Any,
        stripPrefix: String,
        renameFiles: Dict<*, *>?,  // <String, String> expected
        watchArchive: String,
        oldStripPrefix: String,
        stripComponentsI: StarlarkInt?,
        type: String?,
        thread: StarlarkThread
    ) {
        var stripPrefix = stripPrefix
        stripPrefix = renamedStripPrefix("extract", stripPrefix, oldStripPrefix)
        val stripComponents = Starlark.toInt(stripComponentsI, "strip_components")
        validateStripping("extract", stripPrefix, stripComponents)
        val archivePath = getPath(archive)

        if (!archivePath.exists()) {
            throw RepositoryFunctionException(
                Starlark.errorf("Archive path '%s' does not exist.", archivePath), Transience.TRANSIENT
            )
        }
        if (archivePath.isDir()) {
            throw Starlark.errorf("attempting to extract a directory: %s", archivePath)
        }
        maybeWatch(archivePath, ShouldWatch.Companion.fromString(watchArchive))

        val outputPath = getPath(output)
        checkInOutputDirectory("write", outputPath)

        val renameFilesMap: MutableMap<String?, String?> =
            Dict.cast<String?, String?>(renameFiles, String::class.java, String::class.java, "rename_files")

        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newExtractEvent(
                archive.toString(),
                output.toString(),
                stripPrefix,
                renameFilesMap,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)

        env.getListener()
            .post(
                ExtractProgress(
                    outputPath.getPath().toString(), "Extracting " + archivePath.getBasename()
                )
            )
        DecompressorValue.Companion.decompress(
            DecompressorDescriptor.Companion.builder()
                .setContext(identifyingStringForLogging)
                .setArchivePath(archivePath.getPath())
                .setDestinationPath(outputPath.getPath())
                .setPrefix(stripPrefix)
                .setStripComponents(stripComponents)
                .setRenameFiles(renameFilesMap)
                .build(),
            Optional.ofNullable<String?>(type).filter(Predicate { s: String? -> !s.isBlank() })
        )
        env.getListener().post(ExtractProgress(outputPath.getPath().toString()))
    }

    /** A progress event that reports about archive extraction.  */
    protected class ExtractProgress : ExtendedEventHandler.FetchProgress {
        private val repositoryPath: String?
        private val progress: String?
        private val isFinished: Boolean

        internal constructor(repositoryPath: String?, progress: String?) {
            this.repositoryPath = repositoryPath
            this.progress = progress
            this.isFinished = false
        }

        internal constructor(repositoryPath: String?) {
            this.repositoryPath = repositoryPath
            this.progress = ""
            this.isFinished = true
        }

        override fun getResourceIdentifier(): String? {
            return repositoryPath
        }

        override fun getProgress(): String? {
            return progress
        }

        override fun isFinished(): Boolean {
            return isFinished
        }
    }

    @StarlarkMethod(
        name = "file",
        doc = "Generates a file in the repository directory with the provided content.",
        useStarlarkThread = true,
        parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the file to create, relative to the repository directory."
        ), Param(
            name = "content",
            named = true,
            defaultValue = "''",
            doc = "The content of the file to create, empty by default."
        ), Param(
            name = "executable",
            named = true,
            defaultValue = "True",
            doc = "Set the executable flag on the created file, true by default."
        ), Param(
            name = "legacy_utf8", named = true, defaultValue = "False", doc = """
                No-op. This parameter is deprecated and will be removed in a future version of Bazel.
                
                """.trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun createFile(
        path: Any, content: String?, executable: Boolean, legacyUtf8: Boolean?, thread: StarlarkThread
    ) {
        val p = getPath(path)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newFileEvent(
                p.toString(),
                content,
                executable,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        try {
            checkInOutputDirectory("write", p)
            makeDirectories(p.getPath())
            p.getPath().delete()
            p.getPath().getOutputStream().use { stream ->
                stream.write(StringUnsafe.getInternalStringBytes(content))
            }
            if (executable) {
                p.getPath().setExecutable(true)
            }
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        } catch (e: InvalidPathException) {
            throw RepositoryFunctionException(
                Starlark.errorf("Could not create %s: %s", p, e.getMessage()), Transience.PERSISTENT
            )
        }
    }

    @StarlarkMethod(
        name = "getenv",
        doc = """
          Returns the value of an environment variable <code>name</code> as a string if exists, or <code>default</code> if it doesn't. <p>When building incrementally, any change to the value of the variable named by <code>name</code> will cause this repository to be re-fetched.
          
          """.trimIndent(),
        parameters = [Param(
            name = "name",
            doc = "Name of desired environment variable.",
            allowedTypes = [ParamType(type = String::class)]
        ), Param(
            name = "default",
            doc = "Default value to return if <code>name</code> is not found.",
            allowedTypes = [ParamType(type = String::class), ParamType(type = NoneType::class)],
            defaultValue = "None"
        )],
        allowReturnNones = true
    )
    @Throws(
        InterruptedException::class, NeedsSkyframeRestartException::class
    )
    fun getEnvironmentValue(name: String?, defaultValue: Any?): String? {
        try {
            val value = getValueAndRecordInput(RepoRecordedInput.EnvVar(name))
            return if (value != null) value else nullIfNone<String?>(defaultValue, String::class.java)
        } catch (e: IOException) {
            throw IllegalStateException("getting EnvVar never throws IOException", e)
        }
    }

    @StarlarkMethod(
        name = "path", doc = """
          Returns a path from a string, label, or path. If this context is a <code>repository_ctx</code>, a relative path will resolve relative to the repository directory. If it is a <code>module_ctx</code>, a relative path will resolve relative to a temporary working directory for this module extension. If the path is a label, it will resolve to the path of the corresponding file. Note that remote repositories and module extensions are executed during the analysis phase and thus cannot depends on a target result (the label should point to a non-generated file). If path is a path, it will return that path as is.
          
          """.trimIndent(), parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "<code>string</code>, <code>Label</code> or <code>path</code> from which to create"
                    + " a path from."
        )]
    )
    @Throws(EvalException::class, InterruptedException::class)
    fun getPath(path: Any): StarlarkPath {
        return when (path) {
            -> StarlarkPath(this, workingDirectory.getRelative(s))
            -> getPathFromLabel(label)
            -> starlarkPath
            else -> throw IllegalArgumentException("expected string or label for path")
        }
    }

    @StarlarkMethod(
        name = "read",
        doc = "Reads the content of a file on the filesystem.",
        useStarlarkThread = true,
        parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the file to read from."
        ), Param(
            name = "watch", defaultValue = "'auto'", positional = false, named = true, doc = """
                Whether to <a href="#watch">watch</a> the file. Can be the string 'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking the <a href="#watch"><code>watch()</code></a> method; passing 'no' does not attempt to watch the file; passing 'auto' will only attempt to watch the file when it is legal to do so (see <code>watch()</code> docs for more information.
                
                """.trimIndent()
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun readFile(path: Any, watch: String, thread: StarlarkThread): String {
        val p = getPath(path)
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newReadEvent(
                p.toString(), identifyingStringForLogging, thread.getCallerLocation()
            )
        env.getListener().post(w)
        maybeWatch(p, ShouldWatch.Companion.fromString(watch))
        if (p.isDir()) {
            throw Starlark.errorf("attempting to read() a directory: %s", p)
        }
        try {
            return FileSystemUtils.readContent(p.getPath(), StandardCharsets.ISO_8859_1)
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    /**
     * Converts a regular [Path] to a [RepoCacheFriendlyPath] based on [ ]. If the path shouldn't be watched for whatever reason, returns null. If it's
     * illegal to watch the path in the current context, but the user still requested a watch, throws
     * an exception.
     */
    @Throws(EvalException::class)
    protected fun toRepoCacheFriendlyPath(path: Path, shouldWatch: ShouldWatch?): RepoCacheFriendlyPath? {
        if (shouldWatch == ShouldWatch.NO) {
            return null
        }
        if (path.startsWith(workingDirectory)) {
            // The path is under the working directory. Don't watch it, as it would cause a dependency
            // cycle.
            if (shouldWatch == ShouldWatch.AUTO) {
                return null
            }
            throw Starlark.errorf("attempted to watch path under working directory")
        }
        if (path.startsWith(directories.getWorkspace())) {
            // The file is under the workspace root.
            val relPath: PathFragment = path.relativeTo(directories.getWorkspace())
            return RepoCacheFriendlyPath.createInsideWorkspace(RepositoryName.Companion.MAIN, relPath)
        }
        val outputBaseExternal: Path =
            directories.getOutputBase().getRelative(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
        if (path.startsWith(outputBaseExternal)) {
            val relPath: PathFragment = path.relativeTo(outputBaseExternal)
            if (!relPath.isEmpty()) {
                // The file is under a repo root.
                val repoName: RepositoryName
                try {
                    repoName = RepositoryName.Companion.create(relPath.getSegment(0))
                } catch (e: LabelSyntaxException) {
                    throw Starlark.errorf(
                        "attempted to watch path under external repository directory: %s", e.getMessage()
                    )
                }
                val repoRelPath: PathFragment? =
                    relPath.relativeTo(PathFragment.createAlreadyNormalized(repoName.getName()))
                return RepoCacheFriendlyPath.createInsideWorkspace(repoName, repoRelPath)
            }
        }
        // The file is just under a random absolute path.
        if (!allowWatchingPathsOutsideWorkspace) {
            if (shouldWatch == ShouldWatch.AUTO) {
                return null
            }
            throw Starlark.errorf(
                "attempted to watch path outside workspace, but it's prohibited in the current context"
            )
        }
        return RepoCacheFriendlyPath.createOutsideWorkspace(path.asFragment())
    }

    /** Whether to watch a path. See [.readFile] for semantics  */
    enum class ShouldWatch {
        YES,
        NO,
        AUTO;

        companion object {
            @Throws(EvalException::class)
            fun fromString(s: String): ShouldWatch {
                return when (s) {
                    "yes" -> ShouldWatch.YES
                    "no" -> ShouldWatch.NO
                    "auto" -> ShouldWatch.AUTO
                    else -> throw Starlark.errorf(
                        "bad value for 'watch' parameter; want 'yes', 'no', or 'auto', got %s", s
                    )
                }
            }
        }
    }

    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    protected fun maybeWatch(starlarkPath: StarlarkPath, shouldWatch: ShouldWatch?) {
        val repoCacheFriendlyPath: RepoCacheFriendlyPath? =
            toRepoCacheFriendlyPath(starlarkPath.getPath(), shouldWatch)
        if (repoCacheFriendlyPath == null) {
            return
        }
        try {
            getValueAndRecordInput(RepoRecordedInput.File(repoCacheFriendlyPath))
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun maybeWatchDirents(path: Path, shouldWatch: ShouldWatch?) {
        val repoCacheFriendlyPath: RepoCacheFriendlyPath? = toRepoCacheFriendlyPath(path, shouldWatch)
        if (repoCacheFriendlyPath == null) {
            return
        }
        try {
            getValueAndRecordInput(RepoRecordedInput.Dirents(repoCacheFriendlyPath))
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "watch",
        doc = """
          Tells Bazel to watch for changes to the given path, whether or not it exists, or whether it's a file or a directory. Any changes to the file or directory will invalidate this repository or module extension, and cause it to be refetched or re-evaluated next time.<p>"Changes" include changes to the contents of the file (if the path is a file); if the path was a file but is now a directory, or vice versa; and if the path starts or stops existing. Notably, this does <em>not</em> include changes to any files under the directory if the path is a directory. For that, use <a href="path.html#readdir"><code>path.readdir()</code></a> instead.<p>Note that attempting to watch paths inside the repo currently being fetched, or inside the working directory of the current module extension, will result in an error. A module extension attempting to watch a path outside the current Bazel workspace will also result in an error.
          
          """.trimIndent(),
        parameters = [Param(
            name = "path",
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the file to watch."
        )]
    )
    @Throws(RepositoryFunctionException::class, EvalException::class, InterruptedException::class)
    fun watchForStarlark(path: Any) {
        maybeWatch(getPath(path), ShouldWatch.YES)
    }

    @StarlarkMethod(
        name = "report_progress",
        doc = "Updates the progress status for the fetching of this repository or module extension.",
        parameters = [Param(
            name = "status",
            defaultValue = "''",
            allowedTypes = [ParamType(type = String::class)],
            doc = "<code>string</code> describing the current status of the fetch progress."
        )]
    )
    fun reportProgress(status: String?) {
        env.getListener()
            .post(
                object : ExtendedEventHandler.FetchProgress {
                    override fun getResourceIdentifier(): String? {
                        return identifyingStringForLogging
                    }

                    override fun getProgress(): String? {
                        return status
                    }

                    override fun isFinished(): Boolean {
                        return false
                    }
                })
    }

    @get:StarlarkMethod(
        name = "os",
        structField = true,
        doc = "A struct to access information from the system."
    )
    val os: StarlarkOS
        get() {
            // Historically this event reported the location of the ctx.os expression, but that's no longer
            // available in the interpreter API. Now we just use a dummy location, and the user must
            // manually inspect the code where this context object is used if they wish to find the
            // offending ctx.os expression.
            val w: WorkspaceRuleEvent? =
                WorkspaceRuleEvent.newOsEvent(identifyingStringForLogging, Location.BUILTIN)
            env.getListener().post(w)
            return osObject
        }

    /** Whether this context supports remote execution.  */
    abstract val isRemotable: Boolean

    private fun canExecuteRemote(): Boolean {
        val featureEnabled =
            starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_REPO_REMOTE_EXEC)
        val remoteExecEnabled = remoteExecutor != null
        return featureEnabled && this.isRemotable && remoteExecEnabled
    }

    @get:Throws(EvalException::class)
    protected abstract val remoteExecProperties: ImmutableMap<String?, String?>?

    @Throws(EvalException::class, InterruptedException::class)
    private fun getRemotePathFromLabel(label: Label): MutableMap.MutableEntry<PathFragment?, Path?> {
        val localPath = getPathFromLabel(label).getPath()
        val remotePath: PathFragment? =
            label.getPackageIdentifier().getSourceRoot().getRelative(label.getName())
        return Maps.immutableEntry<PathFragment?, Path?>(remotePath, localPath)
    }

    @Throws(EvalException::class, InterruptedException::class)
    private fun executeRemote(
        argumentsUnchecked: Sequence<*>,  // <String> or <Label> expected
        timeout: Int,
        environment: MutableMap<String?, String?>,
        quiet: Boolean,
        workingDirectory: String?
    ): StarlarkExecutionResult {
        Preconditions.checkState(canExecuteRemote())

        val inputsBuilder: ImmutableSortedMap.Builder<PathFragment?, Path?> =
            ImmutableSortedMap.naturalOrder<PathFragment?, Path?>()
        val argumentsBuilder = ImmutableList.builder<String?>()
        for (argumentUnchecked in argumentsUnchecked) {
            if (argumentUnchecked is Label) {
                val remotePath: MutableMap.MutableEntry<PathFragment?, Path?> =
                    getRemotePathFromLabel(argumentUnchecked)
                argumentsBuilder.add(remotePath.getKey().toString())
                inputsBuilder.put(remotePath)
            } else {
                argumentsBuilder.add(argumentUnchecked.toString())
            }
        }

        val arguments: ImmutableList<String> = argumentsBuilder.build()

        try {
            Profiler.instance()
                .profile(
                    ProfilerTask.STARLARK_REPOSITORY_FN, Supplier { profileArgsDesc("remote", arguments) }).use { c ->
                    val result: ExecutionResult =
                        remoteExecutor.execute(
                            arguments,
                            inputsBuilder.buildOrThrow(),
                            this.remoteExecProperties,
                            ImmutableMap.copyOf<String?, String?>(environment),
                            workingDirectory,
                            Duration.ofSeconds(timeout.toLong())
                        )
                    val stdout = String(result.stdout(), StandardCharsets.US_ASCII)
                    val stderr = String(result.stderr(), StandardCharsets.US_ASCII)

                    if (!quiet) {
                        val outErr: OutErr = OutErr.SYSTEM_OUT_ERR
                        outErr.printOut(stdout)
                        outErr.printErr(stderr)
                    }
                    return StarlarkExecutionResult(result.exitCode(), stdout, stderr)
                }
        } catch (e: IOException) {
            throw Starlark.errorf("remote_execute failed: %s", e.getMessage())
        }
    }

    @Throws(EvalException::class)
    private fun validateExecuteArguments(arguments: Sequence<*>) {
        val isRemotable = this.isRemotable
        for (i in arguments.indices) {
            val arg: Any? = arguments.get(i)
            if (isRemotable) {
                if (!(arg is String || arg is Label)) {
                    throw Starlark.errorf("Argument %d of execute is neither a label nor a string.", i)
                }
            } else {
                if (!(arg is String || arg is Label || arg is StarlarkPath)) {
                    throw Starlark.errorf("Argument %d of execute is neither a path, label, nor string.", i)
                }
            }
        }
    }

    @StarlarkMethod(
        name = "execute", doc = """
          Executes the command given by the list of arguments. The execution time of the command is limited by <code>timeout</code> (in seconds, default 600 seconds). This method returns an <code>exec_result</code> structure containing the output of the command. The <code>environment</code> map can be used to override some environment variables to be passed to the process.
          
          """.trimIndent(), useStarlarkThread = true, parameters = [Param(
            name = "arguments", doc = """
                List of arguments, the first element should be the path to the program to execute.
                
                """.trimIndent()
        ), Param(
            name = "timeout",
            named = true,
            defaultValue = "600",
            doc = "Maximum duration of the command in seconds (default is 600 seconds)."
        ), Param(
            name = "environment", defaultValue = "{}", named = true, doc = """
                Force some environment variables to be set to be passed to the process. The value can be <code>None</code> to remove the environment variable.
                
                """.trimIndent()
        ), Param(
            name = "quiet",
            defaultValue = "True",
            named = true,
            doc = "If stdout and stderr should be printed to the terminal."
        ), Param(
            name = "working_directory", defaultValue = "\"\"", named = true, doc = """
                Working directory for command execution.
                Can be relative to the repository root or absolute.
                The default is the repository root.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun execute(
        arguments: Sequence<*>,  // <String> or <StarlarkPath> or <Label> expected
        timeoutI: StarlarkInt?,
        uncheckedEnvironment: Dict<*, *>?,  // <String, Object> expected
        quiet: Boolean,
        overrideWorkingDirectory: String?,
        thread: StarlarkThread
    ): StarlarkExecutionResult {
        validateExecuteArguments(arguments)
        val timeout = Starlark.toInt(timeoutI, "timeout")

        val forceRepoEnvVariablesRaw: MutableMap<String?, Any?> =
            Dict.cast<String?, Any?>(uncheckedEnvironment, String::class.java, Any::class.java, "environment")
        val forceRepoEnvVariables: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        val removeRepoEnvVariables: MutableSet<String?> = LinkedHashSet<String?>()
        for (entry in forceRepoEnvVariablesRaw.entrySet()) {
            val key: String? = entry.getKey()
            val value: Any? = entry.getValue()
            if (value === Starlark.NONE) {
                removeRepoEnvVariables.add(key)
            } else if (value is String) {
                forceRepoEnvVariables.put(key, value)
            } else {
                throw Starlark.errorf("environment values must be strings or None, got %s", value)
            }
        }

        if (canExecuteRemote()) {
            // Remote execution only sees the explicitly set environment variables, so removing env vars
            // isn't necessary.
            return executeRemote(
                arguments, timeout, forceRepoEnvVariables, quiet, overrideWorkingDirectory
            )
        }

        // Execute on the local/host machine
        var args: MutableList<String> = ArrayList<String>(arguments.size())
        for (arg in arguments) {
            if (arg is Label) {
                args.add(getPathFromLabel(arg).toString())
            } else {
                // String or StarlarkPath expected
                args.add(arg.toString())
            }
        }

        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newExecuteEvent(
                args,
                timeout,
                Maps.< K,
                V > filterKeys<K?, V?>(
                    repoEnv,
                    com.google.common.base.Predicate { k: K? -> !removeRepoEnvVariables.contains(k) }),
                forceRepoEnvVariables,
                workingDirectory.getPathString(),
                quiet,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        createDirectory(workingDirectory)

        val timeoutMillis = Math.round(timeout * 1000L * timeoutScaling)
        if (processWrapper != null) {
            args =
                processWrapper
                    .commandLineBuilder(args)
                    .setTimeout(Duration.ofMillis(timeoutMillis))
                    .build()
        }

        val workingDirectoryPath: Path
        if (overrideWorkingDirectory != null && !overrideWorkingDirectory.isEmpty()) {
            workingDirectoryPath = getPath(overrideWorkingDirectory).getPath()
        } else {
            workingDirectoryPath = workingDirectory
        }
        createDirectory(workingDirectoryPath)

        val fargs = args
        Profiler.instance()
            .profile(ProfilerTask.STARLARK_REPOSITORY_FN, Supplier { profileArgsDesc("local", fargs) }).use { c ->
                return StarlarkExecutionResult.Companion.builder(osObject.getEnvironmentVariables())
                    .addArguments(args)
                    .setDirectory(workingDirectoryPath.getPathFile())
                    .addEnvironmentVariables(forceRepoEnvVariables)
                    .removeEnvironmentVariables(removeRepoEnvVariables)
                    .setTimeout(timeoutMillis)
                    .setQuiet(quiet)
                    .execute()
            }
    }

    @StarlarkMethod(
        name = "load_wasm",
        doc = """
          Load a WebAssembly module from a file on the filesystem.

          <p>This method returns a <code>wasm_module</code>, which can be passed to
          <a href="#execute_wasm"><code>execute_wasm</code></a> for execution.
          
          """.trimIndent(),
        useStarlarkThread = true,
        enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_REPOSITORY_CTX_EXECUTE_WASM,
        parameters = [Param(
            name = "path",
            positional = true,
            named = true,
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class)],
            doc = "Path of the WebAssembly module to load."
        ), Param(
            name = "compile",
            defaultValue = "True",
            positional = false,
            named = true,
            enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_REPOSITORY_CTX_WASM_COMPILATION,
            doc = """
                Whether to compile the WebAssembly module, which improves runtime performance
                but takes longer than loading without compilation.
                
                """.trimIndent()
        ), Param(
            name = "allocate_fn", defaultValue = "'allocate'", positional = false, named = true, doc = """
                Name of an exported function that allocates memory in the module's address space.

                <p>The function signature must be <code>(size: u32, align: u32) -&gt; *u8</code>,
                where <code>size</code> is the size of the allocation and <code>align</code>
                is its alignment hint. The returned value must be a valid pointer within the
                module's address space, or <code>NULL</code> (<code>0x00000000</code>) to signal
                an allocation failure.

                <p>The allocation function is allowed to create an allocation that exceeds
                the requested size. The alignment hint may be ignored, and Bazel does not
                require that the returned pointer have any particular alignment.
                
                """.trimIndent()
        ), Param(
            name = "watch", defaultValue = "'auto'", positional = false, named = true, doc = """
                Whether to <a href="#watch">watch</a> the file. Can be the string 'yes', 'no',
                or 'auto'. Passing 'yes' is equivalent to immediately invoking the
                <a href="#watch"><code>watch()</code></a> method; passing 'no' does not
                attempt to watch the file; passing 'auto' will only attempt to watch the
                file when it is legal to do so (see <code>watch()</code> docs for more
                information.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun loadWasm(
        path: Any, compile: Boolean, allocateFn: String?, watch: String, thread: StarlarkThread
    ): StarlarkWasmModule {
        val p = getPath(path)

        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newLoadWasmEvent(
                p.toString(),
                compile,
                allocateFn,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)
        maybeWatch(p, ShouldWatch.Companion.fromString(watch))
        try {
            val moduleContent = FileSystemUtils.readContent(p.getPath())
            return StarlarkWasmModule(p, path, moduleContent, compile, allocateFn)
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "execute_wasm",
        doc = """
          Instantiate a WebAssembly module and execute the specified function,
          passing in the given input buffer.

          <p>The function to execute must have the following signature:
<pre><code>
func(
  input_ptr: *u8,
  input_len: u32,
  output_ptr_ptr: **u8,
  output_ptr_len: *u32,
) -&gt; u32
</code></pre>

          <p>Additionally there must be an allocation function defined, named
          <code>allocate</code> by default. See <a href="#load_wasm"><code>load_wasm</code></a>
          for details on the allocation function's type signature and semantics.

          <p>The execution time is limited by <code>timeout</code> (in seconds,
          default 600 seconds). The memory use is limited by <code>memory_limit</code>
          (in bytes, default 64 MiB).

          <p>This method returns a <code>wasm_exec_result</code> structure containing
          the function's return code (in field <code>return_code</code>) and output
          buffer (in field <code>output</code>). If execution failed before the function
          returned then the return code will be negative and the <code>error_message</code>
          field will be set.

""".trimIndent(),
        useStarlarkThread = true,
        enableOnlyWithFlag = BuildLanguageOptions.EXPERIMENTAL_REPOSITORY_CTX_EXECUTE_WASM,
        parameters = [Param(
            name = "module",
            positional = true,
            named = true,
            allowedTypes = [ParamType(type = String::class), ParamType(type = Label::class), ParamType(type = StarlarkPath::class), ParamType(
                type = StarlarkWasmModule::class
            )],
            doc = """
                Path of the WebAssembly module to execute, or a <code>wasm_module</code>
                loaded by <a href="#load_wasm"><code>load_wasm</code></a>.
                
                """.trimIndent()
        ), Param(
            name = "function",
            positional = true,
            named = true,
            doc = "The name of the function to execute"
        ), Param(
            name = "input",
            positional = false,
            named = true,
            doc = "The content of the input buffer."
        ), Param(
            name = "timeout",
            defaultValue = "600",
            positional = false,
            named = true,
            doc = "Execution timeout in seconds (default is 600 seconds)."
        ), Param(
            name = "memory_limit",
            defaultValue = "67108864",
            positional = false,
            named = true,
            doc = "Memory limit in bytes (default is 64 MiB"
        ), Param(
            name = "watch", defaultValue = "'auto'", positional = false, named = true, doc = """
                Whether to <a href="#watch">watch</a> the file. Can be the string 'yes', 'no', or 'auto'. Passing 'yes' is equivalent to immediately invoking the <a href="#watch"><code>watch()</code></a> method; passing 'no' does not attempt to watch the file; passing 'auto' will only attempt to watch the file when it is legal to do so (see <code>watch()</code> docs for more information.
                
                """.trimIndent()
        )]
    )
    @Throws(EvalException::class, RepositoryFunctionException::class, InterruptedException::class)
    fun executeWasm(
        pathOrModule: Any,
        function: String?,
        input: String?,
        timeoutI: StarlarkInt,
        memLimitI: StarlarkInt,
        watch: String,
        thread: StarlarkThread
    ): StarlarkWasmExecutionResult? {
        var path: StarlarkPath? = null
        var wasmModule: StarlarkWasmModule? = null
        when (pathOrModule) {
            -> {
                wasmModule = m
                path = m.getPath()
            }

            else -> path = getPath(pathOrModule)
        }


        val inputBytes: ByteArray? = StringUnsafe.getInternalStringBytes(input)
        val timeoutSeconds = timeoutI.toInt("timeout")
        val memLimit = memLimitI.toLong("memory_limit")

        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newExecuteWasmEvent(
                path.toString(),
                function,
                inputBytes,
                timeoutSeconds,
                memLimit,
                identifyingStringForLogging,
                thread.getCallerLocation()
            )
        env.getListener().post(w)

        val timeoutMillis = Math.round(timeoutSeconds * 1000L * timeoutScaling)
        val timeout = Duration.ofMillis(timeoutMillis)

        try {
            if (wasmModule == null) {
                maybeWatch(path!!, ShouldWatch.Companion.fromString(watch))
                val moduleContent = FileSystemUtils.readContent(path.getPath())
                val compile =
                    starlarkSemantics.getBool(
                        BuildLanguageOptions.EXPERIMENTAL_REPOSITORY_CTX_WASM_COMPILATION
                    )
                wasmModule = StarlarkWasmModule(path, pathOrModule, moduleContent, compile, "allocate")
            }
            return wasmModule.execute(function, inputBytes, timeout, memLimit)
        } catch (e: IOException) {
            throw RepositoryFunctionException(e, Transience.TRANSIENT)
        }
    }

    @StarlarkMethod(
        name = "which",
        doc = """
          Returns the <code>path</code> of the corresponding program or <code>None</code> if there is no such program in the path.
          
          """.trimIndent(),
        allowReturnNones = true,
        useStarlarkThread = true,
        parameters = [Param(name = "program", named = false, doc = "Program to find in the path.")]
    )
    @Throws(
        EvalException::class
    )
    fun which(program: String, thread: StarlarkThread): StarlarkPath? {
        var program = program
        val w: WorkspaceRuleEvent? =
            WorkspaceRuleEvent.newWhichEvent(
                program, identifyingStringForLogging, thread.getCallerLocation()
            )
        env.getListener().post(w)
        if (program.contains("/") || program.contains("\\")) {
            throw Starlark.errorf(
                "Program argument of which() may not contain a / or a \\ ('%s' given)", program
            )
        }
        if (program.length() == 0) {
            throw Starlark.errorf("Program argument of which() may not be empty")
        }
        try {
            val commandPath = findCommandOnPath(program)
            if (commandPath != null) {
                return commandPath
            }

            if (!program.endsWith(OsUtils.executableExtension())) {
                program += OsUtils.executableExtension()
                return findCommandOnPath(program)
            }
        } catch (e: IOException) {
            // IOException when checking executable file means we cannot read the file data so
            // we cannot execute it, swallow the exception.
        }
        return null
    }

    @Throws(IOException::class)
    private fun findCommandOnPath(program: String): StarlarkPath? {
        val pathEnvVariable = repoEnv.get("PATH")
        if (pathEnvVariable == null) {
            return null
        }
        for (p in Splitter.on(File.pathSeparator).split(pathEnvVariable)) {
            val fragment: PathFragment = PathFragment.create(p)
            if (fragment.isAbsolute()) {
                // We ignore relative path as they don't mean much here (relative to where? the workspace
                // root?).
                val path: Path = workingDirectory.getFileSystem().getPath(fragment).getChild(program.trim())
                if (path.exists() && path.isFile(Symlinks.FOLLOW) && path.isExecutable()) {
                    return StarlarkPath(this, path)
                }
            }
        }
        return null
    }

    // Resolve the label given by value into a file path.
    @Throws(EvalException::class, InterruptedException::class)
    protected fun getPathFromLabel(label: Label): StarlarkPath {
        val rootedPath: RootedPath? = RepositoryUtils.getRootedPathFromLabel(label, env)
        if (rootedPath == null) {
            throw NeedsSkyframeRestartException()
        }
        if (!label.getRepository().isMain()
            && directories.getOutputBase().getFileSystem()
                    is RemoteExternalOverlayFileSystem
        ) {
            try {
                remoteFs.ensureMaterialized(label.getRepository(), env.getListener())
            } catch (e: IOException) {
                throw Starlark.errorf(
                    "Failed to materialize remote repo %s: %s", label.getRepository(), e.getMessage()
                )
            }
        }
        val starlarkPath = StarlarkPath(this, rootedPath.asPath())
        try {
            maybeWatch(
                starlarkPath,
                if (starlarkSemantics.getBool(BuildLanguageOptions.INCOMPATIBLE_NO_IMPLICIT_WATCH_LABEL))
                    ShouldWatch.NO
                else
                    ShouldWatch.AUTO
            )
        } catch (e: RepositoryFunctionException) {
            throw Starlark.errorf("%s", e.getCause().getMessage())
        }
        return starlarkPath
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Max. length of command line args added as a profiler description.  */
        private const val MAX_PROFILE_ARGS_LEN = 512

        /**
         * From an authentication dict extract a map of headers.
         * 
         * 
         * Given a dict as provided as "auth" argument, compute a map specifying for each URI provided
         * which additional headers (as usual, represented as a map from Strings to Strings) should
         * additionally be added to the request. For some form of authentication, in particular basic
         * authentication, adding those headers is enough; for other forms of authentication other
         * measures might be necessary.
         */
        @Throws(EvalException::class)
        private fun getAuthHeaders(
            auth: MutableMap<String?, Dict<*, *>?>
        ): ImmutableMap<URI?, MutableMap<String?, MutableList<String?>?>?> {
            val headers = ImmutableMap.Builder<URI?, MutableMap<String?, MutableList<String?>?>?>()
            for (entry in auth.entrySet()) {
                try {
                    val url = URI(entry.getKey())
                    val authMap: Dict<*, *> = entry.getValue()
                    if (authMap.containsKey("type")) {
                        if (authMap.get("type") == "basic") {
                            if (!authMap.containsKey("login") || !authMap.containsKey("password")) {
                                throw Starlark.errorf(
                                    "Found request to do basic auth for %s without 'login' and 'password' being"
                                            + " provided.",
                                    entry.getKey()
                                )
                            }
                            val credentials = authMap.get("login").toString() + ":" + authMap.get("password")
                            headers.put(
                                url,
                                ImmutableMap.of<String?, MutableList<String?>?>(
                                    "Authorization",
                                    ImmutableList.of<String?>(
                                        "Basic "
                                                + Base64.getEncoder()
                                            .encodeToString(credentials.getBytes(StandardCharsets.UTF_8))
                                    )
                                )
                            )
                        } else if (authMap.get("type") == "pattern") {
                            if (!authMap.containsKey("pattern")) {
                                throw Starlark.errorf(
                                    "Found request to do pattern auth for %s without a pattern being provided",
                                    entry.getKey()
                                )
                            }

                            var result = authMap.get("pattern") as String

                            for (component in Arrays.asList<String?>("password", "login")) {
                                val demarcatedComponent = "<" + component + ">"

                                if (result.contains(demarcatedComponent)) {
                                    if (!authMap.containsKey(component)) {
                                        throw Starlark.errorf(
                                            "Auth pattern contains %s but it was not provided in auth dict.",
                                            demarcatedComponent
                                        )
                                    }
                                } else {
                                    // component isn't in the pattern, ignore it
                                    continue
                                }

                                result = result.replaceAll(demarcatedComponent, authMap.get(component) as String?)
                            }

                            headers.put(
                                url,
                                ImmutableMap.of<String?, MutableList<String?>?>(
                                    "Authorization",
                                    ImmutableList.of<String?>(result)
                                )
                            )
                        }
                    }
                } catch (e: URISyntaxException) {
                    throw EvalException(e)
                }
            }
            return headers.buildOrThrow()
        }

        @Throws(EvalException::class)
        private fun getAuthContents(x: Dict<*, *>?, what: String?): MutableMap<String?, Dict<*, *>?> {
            // Dict.cast returns Dict<String, raw Dict>.
            val res: MutableMap<String?, Dict<*, *>?> =
                Dict.cast<String?, Dict<*, *>?>(x, String::class.java, Dict::class.java, what) as MutableMap<*, *>
            return res
        }

        @Throws(EvalException::class)
        private fun getHeaderContents(x: Dict<*, *>?, what: String?): ImmutableMap<String?, MutableList<String?>?> {
            val headersUnchecked =
                Dict.cast<String?, Any?>(x, String::class.java, Any::class.java, what) as Dict<String?, Any?>
            val headers = ImmutableMap.Builder<String?, MutableList<String?>?>()

            for (entry in headersUnchecked.entrySet()) {
                val headerValue: ImmutableList<String?>?
                val valueUnchecked: Any? = entry.getValue()
                if (valueUnchecked is Sequence<*>) {
                    headerValue =
                        Sequence.cast<String?>(valueUnchecked, String::class.java, "header values").getImmutableList()
                } else if (valueUnchecked is String) {
                    headerValue = ImmutableList.of<String?>(valueUnchecked.toString())
                } else {
                    throw EvalException(
                        java.lang.String.format(
                            "%s argument must be a dict whose keys are string and whose values are either"
                                    + " string or sequence of string",
                            what
                        )
                    )
                }
                headers.put(entry.getKey(), headerValue)
            }
            return headers.buildOrThrow()
        }

        @Throws(EvalException::class)
        private fun checkAllUrls(urlList: Iterable<*>): ImmutableList<String?> {
            val result = ImmutableList.builder<String?>()

            for (o in urlList) {
                if (o is String) {
                    result.add(o)
                } else {
                    throw Starlark.errorf(
                        "Expected a string or sequence of strings for 'url' argument, but got '%s' item in the"
                                + " sequence",
                        Starlark.type(o)
                    )
                }
            }

            return result.build()
        }

        @Throws(RepositoryFunctionException::class, EvalException::class)
        private fun getUrls(
            urlOrList: Any?, ensureNonEmpty: Boolean, checksumGiven: Boolean
        ): ImmutableList<URI?> {
            val urlStrings: ImmutableList<String?>?
            if (urlOrList is String) {
                urlStrings = ImmutableList.of<String?>(urlOrList)
            } else {
                urlStrings = Companion.checkAllUrls((urlOrList as Iterable<*>?)!!)
            }
            if (ensureNonEmpty && urlStrings.isEmpty()) {
                throw RepositoryFunctionException(IOException("urls not set"), Transience.PERSISTENT)
            }
            val urls = ImmutableList.builder<URI?>()
            for (urlString in urlStrings) {
                val url: URI?
                try {
                    url = URI(urlString)
                } catch (e: URISyntaxException) {
                    throw RepositoryFunctionException(
                        IOException("Bad URL: " + urlString, e), Transience.PERSISTENT
                    )
                }
                if (!HttpUtils.isUrlSupportedByDownloader(url)) {
                    throw RepositoryFunctionException(
                        IOException("Unsupported protocol: " + url.getScheme()), Transience.PERSISTENT
                    )
                }
                if (!checksumGiven) {
                    if (!Ascii.equalsIgnoreCase("http", url.getScheme())) {
                        urls.add(url)
                    }
                } else {
                    urls.add(url)
                }
            }
            val urlsResult = urls.build()
            if (ensureNonEmpty && urlsResult.isEmpty()) {
                throw RepositoryFunctionException(
                    IOException(
                        "No URLs left after removing plain http URLs due to missing checksum."
                                + " Please provide either a checksum or an https download location."
                    ),
                    Transience.PERSISTENT
                )
            }
            return urlsResult
        }

        // Do not manually edit. To get a ready-to-copy-and-paste string of updated decompression formats,
        // run the test in StarlarkBaseExternalContextTest.
        @kotlin.jvm.JvmField
        val SUPPORTED_DECOMPRESSION_FORMATS: String = """
"zip", "jar", "war", "aar", "nupkg", "whl", "tar", "tar.gz", "tgz", "gz", "tar.xz", "txz", "xz", "tar.zst", "tzst", "zst", "tar.bz2", "tbz", "bz2", "ar", "deb", "7z", "tar.br" or "br"
""".trimIndent()

        /**
         * This method wraps the deleteTree method in a retry loop, to solve an issue when trying to
         * recursively clean up temporary directories during dependency downloads when they are stored on
         * filesystems where unlinking a file may not be immediately reflected in a list of its parent
         * directory. Specifically, the symptom of this problem was the entire bazel build aborting
         * because during the cleanup of a dependency download (e.g Rust crate), there was an IOException
         * because the parent directory being removed was "not empty" (yet). Please see
         * https://github.com/bazelbuild/bazel/issues/23687 and
         * https://github.com/bazelbuild/bazel/issues/20013 for further details.
         */
        @Throws(RepositoryFunctionException::class)
        private fun deleteTreeWithRetries(downloadDirectory: Path) {
            val start: Instant = Instant.now()
            val deadline: Instant = start.plus(Duration.ofSeconds(5))

            var attempts = 1
            while (true) {
                try {
                    if (downloadDirectory.exists()) {
                        downloadDirectory.deleteTree()
                    }
                    if (attempts > 1) {
                        val elapsedMillis = Duration.between(start, Instant.now()).toMillis()
                        logger.atInfo().log(
                            "Deleting %s took %d attempts over %dms.",
                            downloadDirectory.getPathString(), attempts, elapsedMillis
                        )
                    }
                    break
                } catch (e: IOException) {
                    if (Instant.now().isAfter(deadline)) {
                        throw RepositoryFunctionException(
                            IOException(
                                ("Couldn't delete temporary directory ("
                                        + downloadDirectory.getPathString()
                                        + ") after "
                                        + attempts
                                        + " attempts: "
                                        + e.getMessage()),
                                e
                            ),
                            Transience.TRANSIENT
                        )
                    }
                }
                attempts++
            }
        }

        @Throws(EvalException::class)
        private fun renamedStripPrefix(method: String?, stripPrefix: String, oldStripPrefix: String): String {
            if (oldStripPrefix.isEmpty()) {
                return stripPrefix
            }
            if (stripPrefix.isEmpty()) {
                return oldStripPrefix
            }
            throw Starlark.errorf(
                "%s() got multiple values for parameter 'strip_prefix' (via compatibility alias"
                        + " 'stripPrefix')",
                method
            )
        }

        @Throws(EvalException::class)
        private fun validateStripping(method: String?, stripPrefix: String, stripComponents: Int) {
            if (stripComponents < 0) {
                throw Starlark.errorf(
                    "%s() has an invalid argument for 'strip_components': %d. Must be non-negative.",
                    method, stripComponents
                )
            }

            if (!stripPrefix.isEmpty() && stripComponents > 0) {
                throw Starlark.errorf(
                    "%s() got multiple strip values. Only one of 'strip_prefix' or 'strip_components' can be"
                            + " set",
                    method
                )
            }
        }

        // Move to a common location like net.starlark.java.eval.Starlark?
        private fun <T> nullIfNone(`object`: Any?, type: Class<T?>): T? {
            return if (`object` !== Starlark.NONE) type.cast(`object`) else null
        }

        // Create parent directories for the given path
        @Throws(IOException::class)
        protected fun makeDirectories(path: Path) {
            val parent = path.getParentDirectory()
            if (parent != null) {
                parent.createDirectoryAndParents()
            }
        }

        @Throws(RepositoryFunctionException::class)
        protected fun createDirectory(directory: Path) {
            try {
                if (!directory.exists()) {
                    makeDirectories(directory)
                    directory.createDirectory()
                }
            } catch (e: IOException) {
                throw RepositoryFunctionException(e, Transience.TRANSIENT)
            } catch (e: InvalidPathException) {
                throw RepositoryFunctionException(
                    Starlark.errorf("Could not create %s: %s", directory, e.getMessage()),
                    Transience.PERSISTENT
                )
            }
        }

        /** Returns the command line arguments as a string for display in the profiler.  */
        private fun profileArgsDesc(method: String?, args: MutableList<String>): String {
            val b = StringBuilder()
            b.append(method).append(":")

            val sep = " "
            for (arg in args) {
                val appendLen: Int = sep.length() + arg.length()
                val remainingLen: Int = MAX_PROFILE_ARGS_LEN - b.length()

                if (appendLen <= remainingLen) {
                    b.append(sep)
                    b.append(arg)
                } else {
                    val shortenedArg: String = (sep + arg).substring(0, remainingLen)
                    b.append(shortenedArg)
                    b.append("...")
                    break
                }
            }

            return b.toString()
        }
    }
}
