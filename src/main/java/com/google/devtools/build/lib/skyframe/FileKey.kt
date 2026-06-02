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

import com.google.devtools.build.lib.skyframe.FileOpNodeOrFuture.FileOpNode
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.autocodec.AutoCodec
import com.google.devtools.build.lib.vfs.RootedPath
import com.google.devtools.build.skyframe.AbstractSkyKey
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner

/** Key for [FileFunction].  */
@AutoCodec
class FileKey private constructor(arg: RootedPath?) : AbstractSkyKey<RootedPath?>(arg), FileOpNode {
    override fun functionName(): SkyFunctionName {
        return SkyFunctions.FILE
    }

    val skyKeyInterner: SkyKeyInterner<FileKey?>
        get() = com.google.devtools.build.lib.skyframe.FileKey.Companion.interner

    companion object {
        private val interner: SkyKeyInterner<FileKey?> = SkyKey.newInterner<FileKey?>()

        fun create(arg: RootedPath?): FileKey {
            return com.google.devtools.build.lib.skyframe.FileKey.Companion.interner.intern(
                com.google.devtools.build.lib.skyframe.FileKey(
                    arg
                )
            )
        }

        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        @AutoCodec.Interner
        fun intern(key: FileKey?): FileKey {
            return com.google.devtools.build.lib.skyframe.FileKey.Companion.interner.intern(key)
        }
    }
}
