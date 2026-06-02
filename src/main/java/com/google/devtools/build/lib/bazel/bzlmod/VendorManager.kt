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
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.repository.RepositoryUtils
import com.google.devtools.build.lib.bazel.repository.cache.LocalRepoContentsCache
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder
import java.util.Locale

/** Class to manage the vendor directory.  */
class VendorManager(vendorDirectory: com.google.devtools.build.lib.vfs.Path) {
    private val vendorDirectory: com.google.devtools.build.lib.vfs.Path

    init {
        this.vendorDirectory = vendorDirectory
    }

    /**
     * Vendors the specified repositories under the vendor directory.
     * 
     * 
     * TODO(pcloudy): Parallelize vendoring repos
     * 
     * @param externalRepoRoot The root directory of the external repositories.
     * @param workspace The workspace directory.
     * @param reposToVendor The list of repositories to vendor.
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    fun vendorRepos(
        externalRepoRoot: com.google.devtools.build.lib.vfs.Path,
        workspace: com.google.devtools.build.lib.vfs.Path?,
        reposToVendor: com.google.common.collect.ImmutableList<RepositoryName>
    ) {
        if (!vendorDirectory.exists()) {
            vendorDirectory.createDirectoryAndParents()
        }

        for (repo in reposToVendor) {
            com.google.devtools.build.lib.profiler.Profiler.instance()
                .profile(com.google.devtools.build.lib.profiler.ProfilerTask.REPOSITORY_VENDOR, repo.toString())
                .use { c ->
                    val repoUnderExternal: com.google.devtools.build.lib.vfs.Path =
                        externalRepoRoot.getChild(repo.getName())
                    val repoUnderVendor: com.google.devtools.build.lib.vfs.Path =
                        vendorDirectory.getChild(repo.getName())
                    // This could happen when running the vendor command twice without changing anything.
                    if (repoUnderExternal.isSymbolicLink()
                        && repoUnderExternal.resolveSymbolicLinks() == repoUnderVendor
                    ) {
                        continue
                    }

                    val markerUnderExternal: com.google.devtools.build.lib.vfs.Path =
                        externalRepoRoot.getChild(repo.getMarkerFileName())
                    val markerUnderVendor: com.google.devtools.build.lib.vfs.Path =
                        vendorDirectory.getChild(repo.getMarkerFileName())
                    // If the marker file doesn't exist under outputBase/external, then the repo is either local
                    // (which cannot be in this case since local repos aren't vendored) or in the repo contents
                    // cache.
                    val isCached: Boolean = !markerUnderExternal.exists()
                    val actualMarkerFile: com.google.devtools.build.lib.vfs.Path?
                    if (isCached) {
                        val cacheRepoDir: com.google.devtools.build.lib.vfs.Path =
                            repoUnderExternal.resolveSymbolicLinks()
                        actualMarkerFile =
                            cacheRepoDir.replaceName(
                                cacheRepoDir.getBaseName() + LocalRepoContentsCache.RECORDED_INPUTS_SUFFIX
                            )
                    } else {
                        actualMarkerFile = markerUnderExternal
                    }

                    // At this point, the repo should exist under external dir, but check if the vendor src is
                    // already up-to-date.
                    if (isRepoUpToDate(markerUnderVendor, actualMarkerFile)) {
                        continue
                    }

                    // Actually vendor the repo. If the repo is cached, copy it; otherwise move it.
                    // 1. Clean up existing marker file and vendor dir.
                    markerUnderVendor.delete()
                    repoUnderVendor.deleteTree()
                    repoUnderVendor.createDirectory()
                    // 2. Copy/move the marker file to a temporary one under vendor dir.
                    val temporaryMarker: com.google.devtools.build.lib.vfs.Path =
                        vendorDirectory.getChild(repo.getMarkerFileName() + ".tmp")
                    if (isCached) {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.copyFile(actualMarkerFile, temporaryMarker)
                    } else {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.moveFile(actualMarkerFile, temporaryMarker)
                    }
                    // 3. Move/copy the external repo to vendor dir. Note that, in the "move" case, it's fine if
                    // this step fails or is interrupted, because the marker file under external is gone anyway.
                    if (isCached) {
                        com.google.devtools.build.lib.vfs.FileSystemUtils.copyTreesBelow(
                            repoUnderExternal.resolveSymbolicLinks(),
                            repoUnderVendor
                        )
                    } else {
                        try {
                            repoUnderExternal.renameTo(repoUnderVendor)
                        } catch (e: IOException) {
                            com.google.devtools.build.lib.vfs.FileSystemUtils.moveTreesBelow(
                                repoUnderExternal,
                                repoUnderVendor
                            )
                        }
                    }
                    // 4. Re-plant symlinks pointing to a Bazel-managed path to a relative path to make sure the
                    // vendor src keep working after being moved (including to a different checkout of the
                    // workspace) or used with a different output base. We assume that a given vendor directory
                    // is only used with one output base at a time.
                    val externalSymlink: com.google.devtools.build.lib.vfs.Path? = vendorDirectory.getRelative(
                        EXTERNAL_ROOT_SYMLINK_NAME
                    )
                    com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                        externalSymlink,
                        externalRepoRoot
                    )
                    RepositoryUtils.replantSymlinks(
                        repoUnderVendor, workspace, externalRepoRoot, EXTERNAL_ROOT_SYMLINK_NAME
                    )
                    // 5. Rename the temporary marker file after the move/copy is done.
                    temporaryMarker.renameTo(markerUnderVendor)
                    // 6. Leave a symlink in external dir to keep things working.
                    repoUnderExternal.deleteTree()
                    com.google.devtools.build.lib.vfs.FileSystemUtils.ensureSymbolicLink(
                        repoUnderExternal,
                        repoUnderVendor
                    )
                }
        }
    }

    /**
     * Checks if the given URL is vendored.
     * 
     * @param url The URL to check.
     * @return true if the URL is vendored, false otherwise.
     * @throws UnsupportedEncodingException if the URL decoding fails.
     */
    @Throws(UnsupportedEncodingException::class)
    fun isUrlVendored(url: java.net.URI): Boolean {
        return getVendorPathForUrl(url).isFile()
    }

