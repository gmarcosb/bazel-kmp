// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.io

import com.google.devtools.build.lib.io.AbstractFileChainUniquenessFunction
import com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.skyframe.AbstractSkyKey.WithCachedHashCode
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner

/**
 * A [com.google.devtools.build.skyframe.SkyFunction] that has the side effect of reporting a
 * file symlink expansion error exactly once. This is achieved by forcing the same value key for two
 * logically equivalent expansion errors (e.g. ['a' -> 'b' -> 'c' -> 'a/nope'] and ['b' -> 'c' ->
 * 'a' -> 'a/nope']), and letting Skyframe do its magic.
 */
class FileSymlinkInfiniteExpansionUniquenessFunction

    : AbstractFileChainUniquenessFunction() {
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: com.google.common.collect.ImmutableList<RootedPath?>?) :
        WithCachedHashCode<com.google.common.collect.ImmutableList<RootedPath?>?>(arg) {
        override fun functionName(): SkyFunctionName {
            return NAME
        }

        override fun getSkyKeyInterner(): SkyKeyInterner<Key?> {
            return com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction.Key.Companion.interner
        }

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun create(arg: com.google.common.collect.ImmutableList<RootedPath?>?): Key {
                return com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction.Key(arg)
                )
            }
        }
    }

    override fun elementToString(elt: RootedPath): String? {
        return elt.asPath().toString()
    }

    override fun getConciseDescription(): String {
        return "infinite symlink expansion"
    }

    override fun getHeaderMessage(): String {
        return "[start of symlink chain]"
    }

    override fun getFooterMessage(): String {
        return "[end of symlink chain]"
    }

    companion object {
        @kotlin.jvm.JvmField
        val NAME: SkyFunctionName = SkyFunctionName.createHermetic("FILE_SYMLINK_INFINITE_EXPANSION_UNIQUENESS")

        fun key(cycle: com.google.common.collect.ImmutableList<RootedPath?>): SkyKey {
            return com.google.devtools.build.lib.io.FileSymlinkInfiniteExpansionUniquenessFunction.Key.Companion.create(
                AbstractFileChainUniquenessFunction.Companion.canonicalize(cycle)
            )
        }
    }
}

