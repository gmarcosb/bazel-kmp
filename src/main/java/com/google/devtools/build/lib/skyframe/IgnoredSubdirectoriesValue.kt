// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/** An immutable set of package name prefixes that should be ignored.  */
class IgnoredSubdirectoriesValue private constructor(ignoredSubdirectories: IgnoredSubdirectories) : SkyValue {
    private val ignoredSubdirectories: IgnoredSubdirectories

    init {
        this.ignoredSubdirectories = ignoredSubdirectories
    }

    fun asIgnoredSubdirectories(): IgnoredSubdirectories {
        return ignoredSubdirectories
    }

    override fun hashCode(): Int {
        return ignoredSubdirectories.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj is IgnoredSubdirectoriesValue) {
            return this.ignoredSubdirectories.equals(obj.ignoredSubdirectories)
        }
        return false
    }

    override fun toString(): String {
        return ignoredSubdirectories.toString()
    }

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: RepositoryName?) : AbstractSkyKey<RepositoryName?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.IGNORED_SUBDIRECTORIES
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            fun create(arg: RepositoryName?): Key {
                return com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    /** Exception thrown when an ignore path is wrong for some reason.  */
    class InvalidIgnorePathException(path: String?, message: String?) :
        java.lang.Exception("Invalid path in " + path + ": " + message)

    companion object {
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY: IgnoredSubdirectoriesValue = IgnoredSubdirectoriesValue(IgnoredSubdirectories.EMPTY)

        fun of(
            prefixes: com.google.common.collect.ImmutableSet<PathFragment?>,
            patterns: com.google.common.collect.ImmutableList<String?>
        ): IgnoredSubdirectoriesValue? {
            return if (prefixes.isEmpty() && patterns.isEmpty())
                EMPTY
            else
                IgnoredSubdirectoriesValue(IgnoredSubdirectories.of(prefixes, patterns))
        }

        fun of(ignoredSubdirectories: IgnoredSubdirectories): IgnoredSubdirectoriesValue? {
            return if (ignoredSubdirectories.isEmpty())
                EMPTY
            else
                IgnoredSubdirectoriesValue(ignoredSubdirectories)
        }

        /** Creates a key from the main repository.  */
        fun key(): SkyKey {
            return com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key.Companion.create(RepositoryName.MAIN)
        }

        /** Creates a key from the given repository name.  */
        @kotlin.jvm.JvmStatic
        fun key(repository: RepositoryName?): SkyKey {
            return com.google.devtools.build.lib.skyframe.IgnoredSubdirectoriesValue.Key.Companion.create(repository)
        }
    }
}
