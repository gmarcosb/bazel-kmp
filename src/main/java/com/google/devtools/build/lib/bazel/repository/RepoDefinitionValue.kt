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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.repository.RepoDefinition
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.NotComparableSkyValue
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner

/**
 * The result of [RepoDefinitionFunction], holding a repository rule instance.
 * 
 * 
 * This has to be a [NotComparableSkyValue] for a very subtle reason. Two [ ]s can compare equal if, for example, the .bzl file containing the repo rule
 * hasn't changed across a Bazel invocation, and the attributes stay the same. However, this doesn't
 * mean that the two definitions are actually equivalent, because certain information the repo rule
 * has access to (notably, the repo mapping applicable to the .bzl file) is *not* encoded in the
 * [RepoRule] object. In the particular case of the repo mapping (usable by the Starlark
 * `Label()` function), the repo rule's impl function essentially closes over it, but the
 * [net.starlark.java.eval.StarlarkCallable] object stored in [RepoRule] does *not*
 * compare unequal if only its containing .bzl's repo mapping is different.
 * 
 * 
 * Certainly, we can fix this by somehow making [RepoRule] store all the information it
 * could technically close over, and use that to influence its `equals` method; but we can't
 * easily guarantee the exhaustiveness of this (it's just very subtle). Instead, we declare [ ] to be a [NotComparableSkyValue], which inherits the condition that we
 * used to have when repo definitions were stored as `Rule`s in `Package`s; and no
 * `Package`s compare equal, ever.
 * 
 * 
 * This means that we're relying on the repo marker files to be the ultimate "change pruners" of
 * this SkyValue.
 */
interface RepoDefinitionValue : NotComparableSkyValue {
    /** No repo found with the given name.  */
    @AutoCodec
    class NotFound : RepoDefinitionValue

    /** Symlink to target directory.  */
    @AutoCodec
    class RepoOverride(repoPath: PathFragment?) : RepoDefinitionValue {
        val repoPath: PathFragment?

        init {
            this.repoPath = repoPath
        }
    }

    /** A repo with the given name is found.  */
    @AutoCodec
    class Found(repoDefinition: RepoDefinition?) : RepoDefinitionValue {
        val repoDefinition: RepoDefinition?

        init {
            this.repoDefinition = repoDefinition
        }
    }

    /** Key type for [RepoDefinitionValue].  */
    @AutoCodec
    class Key private constructor(arg: RepositoryName?) : AbstractSkyKey<RepositoryName?>(arg) {
        override fun functionName(): SkyFunctionName {
            return REPO_DEFINITION
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun create(arg: RepositoryName?): Key? {
                return com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Key(arg)
                )
            }
        }
    }

    companion object {
        fun key(repositoryName: RepositoryName?): Key? {
            return com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue.Key.Companion.create(
                repositoryName
            )
        }

        @kotlin.jvm.JvmField
        val REPO_DEFINITION: SkyFunctionName = SkyFunctionName.createHermetic("REPO_DEFINITION")

        @kotlin.jvm.JvmField
        val NOT_FOUND: RepoDefinitionValue = NotFound()
    }
}
