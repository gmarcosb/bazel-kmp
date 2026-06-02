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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.skyframe.SkyValue

/**
 * Contains information about the recursive digest of a directory tree, including all transitive
 * descendant files and their contents.
 */
@kotlin.jvm.JvmRecord
data class DirectoryTreeDigestValue(@kotlin.jvm.JvmField val hexDigest: String?) : SkyValue {
    /**
     * Key type for [DirectoryTreeDigestValue].
     * 
     * 
     * The `rootedPath` indicates the directory tree to compute a digest for.
     * 
     * 
     * To filter out files/directories, you can optionally provide a `globBase` and glob
     * `excludes` patterns. They are joined together to create the paths to be filtered out. For
     * example, if the given parameters are:
     * 
     * <pre>
     * rootedPath = "/tmp"
     * globBase = "/tmp/path"
     * excludes = [".git/ **", "cache/ignoreMe"]
    </pre> * 
     * 
     * Then the glob patterns that will be filtered/excluded from under `rootedPath` would
     * be:
     * 
     * <pre>
     * /tmp/path/.git/ **
     * /tmp/path/cache/ignoreMe
    </pre> * 
     */
    @AutoCodec
    internal class Key(
        rootedPath: RootedPath?,
        globBase: RootedPath?,
        excludes: com.google.common.collect.ImmutableList<String?>?
    ) : SkyKey {
        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key.Companion.interner

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.DIRECTORY_TREE_DIGEST
        }

        val rootedPath: RootedPath?
        val globBase: RootedPath?
        val excludes: com.google.common.collect.ImmutableList<String?>?

        init {
            this.excludes = excludes
            this.globBase = globBase
            this.rootedPath = rootedPath
            java.util.Objects.requireNonNull<RootedPath?>(rootedPath, "rootedPath")
            java.util.Objects.requireNonNull<RootedPath?>(globBase, "globBase")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<String?>?>(excludes, "excludes")
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            fun of(
                rootedPath: RootedPath?,
                globBase: RootedPath?,
                excludes: com.google.common.collect.ImmutableList<String?>?
            ): Key {
                return com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key.Companion.create(
                    rootedPath,
                    globBase,
                    excludes
                )
            }

            @AutoCodec.Instantiator
            fun create(
                rootedPath: RootedPath?,
                globBase: RootedPath?,
                excludes: com.google.common.collect.ImmutableList<String?>?
            ): Key {
                return com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key(
                        rootedPath,
                        globBase,
                        excludes
                    )
                )
            }
        }
    }

    init {
        java.util.Objects.requireNonNull<String?>(hexDigest, "hexDigest")
    }

    companion object {
        fun of(hexDigest: String?): DirectoryTreeDigestValue {
            return DirectoryTreeDigestValue(hexDigest)
        }

        fun key(
            rootedPath: RootedPath?, globBase: RootedPath?, excludes: com.google.common.collect.ImmutableList<String?>?
        ): Key {
            return com.google.devtools.build.lib.skyframe.DirectoryTreeDigestValue.Key.Companion.of(
                rootedPath,
                globBase,
                excludes
            )
        }
    }
}
