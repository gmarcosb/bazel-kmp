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
package com.google.devtools.build.lib.vfs

import com.google.devtools.build.lib.vfs.FileStatusWithDigest
import com.google.devtools.build.lib.vfs.PathFragment
import java.io.IOException

/**
 * An interface for doing a batch of stat() calls.
 */
interface BatchStat {
    /**
     * Calls stat() on a set of paths.
     * 
     * @param paths The input paths to stat(), relative to the exec root. Symlinks are not followed.
     * @return A list of [FileStatusWithDigest] in the same order as the input. If a path does
     * not exist, `null` is returned in the corresponding position.
     * @throws IOException on I/O errors.
     * @throws InterruptedException on interrupt.
     */
    @Throws(IOException::class, java.lang.InterruptedException::class)
    fun batchStat(paths: Iterable<PathFragment?>?): MutableList<FileStatusWithDigest?>?
}
