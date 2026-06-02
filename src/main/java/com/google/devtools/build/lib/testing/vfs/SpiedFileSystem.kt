// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testing.vfs

import com.google.devtools.build.lib.vfs.DelegateFileSystem
import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.FileSystem
import com.google.devtools.build.lib.vfs.inmemoryfs.InMemoryFileSystem
import org.mockito.Mockito

/**
 * Delegate file system with the sole purpose of creating a [Mockito.spy].
 */
class SpiedFileSystem private constructor(delegateFs: FileSystem?) : DelegateFileSystem(delegateFs) {
    companion object {
        /**
         * Create a spied file system instance delegating all calls to the provided `fileSystem`.
         */
        @kotlin.jvm.JvmStatic
        fun createSpy(fileSystem: FileSystem?): SpiedFileSystem? {
            return Mockito.spy<SpiedFileSystem?>(SpiedFileSystem(fileSystem))
        }

        @kotlin.jvm.JvmStatic
        fun createInMemorySpy(): SpiedFileSystem? {
            return createSpy(InMemoryFileSystem(DigestHashFunction.SHA256))
        }
    }
}
