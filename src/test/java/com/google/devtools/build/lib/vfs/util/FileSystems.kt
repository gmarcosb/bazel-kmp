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
package com.google.devtools.build.lib.vfs.util

import com.google.devtools.build.lib.unix.NativePosixFilesServiceImpl

/** Convenience factory methods.  */
object FileSystems {
    /** Constructs a platform native (Unix or Windows) file system.  */
    fun getNativeFileSystem(digestHashFunction: DigestHashFunction?): FileSystem {
        if (OS.getCurrent() === OS.WINDOWS) {
            return WindowsFileSystem(digestHashFunction,  /* createSymbolicLinks= */true)
        } else {
            return UnixFileSystem(
                digestHashFunction,  /* hashAttributeName= */"", NativePosixFilesServiceImpl()
            )
        }
    }

    val nativeFileSystem: FileSystem
        /** Constructs a platform native (Unix or Windows) file system with SHA256 digests.  */
        get() = com.google.devtools.build.lib.vfs.util.FileSystems.getNativeFileSystem(DigestHashFunction.SHA256)

    val javaIoFileSystem: FileSystem
        /** Constructs a java.io.File file system.  */
        get() = JavaIoFileSystem(DigestHashFunction.SHA256)
}
