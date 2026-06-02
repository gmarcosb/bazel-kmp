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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.concurrent.ThreadSafety

/** Key for [PlatformMappingValue] based on the location of the mapping file.  */
@ThreadSafety.Immutable
@AutoCodec
class PlatformMappingKey private constructor(path: PathFragment, wasExplicitlySetByUser: Boolean) : SkyKey {
    private val path: PathFragment
    private val wasExplicitlySetByUser: Boolean

    init {
        this.path = path
        this.wasExplicitlySetByUser = wasExplicitlySetByUser
    }

    val workspaceRelativeMappingPath: PathFragment
        /** Returns the main-workspace relative path this mapping's mapping file can be found at.  */
        get() = path

    fun wasExplicitlySetByUser(): Boolean {
        return wasExplicitlySetByUser
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.PLATFORM_MAPPING
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is PlatformMappingKey) {
            return false
        }
        return path == o.path && wasExplicitlySetByUser == o.wasExplicitlySetByUser
    }

    override fun hashCode(): Int {
        return path.hashCode() * 31 + java.lang.Boolean.hashCode(wasExplicitlySetByUser)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("path", path)
            .add("wasExplicitlySetByUser", wasExplicitlySetByUser)
            .toString()
    }

    val skyKeyInterner: SkyKeyInterner<PlatformMappingKey?>
        get() = interner

    companion object {
        private val interner: SkyKeyInterner<PlatformMappingKey?> = SkyKey.newInterner<PlatformMappingKey?>()

        /** Default key to use when the user does not explicitly set `--platform_mappings`.  */
        @kotlin.jvm.JvmField
        val DEFAULT: PlatformMappingKey =
            create(PathFragment.create("platform_mappings"),  /* wasExplicitlySetByUser= */false)

        /**
         * Creates a platform mapping key with the given, main workspace-relative path to the mappings
         * file which was specified by the user via the `--platform_mappings` flag.
         */
        fun createExplicitlySet(workspaceRelativeMappingPath: PathFragment): PlatformMappingKey {
            return create(workspaceRelativeMappingPath,  /* wasExplicitlySetByUser= */true)
        }

        private fun create(
            workspaceRelativeMappingPath: PathFragment, wasExplicitlySetByUser: Boolean
        ): PlatformMappingKey {
            return interner.intern(
                PlatformMappingKey(workspaceRelativeMappingPath, wasExplicitlySetByUser)
            )
        }

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        @AutoCodec.Interner
        fun intern(key: PlatformMappingKey?): PlatformMappingKey {
            return interner.intern(key)
        }
    }
}
