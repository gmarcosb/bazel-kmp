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

import com.google.devtools.build.lib.vfs.DigestHashFunction
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.lib.vfs.SymlinkTargetType
import java.io.IOException
import java.nio.channels.SeekableByteChannel

/** Functionally like a read-only [FileSystem].  */
abstract class ReadonlyFileSystem(hashFunction: DigestHashFunction?) :
    com.google.devtools.build.lib.vfs.FileSystem(hashFunction) {
    protected fun modificationException(): IOException {
        val longname: String = this.getClass().getName()
        val shortname: String = longname.substring(longname.lastIndexOf('.'.code) + 1)
        return IOException(
            shortname + " does not support mutating operations"
        )
    }

    @Throws(IOException::class)
    override fun getOutputStream(path: PathFragment?, append: Boolean, internal: Boolean): java.io.OutputStream? {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun createReadWriteByteChannel(path: PathFragment?): SeekableByteChannel? {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun setReadable(path: PathFragment?, readable: Boolean) {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun setWritable(path: PathFragment?, writable: Boolean) {
        throw modificationException()
    }

    override fun setExecutable(path: PathFragment?, executable: Boolean) {
        throw java.lang.UnsupportedOperationException("setExecutable")
    }

    override fun supportsModifications(path: PathFragment?): Boolean {
        return false
    }

    override fun supportsSymbolicLinksNatively(path: PathFragment?): Boolean {
        return false
    }

    override fun supportsHardLinksNatively(path: PathFragment?): Boolean {
        return false
    }

    @Throws(IOException::class)
    override fun createDirectory(path: PathFragment?): Boolean {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun createDirectoryAndParents(path: PathFragment?) {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun createSymbolicLink(
        linkPath: PathFragment?, targetFragment: PathFragment?, type: SymlinkTargetType?
    ) {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun createFSDependentHardLink(linkPath: PathFragment?, originalPath: PathFragment?) {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun renameTo(sourcePath: PathFragment?, targetPath: PathFragment?) {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun delete(path: PathFragment?): Boolean {
        throw modificationException()
    }

    @Throws(IOException::class)
    override fun setLastModifiedTime(path: PathFragment?, newTime: Long) {
        throw modificationException()
    }
}
