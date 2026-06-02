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

import com.google.devtools.build.lib.actions.ChangedFilesMessage

/**
 * A package-private class intended to track a small number of modified files during the build. This
 * class should stop recording changed files if there are too many of them, instead of holding onto
 * a large collection of files.
 */
@ThreadCompatible
internal class SkyframeIncrementalBuildMonitor {
    private var files: MutableSet<PathFragment?>? = HashSet<PathFragment?>()
    private var fileCount = 0
    private val invalidatedFileValueCount: java.util.concurrent.atomic.LongAdder =
        java.util.concurrent.atomic.LongAdder()

    fun accrue(invalidatedValues: Iterable<SkyKey>) {
        for (skyKey in invalidatedValues) {
            if (skyKey.functionName() == FileStateKey.FILE_STATE) {
                val file: RootedPath = skyKey.argument() as RootedPath
                maybeAddFile(file.getRootRelativePath())
            }
        }
    }

    private fun maybeAddFile(path: PathFragment?) {
        if (files != null) {
            files!!.add(path)
            if (files.size() >= MAX_FILES) {
                files = null
            }
        }

        fileCount++
    }

    @ThreadSafety.ThreadSafe
    fun reportInvalidatedFileValue() {
        invalidatedFileValueCount.increment()
    }

    fun alertListeners(eventBus: com.google.common.eventbus.EventBus) {
        val changedFiles: MutableSet<PathFragment?> =
            if (files != null) files else com.google.common.collect.ImmutableSet.of<PathFragment?>()
        eventBus.post(
            ChangedFilesMessage.create(changedFiles, fileCount, invalidatedFileValueCount.intValue())
        )
    }

    companion object {
        private const val MAX_FILES = 100
    }
}
