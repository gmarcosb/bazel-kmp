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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.util.io.FileOutErr

/**
 * An implementation of the FileOutErr that uses an in-memory file behind the scenes.
 */
class TestFileOutErr : FileOutErr {
    constructor() : super(
        FlushingFileRecordingOutputStream(newInMemoryFile("out.log")),
        FlushingFileRecordingOutputStream(newInMemoryFile("err.log"))
    )

    constructor(root: Path) : super(
        FlushingFileRecordingOutputStream(root.getChild("out.log")),
        FlushingFileRecordingOutputStream(root.getChild("err.log"))
    )

    private class FlushingFileRecordingOutputStream(outputFile: Path?) : FileRecordingOutputStream(outputFile) {
        @kotlin.jvm.Synchronized
        @Throws(IOException::class)
        public override fun write(b: ByteArray?) {
            super.write(b)
            flush()
        }

        @kotlin.jvm.Synchronized
        public override fun write(b: ByteArray?, off: Int, len: Int) {
            super.write(b, off, len)
            try {
                flush()
            } catch (e: IOException) {
                recordError(e)
            }
        }

        @kotlin.jvm.Synchronized
        public override fun write(b: Int) {
            super.write(b)
            try {
                flush()
            } catch (e: IOException) {
                recordError(e)
            }
        }
    }

    val recordedOutput: String
        get() = outAsLatin1() + errAsLatin1()

    companion object {
        private fun newInMemoryFile(root: java.io.File, name: String?): Path {
            val inMemFS: InMemoryFileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
            val directory: Path = inMemFS.getPath(root.getPath())
            try {
                directory.createDirectoryAndParents()
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(e)
            }
            return directory.getRelative(name)
        }

        private fun newInMemoryFile(name: String?): Path {
            return newInMemoryFile(java.io.File("/inmem/file_outerr"), name)
        }
    }
}
