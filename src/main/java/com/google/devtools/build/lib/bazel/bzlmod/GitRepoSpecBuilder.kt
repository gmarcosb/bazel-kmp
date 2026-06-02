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

import com.google.devtools.build.lib.bazel.bzlmod.ArchiveRepoSpecBuilder.RemoteFile
import com.google.devtools.build.lib.bazel.bzlmod.RepoRuleId
import com.google.devtools.build.lib.bazel.bzlmod.RepoSpec

/**
 * Builder for a [RepoSpec] object that indicates how to materialize a repo corresponding to a
 * `git_repository` repo rule call.
 */
class GitRepoSpecBuilder {
    private val attrBuilder: net.starlark.java.eval.Dict.Builder<String?, Any?> =
        net.starlark.java.eval.Dict.builder<String?, Any?>()

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemote(remoteRepoUrl: String?): GitRepoSpecBuilder {
        return setAttr("remote", remoteRepoUrl)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setCommit(gitCommitHash: String?): GitRepoSpecBuilder {
        return setAttr("commit", gitCommitHash)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setShallowSince(shallowSince: String?): GitRepoSpecBuilder {
        return setAttr("shallow_since", shallowSince)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setTag(tag: String?): GitRepoSpecBuilder {
        return setAttr("tag", tag)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setInitSubmodules(initSubmodules: Boolean): GitRepoSpecBuilder {
        setAttr("init_submodules", initSubmodules)
        setAttr("recursive_init_submodules", initSubmodules)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setVerbose(verbose: Boolean): GitRepoSpecBuilder {
        return setAttr("verbose", verbose)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setStripPrefix(stripPrefix: String?): GitRepoSpecBuilder {
        return setAttr("strip_prefix", stripPrefix)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setAddPrefix(addPrefix: String?): GitRepoSpecBuilder {
        return setAttr("add_prefix", addPrefix)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemotePatches(remotePatches: com.google.common.collect.ImmutableMap<String?, String?>?): GitRepoSpecBuilder {
        return setAttr("remote_patches", remotePatches)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemoteModuleFile(
        remoteModuleFile: RemoteFile
    ): GitRepoSpecBuilder {
        setAttr("remote_module_file_urls", remoteModuleFile.urls)
        setAttr("remote_module_file_integrity", remoteModuleFile.integrity)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRemotePatchStrip(remotePatchStrip: Int): GitRepoSpecBuilder {
        return setAttr("remote_patch_strip", remotePatchStrip)
    }

    fun build(): RepoSpec {
        return RepoSpec(
            GIT_REPOSITORY,
            com.google.devtools.build.lib.bazel.bzlmod.AttributeValues.Companion.create(attrBuilder.buildImmutable())
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setAttr(name: String?, value: String?): GitRepoSpecBuilder {
        if (value != null && !value.isEmpty()) {
            attrBuilder.put(name, value)
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setAttr(name: String?, value: Boolean): GitRepoSpecBuilder {
        attrBuilder.put(name, value)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setAttr(name: String?, value: MutableList<*>?): GitRepoSpecBuilder {
        if (value != null && !value.isEmpty()) {
            attrBuilder.put(name, net.starlark.java.eval.StarlarkList.immutableCopyOf(value))
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setAttr(name: String?, value: com.google.common.collect.ImmutableMap<*, *>?): GitRepoSpecBuilder {
        if (value != null && !value.isEmpty()) {
            attrBuilder.put(name, net.starlark.java.eval.Dict.immutableCopyOf(value))
        }
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    private fun setAttr(name: String?, value: Int): GitRepoSpecBuilder {
        attrBuilder.put(name, net.starlark.java.eval.StarlarkInt.of(value))
        return this
    }

    companion object {
        val GIT_REPOSITORY: RepoRuleId = RepoRuleId(
            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked("@@bazel_tools//tools/build_defs/repo:git.bzl"),
            "git_repository"
        )
    }
}