    /**
     * Vendors the registry URL with the specified content.
     * 
     * @param url The registry URL to vendor.
     * @param content The content to write.
     * @throws IOException if an I/O error occurs.
     */
    @Throws(IOException::class)
    fun vendorRegistryUrl(url: java.net.URI, content: ByteArray?) {
        val outputPath: com.google.devtools.build.lib.vfs.Path = getVendorPathForUrl(url)
        java.util.Objects.requireNonNull<com.google.devtools.build.lib.vfs.Path?>(outputPath.getParentDirectory())
            .createDirectoryAndParents()
        com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(outputPath, content)
    }

    /**
     * Reads the content of the registry URL and verifies its checksum.
     * 
     * @param url The registry URL to read.
     * @param checksum The checksum to verify.
     * @return The content of the registry URL.
     * @throws IOException if an I/O error occurs or the checksum verification fails.
     */
    @Throws(IOException::class)
    fun readRegistryUrl(
        url: java.net.URI,
        checksum: com.google.devtools.build.lib.bazel.repository.downloader.Checksum
    ): ByteArray {
        val content: ByteArray = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(getVendorPathForUrl(url))
        val hasher: com.google.common.hash.Hasher = checksum.getKeyType().newHasher()
        hasher.putBytes(content)
        val actual: com.google.common.hash.HashCode = hasher.hash()
        if (checksum.getHashCode() != actual) {
            throw IOException(
                java.lang.String.format(
                    "Checksum was %s but wanted %s",
                    checksum.emitOtherHashInSameFormat(actual),
                    checksum.emitOtherHashInSameFormat(checksum.getHashCode())
                )
            )
        }
        return content
    }

    /**
     * Checks if the repository under vendor dir is up-to-date by comparing its marker file with the
     * one under <output_base>/external. This function assumes the marker file under
     * <output_base>/external exists and is up-to-date.
     * 
     * @param markerUnderVendor The marker file path under vendor dir
     * @param markerUnderExternal The marker file path under external dir
     * @return true if the repository is up-to-date, false otherwise.
     * @throws IOException if an I/O error occurs.
    </output_base></output_base> */
    @Throws(IOException::class)
    private fun isRepoUpToDate(
        markerUnderVendor: com.google.devtools.build.lib.vfs.Path,
        markerUnderExternal: com.google.devtools.build.lib.vfs.Path?
    ): Boolean {
        if (!markerUnderVendor.exists()) {
            return false
        }
        val vendorMarkerContent: String = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
            markerUnderVendor,
            java.nio.charset.StandardCharsets.UTF_8
        )
        val externalMarkerContent: String = com.google.devtools.build.lib.vfs.FileSystemUtils.readContent(
            markerUnderExternal,
            java.nio.charset.StandardCharsets.UTF_8
        )
        return vendorMarkerContent == externalMarkerContent
    }

    /**
     * Returns the vendor path for the given URL.
     * 
     * 
     * The vendor path is constructed as follows: <vendor_directory>/registry_cache/<host>/<path>
     * 
     * 
     * The host name is case-insensitive, so it is converted to lowercase. The path is
     * case-sensitive, so it is left as is. The port number is not included in the vendor path.
     * 
     * 
     * Note that the vendor path may conflict if two URLs only differ by the case or port number.
     * But this is unlikely to happen in practice, and conflicts are checked in VendorCommand.java.
     * 
     * @param url The URL to get the vendor path for.
     * @return The vendor path.
     * @throws UnsupportedEncodingException if the URL decoding fails.
    </path></host></vendor_directory> */
    @Throws(UnsupportedEncodingException::class)
    fun getVendorPathForUrl(url: java.net.URI): com.google.devtools.build.lib.vfs.Path {
        val host: String = url.getHost().toLowerCase(Locale.ROOT) // Host names are case-insensitive
        var path: String = url.getPath()
        path = URLDecoder.decode(path, "UTF-8")
        if (path.startsWith("/")) {
            path = path.substring(1)
        }
        return vendorDirectory.getRelative(REGISTRIES_DIR).getRelative(host).getRelative(path)
    }

    companion object {
        private const val REGISTRIES_DIR = "_registries"

        @kotlin.jvm.JvmField
        val EXTERNAL_ROOT_SYMLINK_NAME: PathFragment? = PathFragment.create("bazel-external")
    }
}
