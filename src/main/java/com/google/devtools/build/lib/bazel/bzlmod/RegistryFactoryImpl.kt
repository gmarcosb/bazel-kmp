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

import com.google.devtools.build.lib.bazel.bzlmod.IndexRegistry
import com.google.devtools.build.lib.bazel.bzlmod.IndexRegistry.KnownFileHashesMode
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.RegistryFactory
import com.google.devtools.build.lib.bazel.repository.RepositoryOptions.LockfileMode
import java.net.URISyntaxException

/** Prod implementation of [RegistryFactory].  */
class RegistryFactoryImpl(nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>) :
    RegistryFactory {
    private val nonstrictRepoEnvSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableMap<String?, String?>?>

    init {
        this.nonstrictRepoEnvSupplier = nonstrictRepoEnvSupplier
    }

    @Throws(URISyntaxException::class)
    override fun createRegistry(
        url: String?,
        lockfileMode: LockfileMode?,
        knownFileHashes: com.google.common.collect.ImmutableMap<String?, java.util.Optional<com.google.devtools.build.lib.bazel.repository.downloader.Checksum?>?>?,
        previouslySelectedYankedVersions: com.google.common.collect.ImmutableMap<ModuleKey?, String?>?,
        vendorDir: java.util.Optional<com.google.devtools.build.lib.vfs.Path?>,
        moduleMirrors: com.google.common.collect.ImmutableSet<String?>
    ): com.google.devtools.build.lib.bazel.bzlmod.Registry {
        val uri: java.net.URI = java.net.URI(url)
        if (uri.getScheme() == null) {
            throw URISyntaxException(
                uri.toString(),
                "Registry URL has no scheme -- supported schemes are: "
                        + "http://, https:// and file://"
            )
        }
        if (uri.getPath() == null) {
            throw URISyntaxException(
                uri.toString(),
                "Registry URL path is not valid -- did you mean to use file:///foo/bar "
                        + "or file:///c:/foo/bar for Windows?"
            )
        }
        val knownFileHashesMode: KnownFileHashesMode =
            when (uri.getScheme()) {
                "http", "https" -> when (lockfileMode) {
                    LockfileMode.ERROR -> KnownFileHashesMode.ENFORCE
                    LockfileMode.REFRESH -> KnownFileHashesMode.USE_IMMUTABLE_AND_UPDATE
                    LockfileMode.OFF, LockfileMode.UPDATE -> KnownFileHashesMode.USE_AND_UPDATE
                }

                "file" -> KnownFileHashesMode.IGNORE
                else -> throw URISyntaxException(uri.toString(), "Unrecognized registry URL protocol")
            }
        val moduleMirrorUris: com.google.common.collect.ImmutableSet.Builder<java.net.URI?> =
            com.google.common.collect.ImmutableSet.builderWithExpectedSize<java.net.URI?>(moduleMirrors.size())
        for (moduleMirror in moduleMirrors) {
            moduleMirrorUris.add(java.net.URI(moduleMirror))
        }
        return IndexRegistry(
            uri,
            nonstrictRepoEnvSupplier.get(),
            knownFileHashes,
            knownFileHashesMode,
            previouslySelectedYankedVersions,
            vendorDir,
            moduleMirrorUris.build()
        )
    }
}
