// Copyright 2021 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.ArchiveRepoSpecBuilder
import com.google.devtools.build.lib.bazel.bzlmod.ArchiveRepoSpecBuilder.RemoteFile
import com.google.devtools.build.lib.bazel.bzlmod.GitRepoSpecBuilder
import com.google.devtools.build.lib.bazel.bzlmod.LocalPathRepoSpecs
import com.google.devtools.build.lib.bazel.bzlmod.ModuleFile
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.Registry.NotFoundException
import com.google.devtools.build.lib.bazel.bzlmod.RegistryFileDownloadEvent
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec
import com.google.devtools.build.lib.bazel.bzlmod.VendorManager
import com.google.devtools.build.lib.bazel.bzlmod.YankedVersionsValue
import com.google.devtools.build.lib.bazel.repository.downloader.Checksum.MissingChecksumException
import com.google.devtools.build.lib.bazel.repository.downloader.DownloadManager
import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.gson.FieldNamingPolicy
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParseException
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URISyntaxException

/**
 * Represents a Bazel module registry that serves a list of module metadata from a static HTTP
 * server or a local file path.
 * 
 * 
 * For details, see [the docs](https://bazel.build/external/registry)
 */
class IndexRegistry(
    uri: java.net.URI,
    clientEnv: MutableMap<String?, String?>?,
    knownFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>,
    knownFileHashesMode: KnownFileHashesMode?,
    previouslySelectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>,
    vendorDir: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>,
    moduleMirrors: com.google.common.collect.ImmutableSet<java.net.URI?>
) : com.google.devtools.build.lib.bazel.bzlmod.Registry {
    /**
     * How to handle the list of file hashes known from the lockfile when downloading files from the
     * registry.
     */
    enum class KnownFileHashesMode {
        /**
         * Neither use nor update any file hashes. All registry downloads will go out to the network.
         */
        IGNORE,

        /**
         * Use file hashes from the lockfile if available and add hashes for new files to the lockfile.
         * Avoid revalidation of mutable registry information (yanked versions in metadata.json and
         * modules that previously 404'd) by using these hashes and recording absent files in the
         * lockfile.
         */
        USE_AND_UPDATE,

        /**
         * Use file hashes from the lockfile if available and add hashes for new files to the lockfile.
         * Always revalidate mutable registry information.
         */
        USE_IMMUTABLE_AND_UPDATE,

        /**
         * Require file hashes for all registry downloads. In particular, mutable registry files such as
         * metadata.json can't be downloaded in this mode.
         */
        ENFORCE
    }

    private val uri: java.net.URI
    private val clientEnv: MutableMap<String?, String?>?
    private val gson: Gson
    private val knownFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>
    private val previouslySelectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>
    private val vendorManager: VendorManager?
    private val knownFileHashesMode: KnownFileHashesMode?
    private val moduleMirrors: com.google.common.collect.ImmutableSet<java.net.URI?>

    @kotlin.concurrent.Volatile
    private var bazelRegistryJson: java.util.Optional<BazelRegistryJson?>? = null

    @kotlin.concurrent.Volatile
    private var bazelRegistryJsonEvents: com.google.devtools.build.lib.events.StoredEventHandler? = null

    init {
        this.uri = uri
        this.clientEnv = clientEnv
        this.gson =
            GsonBuilder()
                .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
                .create()
        this.knownFileHashes = knownFileHashes
        this.knownFileHashesMode = knownFileHashesMode
        this.previouslySelectedYankedVersions = previouslySelectedYankedVersions
        this.vendorManager =
            vendorDir.map<VendorManager?>(java.util.function.Function { vendorDirectory: com.google.devtools.build.lib.vfs.Path? ->
                VendorManager(vendorDirectory)
            }).orElse(null)
        this.moduleMirrors = moduleMirrors
    }

    val url: String?
        get() = uri.toString()

    private fun constructUrl(base: String, vararg segments: String): String {
        val url: java.lang.StringBuilder = java.lang.StringBuilder(base)
        for (segment in segments) {
            if (url.charAt(url.length() - 1) != '/' && !segment.startsWith("/")) {
                url.append('/')
            }
            url.append(segment)
        }
        return url.toString()
    }

    /** Grabs a file from the given URL. Throws [NotFoundException] if it doesn't exist.  */
    @Throws(IOException::class, java.lang.InterruptedException::class, NotFoundException::class)
    private fun grabFile(
        url: String?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager,
        useChecksum: Boolean
    ): ByteArray? {
        var maybeContent: java.util.Optional<ByteArray?> = java.util.Optional.empty<ByteArray?>()
        try {
            maybeContent = java.util.Optional.of<ByteArray?>(doGrabFile(downloadManager, url, useChecksum))
            return maybeContent.get()
        } finally {
            // We intentionally don't check knownFileHashesMode here: The checksums of module files are
            // always needed for the remote_module_file_integrity attributes of the http_archive backing
            // the module repo.
            if (useChecksum) {
                eventHandler.post(RegistryFileDownloadEvent.Companion.create(url, maybeContent))
            }
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, NotFoundException::class)
    private fun doGrabFile(downloadManager: DownloadManager, rawUrl: String?, useChecksum: Boolean): ByteArray? {
        val checksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?
        if (knownFileHashesMode != KnownFileHashesMode.IGNORE && useChecksum) {
            val knownChecksum: java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>? =
                knownFileHashes.get(rawUrl)
            if (knownChecksum == null) {
                if (knownFileHashesMode == KnownFileHashesMode.ENFORCE) {
                    throw MissingChecksumException(
                        java.lang.String.format(
                            ("Missing checksum for registry file %s not permitted with --lockfile_mode=error."
                                    + " Please run `bazel mod deps --lockfile_mode=update` to update your"
                                    + " lockfile."),
                            rawUrl
                        )
                    )
                }
                // This is a new file, download without providing a checksum.
                checksum =
                    java.util.Optional.empty<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>()
            } else if (knownChecksum.isEmpty()) {
                // The file didn't exist when the lockfile was created, but it may exist now.
                if (knownFileHashesMode == KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE) {
                    // Attempt to download the file again.
                    checksum =
                        java.util.Optional.empty<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>()
                } else {
                    // Guarantee reproducibility by assuming that the file still doesn't exist.
                    throw NotFoundException(
                        java.lang.String.format(
                            "%s: previously not found (as recorded in %s, refresh with"
                                    + " --lockfile_mode=refresh)",
                            rawUrl, LabelConstants.MODULE_LOCKFILE_NAME
                        )
                    )
                }
            } else {
                // The file is known, download with a checksum to potentially obtain a repository cache hit
                // and ensure that the remote file hasn't changed.
                checksum = knownChecksum
            }
        } else {
            checksum = java.util.Optional.empty<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>()
        }
        if (knownFileHashesMode == KnownFileHashesMode.ENFORCE) {
            com.google.common.base.Preconditions.checkState(
                checksum.isPresent(),
                "Cannot fetch a file without a checksum in ENFORCE mode. This is a bug in Bazel, please "
                        + "report at https://github.com/bazelbuild/bazel/issues/new/choose."
            )
        }

        val url: java.net.URI = java.net.URI.create(rawUrl)
        // Don't read the registry URL from the vendor directory in the following cases:
        // 1. vendorUtil is null, which means vendor mode is disabled.
        // 2. The checksum is not present, which means the URL is not vendored or the vendored content
        // is out-dated.
        // 3. The URL starts with "file:", which means it's a local file and isn't vendored.
        // 4. The vendor path doesn't exist, which means the URL is not vendored.
        if (vendorManager != null && checksum.isPresent()
            && (url.getScheme() != "file") && vendorManager.isUrlVendored(url)
        ) {
            try {
                return vendorManager.readRegistryUrl(url, checksum.get())
            } catch (e: IOException) {
                throw IOException(
                    java.lang.String.format(
                        "Failed to read vendored registry file %s at %s: %s. Please rerun the bazel"
                                + " vendor command.",
                        rawUrl, vendorManager.getVendorPathForUrl(url), e.getMessage()
                    ),
                    e
                )
            }
        }

        try {
            com.google.devtools.build.lib.profiler.Profiler.instance().profile(
                com.google.devtools.build.lib.profiler.ProfilerTask.BZLMOD,
                java.util.function.Supplier { "download file: " + rawUrl }).use { c ->
                return downloadManager.downloadAndReadOneUrlForBzlmod(url, clientEnv, checksum)
            }
        } catch (e: FileNotFoundException) {
            throw NotFoundException(java.lang.String.format("%s: not found", rawUrl))
        } catch (e: IOException) {
            // Include the URL in the exception message for easier debugging.
            throw IOException(
                "Failed to fetch registry file %s: %s".formatted(rawUrl, e.getMessage()), e
            )
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class, NotFoundException::class)
    override fun getModuleFile(
        key: ModuleKey,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager
    ): ModuleFile {
        val url = constructModuleFileUrl(key)
        val content = grabFile(url, eventHandler, downloadManager,  /* useChecksum= */true)
        return ModuleFile.Companion.create(content, url)
    }

    private fun constructModuleFileUrl(key: ModuleKey): String {
        return constructUrl(this.url!!, "modules", key.name, key.version.toString(), "MODULE.bazel")
    }

    /** Represents fields available in `bazel_registry.json` for the registry.  */
    private class BazelRegistryJson {
        var mirrors: Array<String?>?
        var moduleBasePath: String? = null
    }

    /** Represents the type field in `source.json` for each version of a module.  */
    private class SourceJson {
        var type: String = "archive"
    }

    /** Represents fields in `source.json` for each archive-type version of a module.  */
    private class ArchiveSourceJson {
        var url: java.net.URI? = null
        var mirrorUrls: MutableList<String?>? = null
        var integrity: String? = null
        var stripPrefix: String? = null
        var patches: MutableMap<String?, String?>? = null
        var overlay: MutableMap<String?, String?>? = null
        var patchStrip: Int = 0
        var archiveType: String? = null
    }

    /** Represents fields in `source.json` for each local_path-type version of a module.  */
    private class LocalPathSourceJson {
        var path: String? = null
    }

    /** Represents fields in `source.json` for each git_repository-type version of a module.  */
    private class GitRepoSourceJson {
        var remote: String? = null
        var commit: String? = null
        var shallowSince: String? = null
        var tag: String? = null
        var initSubmodules: Boolean = false
        var verbose: Boolean = false
        var patches: MutableMap<String?, String?>? = null
        var patchStrip: Int = 0
        var stripPrefix: String? = null
        var addPrefix: String? = null
    }

    /**
     * Grabs a JSON file from the given URL, and returns its content. Returns [Optional.empty]
     * if the file doesn't exist.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun grabJsonFile(
        url: String?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager,
        useChecksum: Boolean
    ): java.util.Optional<String?> {
        try {
            return java.util.Optional.of<String?>(
                String(
                    grabFile(url, eventHandler, downloadManager, useChecksum),
                    java.nio.charset.StandardCharsets.UTF_8
                )
            )
        } catch (e: NotFoundException) {
            return java.util.Optional.empty<String?>()
        }
    }

    /**
     * Grabs a JSON file from the given URL, and returns it as a parsed object with fields in `T`. Returns [Optional.empty] if the file doesn't exist.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun <T> grabJson(
        url: String?,
        klass: java.lang.Class<T?>,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager,
        useChecksum: Boolean
    ): java.util.Optional<T?> {
        val jsonString: java.util.Optional<String?> = grabJsonFile(url, eventHandler, downloadManager, useChecksum)
        if (jsonString.isEmpty() || jsonString.get().isBlank()) {
            return java.util.Optional.empty<T?>()
        }
        return java.util.Optional.of<T?>(parseJson<T?>(jsonString.get(), url, klass))
    }

    /** Parses the given JSON string and returns it as an object with fields in `T`.  */
    @Throws(IOException::class)
    private fun <T> parseJson(jsonString: String?, url: String?, klass: java.lang.Class<T?>): T? {
        try {
            return gson.fromJson<T?>(jsonString, klass)
        } catch (e: JsonParseException) {
            throw IOException(
                java.lang.String.format("Unable to parse json at url %s: %s", url, e.getMessage()), e
            )
        }
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun getRepoSpec(
        key: ModuleKey,
        moduleFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum>?>,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager
    ): RepoSpec? {
        val jsonUrl = getSourceJsonUrl(key)
        val jsonString: java.util.Optional<String?> =
            grabJsonFile(jsonUrl, eventHandler, downloadManager,  /* useChecksum= */true)
        if (jsonString.isEmpty()) {
            throw FileNotFoundException(
                java.lang.String.format(
                    "Module %s's %s not found in registry %s", key, SOURCE_JSON_FILENAME, this.url
                )
            )
        }
        val sourceJson: SourceJson = parseJson<SourceJson>(jsonString.get(), jsonUrl, SourceJson::class.java)!!
        when (sourceJson.type) {
            "archive" -> {
                val typedSourceJson: ArchiveSourceJson =
                    parseJson<ArchiveSourceJson>(jsonString.get(), jsonUrl, ArchiveSourceJson::class.java)!!
                val moduleFileUrl = constructModuleFileUrl(key)
                val moduleFileChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum =
                    moduleFileHashes.get(moduleFileUrl).get()
                return createArchiveRepoSpec(
                    typedSourceJson,
                    moduleFileUrl,
                    moduleFileChecksum,
                    getBazelRegistryJson(eventHandler, downloadManager),
                    key
                )
            }

            "local_path" -> {
                val typedSourceJson: LocalPathSourceJson =
                    parseJson<LocalPathSourceJson>(jsonString.get(), jsonUrl, LocalPathSourceJson::class.java)!!
                return createLocalPathRepoSpec(
                    typedSourceJson, getBazelRegistryJson(eventHandler, downloadManager), key
                )
            }

            "git_repository" -> {
                val typedSourceJson: GitRepoSourceJson =
                    parseJson<GitRepoSourceJson>(jsonString.get(), jsonUrl, GitRepoSourceJson::class.java)!!
                val moduleFileUrl = constructModuleFileUrl(key)
                val moduleFileChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum =
                    moduleFileHashes.get(moduleFileUrl).get()
                return createGitRepoSpec(typedSourceJson, moduleFileUrl, moduleFileChecksum, key)
            }

            else -> throw IOException(
                java.lang.String.format("Invalid source type \"%s\" for module %s", sourceJson.type, key)
            )
        }
    }

    private fun getSourceJsonUrl(key: ModuleKey): String {
        return constructUrl(
            this.url!!, "modules", key.name, key.version.toString(), SOURCE_JSON_FILENAME
        )
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    private fun getBazelRegistryJson(
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?, downloadManager: DownloadManager
    ): java.util.Optional<BazelRegistryJson?>? {
        if (bazelRegistryJson == null || bazelRegistryJsonEvents == null) {
            synchronized(this) {
                if (bazelRegistryJson == null || bazelRegistryJsonEvents == null) {
                    com.google.common.base.Preconditions.checkState(bazelRegistryJson == null && bazelRegistryJsonEvents == null)
                    val storedEventHandler: com.google.devtools.build.lib.events.StoredEventHandler =
                        com.google.devtools.build.lib.events.StoredEventHandler()
                    bazelRegistryJson =
                        grabJson<BazelRegistryJson?>(
                            constructUrl(this.url!!, "bazel_registry.json"),
                            BazelRegistryJson::class.java,
                            storedEventHandler,
                            downloadManager,  /* useChecksum= */
                            true
                        )
                    bazelRegistryJsonEvents = storedEventHandler
                }
            }
        }
        bazelRegistryJsonEvents.replayOn(eventHandler)
        return bazelRegistryJson
    }

    @Throws(IOException::class)
    private fun createLocalPathRepoSpec(
        sourceJson: LocalPathSourceJson, bazelRegistryJson: java.util.Optional<BazelRegistryJson?>, key: ModuleKey?
    ): RepoSpec {
        var path = sourceJson.path
        if (!PathFragment.isAbsolute(path)) {
            val moduleBase: String? = bazelRegistryJson.get().moduleBasePath
            path = moduleBase + "/" + path
            if (!PathFragment.isAbsolute(moduleBase)) {
                if (uri.getScheme() == "file") {
                    if (uri.getPath().isEmpty() || !uri.getPath().startsWith("/")) {
                        throw IOException(
                            java.lang.String.format(
                                "Provided non absolute local registry path for module %s: %s",
                                key, uri.getPath()
                            )
                        )
                    }
                    // Unix:    file:///tmp --> /tmp
                    // Windows: file:///C:/tmp --> C:/tmp
                    path = uri.getPath()
                        .substring(if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) 1 else 0) + "/" + path
                } else {
                    throw IOException(java.lang.String.format("Provided non local registry for module %s", key))
                }
            }
        }

        return LocalPathRepoSpecs.create(PathFragment.create(path).toString())
    }

    @Throws(IOException::class)
    private fun createArchiveRepoSpec(
        sourceJson: ArchiveSourceJson,
        moduleFileUrl: String,
        moduleFileChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum,
        bazelRegistryJson: java.util.Optional<BazelRegistryJson?>,
        key: ModuleKey
    ): RepoSpec? {
        val sourceUrl: java.net.URI = sourceJson.url
        if (sourceUrl == null) {
            throw IOException(java.lang.String.format("Missing source URL for module %s", key))
        }
        if (sourceJson.integrity == null || sourceJson.integrity.isBlank()) {
            throw IOException(java.lang.String.format("Missing integrity for module %s", key))
        }

        // Give precedence to mirror specified via the command-line flag.
        val allMirrors: com.google.common.collect.ImmutableSet<String?> =
            java.util.stream.Stream.concat<String?>(
                moduleMirrors.stream()
                    .map<String?>(java.util.function.Function { obj: java.net.URI? -> obj.toString() }),
                bazelRegistryJson.flatMap<Array<String?>?>(java.util.function.Function { json: BazelRegistryJson? ->
                    java.util.Optional.ofNullable<Array<String?>?>(
                        json!!.mirrors
                    )
                }).stream()
                    .flatMap<String?>(java.util.function.Function { array: Array<String?>? ->
                        java.util.Arrays.stream(
                            array
                        )
                    })
            )
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
        val urls: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        // For each mirror specified in bazel_registry.json, add a URL that's essentially the mirror
        // URL concatenated with the source URL.
        for (mirror in allMirrors) {
            try {
                val unused: java.net.URI = java.net.URI(mirror)
            } catch (e: URISyntaxException) {
                throw IOException("Malformed mirror URL specified in bazel_registry.json of " + uri, e)
            }
            val authority: String? = sourceUrl.getRawAuthority()
            val path: String? = sourceUrl.getRawPath()
            val query: String? = sourceUrl.getRawQuery()
            urls.add(
                constructUrl(mirror, if (authority != null) authority else "", if (path != null) path else "")
                        + (if (query != null) "?" + query else "")
            )
        }
        // Add the original source URL itself.
        urls.add(sourceUrl.toString())

        // Add mirror_urls from source.json as backups after the primary url.
        if (sourceJson.mirrorUrls != null) {
            urls.addAll(sourceJson.mirrorUrls)
        }

        // Build remote patches as key-value pairs of "url" => "integrity".
        val remotePatches: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.Builder<String?, String?>()
        if (sourceJson.patches != null) {
            for (entry in sourceJson.patches.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw IOException(
                        java.lang.String.format("Missing integrity for patch %s of module %s", entry.getKey(), key)
                    )
                }
                remotePatches.put(
                    constructUrl(
                        this.url!!,
                        "modules",
                        key.name,
                        key.version.toString(),
                        "patches",
                        entry.getKey()
                    ),
                    entry.getValue()
                )
            }
        }

        val overlay: com.google.common.collect.ImmutableMap.Builder<String?, RemoteFile?> =
            com.google.common.collect.ImmutableMap.builder<String?, RemoteFile?>()
        if (sourceJson.overlay != null) {
            for (entry in sourceJson.overlay.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw IOException(
                        java.lang.String.format(
                            "Missing integrity for overlay file %s of module %s", entry.getKey(), key
                        )
                    )
                }
                overlay.put(
                    entry.getKey(),
                    RemoteFile(
                        entry.getValue(),  // integrity
                        // URLs in the registry itself are not mirrored.
                        com.google.common.collect.ImmutableList.of<String?>(
                            constructUrl(
                                this.url!!,
                                "modules",
                                key.name,
                                key.version.toString(),
                                "overlay",
                                entry.getKey()
                            )
                        )
                    )
                )
            }
        }

        return ArchiveRepoSpecBuilder()
            .setUrls(urls.build())
            .setIntegrity(sourceJson.integrity)
            .setStripPrefix(com.google.common.base.Strings.nullToEmpty(sourceJson.stripPrefix))
            .setRemotePatches(remotePatches.buildKeepingLast())
            .setOverlay(overlay.buildOrThrow())
            .setRemoteModuleFile(
                RemoteFile(
                    moduleFileChecksum.toSubresourceIntegrity(),
                    com.google.common.collect.ImmutableList.of<String?>(moduleFileUrl)
                )
            )
            .setRemotePatchStrip(sourceJson.patchStrip)
            .setArchiveType(sourceJson.archiveType)
            .build()
    }

    @Throws(IOException::class)
    private fun createGitRepoSpec(
        sourceJson: GitRepoSourceJson,
        moduleFileUrl: String,
        moduleFileChecksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum,
        key: ModuleKey
    ): RepoSpec? {
        // Build remote patches as key-value pairs of "url" => "integrity".
        val remotePatches: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
            com.google.common.collect.ImmutableMap.Builder<String?, String?>()
        if (sourceJson.patches != null) {
            for (entry in sourceJson.patches.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isBlank()) {
                    throw IOException(
                        java.lang.String.format("Missing integrity for patch %s of module %s", entry.getKey(), key)
                    )
                }
                remotePatches.put(
                    constructUrl(
                        this.url!!,
                        "modules",
                        key.name,
                        key.version.toString(),
                        "patches",
                        entry.getKey()
                    ),
                    entry.getValue()
                )
            }
        }

        return GitRepoSpecBuilder()
            .setRemote(sourceJson.remote)
            .setCommit(sourceJson.commit)
            .setShallowSince(sourceJson.shallowSince)
            .setTag(sourceJson.tag)
            .setInitSubmodules(sourceJson.initSubmodules)
            .setVerbose(sourceJson.verbose)
            .setStripPrefix(sourceJson.stripPrefix)
            .setAddPrefix(sourceJson.addPrefix)
            .setRemoteModuleFile(
                RemoteFile(
                    moduleFileChecksum.toSubresourceIntegrity(),
                    com.google.common.collect.ImmutableList.of<String?>(moduleFileUrl)
                )
            )
            .setRemotePatches(remotePatches.buildKeepingLast())
            .setRemotePatchStrip(sourceJson.patchStrip)
            .build()
    }

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun getYankedVersions(
        moduleName: String?,
        eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler,
        downloadManager: DownloadManager
    ): java.util.Optional<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?> {
        val metadataJson: java.util.Optional<MetadataJson?>
        MetadataJson > grabJson<MetadataJson?>(
            constructUrl(this.url!!, "modules", moduleName!!, "metadata.json"),
            MetadataJson::class.java,
            eventHandler,
            downloadManager,  // metadata.json is not immutable
            /* useChecksum= */
            false
        )
        if (metadataJson.isEmpty()) {
            return java.util.Optional.empty<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>()
        }

        try {
            val yankedVersionsBuilder: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?> =
                com.google.common.collect.ImmutableMap.Builder<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>()
            if (metadataJson.get().yankedVersions != null) {
                for (e in metadataJson.get().yankedVersions.entrySet()) {
                    yankedVersionsBuilder.put(
                        com.google.devtools.build.lib.bazel.bzlmod.Version.Companion.parse(e.getKey()),
                        e.getValue()
                    )
                }
            }
            return java.util.Optional.of<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>(
                yankedVersionsBuilder.buildOrThrow()
            )
        } catch (e: com.google.devtools.build.lib.bazel.bzlmod.Version.ParseException) {
            throw IOException(
                java.lang.String.format(
                    "Could not parse module %s's metadata file: %s", moduleName, e.getMessage()
                )
            )
        }
    }

    override fun tryGetYankedVersionsFromLockfile(
        selectedModuleKey: ModuleKey
    ): java.util.Optional<YankedVersionsValue?> {
        if (knownFileHashesMode == KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE) {
            // Yanked version information is inherently mutable, so always refresh it when requested.
            return java.util.Optional.empty<YankedVersionsValue?>()
        }
        val yankedInfo: String? = previouslySelectedYankedVersions.get(selectedModuleKey)
        if (yankedInfo != null) {
            // The module version was selected when the lockfile was created, but known to be yanked
            // (hence, it was explicitly allowed by the user). We reuse the yanked info from the lockfile.
            // Rationale: A module that was yanked in the past should remain yanked in the future. The
            // yanked info may have been updated since then, but by not fetching it, we avoid network
            // access if the set of yanked versions has not changed, but the set allowed versions has.
            return java.util.Optional.of<YankedVersionsValue?>(
                YankedVersionsValue.Companion.create(
                    java.util.Optional.of<com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>?>(
                        com.google.common.collect.ImmutableMap.of<com.google.devtools.build.lib.bazel.bzlmod.Version?, String?>(
                            selectedModuleKey.version,
                            yankedInfo
                        )
                    )
                )
            )
        }
        if (knownFileHashes.containsKey(getSourceJsonUrl(selectedModuleKey))) {
            // If the source.json hash is recorded in the lockfile, we know that the module was selected
            // when the lockfile was created. Since it does not appear in the list of selected yanked
            // versions recorded in the lockfile, it must not have been yanked at that time. We do not
            // refresh yanked versions information.
            // Rationale: This ensures that builds with --lockfile_mode=update or error are reproducible
            // and do not fail due to changes in the set of yanked versions. Furthermore, it avoids
            // refetching yanked versions for all modules every time the user modifies or adds a
            // dependency. If the selected version for a module changes, yanked version information is
            // always refreshed.
            return java.util.Optional.of<YankedVersionsValue?>(YankedVersionsValue.Companion.NONE_YANKED)
        }
        // The lockfile does not contain sufficient information to determine the "yanked" status of the
        // module - network access to the registry is required.
        // Note that this point can't (and must not) be reached with --lockfile_mode=error: The lockfile
        // records the source.json hashes of all selected modules and the result of selection is fully
        // determined by the lockfile.
        return java.util.Optional.empty<YankedVersionsValue?>()
    }

    /** Represents fields available in `metadata.json` for each module.  */
    internal class MetadataJson {
        // There are other attributes in the metadata.json file, but for now, we only care about
        // the yanked_version attribute.
        var yankedVersions: MutableMap<String?, String?>? = null
    }

    companion object {
        private const val SOURCE_JSON_FILENAME = "source.json"
    }
}
