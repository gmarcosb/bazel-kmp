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

import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec

/**
 * Builder for a [RepoSpec] object that indicates how to materialize a repo corresponding to
 * an `http_archive` repo rule call.
 */
class ArchiveRepoSpecBuilder {
    private val attrBuilder: net.starlark.java.eval.Dict.Builder<String?, Any?> =
        net.starlark.java.eval.Dict.builder<String?, Any?>()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setUrls(urls: com.google.common.collect.ImmutableList<String?>?): ArchiveRepoSpecBuilder {
        attrBuilder.put("urls", net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(urls))
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setIntegrity(integrity: String?): ArchiveRepoSpecBuilder {
        attrBuilder.put("integrity", integrity)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setStripPrefix(stripPrefix: String?): ArchiveRepoSpecBuilder {
        attrBuilder.put("strip_prefix", stripPrefix)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setPatches(patches: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>?): ArchiveRepoSpecBuilder {
        attrBuilder.put(
            "patches",
            net.starlark.java.eval.StarlarkList.immutableCopyOf<com.google.devtools.build.lib.cmdline.Label?>(patches)
        )
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemotePatches(remotePatches: com.google.common.collect.ImmutableMap<String?, String?>?): ArchiveRepoSpecBuilder {
        attrBuilder.put("remote_patches", net.starlark.java.eval.Dict.immutableCopyOf<String?, String?>(remotePatches))
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setOverlay(overlay: com.google.common.collect.ImmutableMap<String?, RemoteFile?>): ArchiveRepoSpecBuilder {
        val remoteFiles: MutableMap<String?, net.starlark.java.eval.StarlarkList<String?>?> =
            com.google.common.collect.Maps.transformValues<String?, RemoteFile?, net.starlark.java.eval.StarlarkList<String?>?>(
                overlay,
                com.google.common.base.Function { rf: RemoteFile? ->
                    net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(rf!!.urls)
                })
        val remoteFilesIntegrity: MutableMap<String?, String?> =
            com.google.common.collect.Maps.transformValues<String?, RemoteFile?, String?>(
                overlay,
                RemoteFile::integrity
            )
        attrBuilder.put(
            "remote_file_urls",
            net.starlark.java.eval.Dict.immutableCopyOf<String?, net.starlark.java.eval.StarlarkList<String?>?>(
                remoteFiles
            )
        )
        attrBuilder.put(
            "remote_file_integrity",
            net.starlark.java.eval.Dict.immutableCopyOf<String?, String?>(remoteFilesIntegrity)
        )
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemoteModuleFile(remoteModuleFile: RemoteFile): ArchiveRepoSpecBuilder {
        attrBuilder.put(
            "remote_module_file_urls",
            net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(remoteModuleFile.urls)
        )
        attrBuilder.put("remote_module_file_integrity", remoteModuleFile.integrity)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemotePatchStrip(remotePatchStrip: Int): ArchiveRepoSpecBuilder {
        attrBuilder.put("remote_patch_strip", net.starlark.java.eval.StarlarkInt.of(remotePatchStrip))
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setArchiveType(archiveType: String?): ArchiveRepoSpecBuilder {
        if (!com.google.common.base.Strings.isNullOrEmpty(archiveType)) {
            attrBuilder.put("type", archiveType)
        }
        return this
    }

    fun build(): RepoSpec {
        return RepoSpec(
            HTTP_ARCHIVE,
            com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(attrBuilder.buildImmutable())
        )
    }

    /**
     * A simple pojo to track remote files that are offered at multiple urls (mirrors) with a single
     * integrity. We split up the file here to simplify the dependency.
     */
    @kotlin.jvm.JvmRecord
    data class RemoteFile(val integrity: String?, val urls: MutableList<String?>?)
    companion object {
        val HTTP_ARCHIVE: RepoRuleId = RepoRuleId(
            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@@bazel_tools//tools/build_defs/repo:http.bzl"),
            "http_archive"
        )
    }
}
