// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile
import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType
import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader
import com.google.devtools.build.lib.buildeventstream.LocalFilesArtifactUploader
import com.google.devtools.build.lib.buildeventstream.PathConverter.FileUriPathConverter

/** An uploader that simply turns paths into local file URIs.  */
class LocalFilesArtifactUploader : io.netty.util.AbstractReferenceCounted(), BuildEventArtifactUploader {
    override fun upload(files: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>): com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.buildeventstream.PathConverter?> {
        return com.google.common.util.concurrent.Futures.immediateFuture<com.google.devtools.build.lib.buildeventstream.PathConverter?>(
            com.google.devtools.build.lib.buildeventstream.LocalFilesArtifactUploader.PathConverterImpl(files)
        )
    }

    override fun deallocate() {
        // Intentionally left empty
    }

    override fun touch(o: Any?): io.netty.util.ReferenceCounted {
        return this
    }

    override fun mayBeSlow(): Boolean {
        return false
    }

    private inner class PathConverterImpl(paths: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>) :
        com.google.devtools.build.lib.buildeventstream.PathConverter {
        private val paths: MutableMap<com.google.devtools.build.lib.vfs.Path?, LocalFile?>

        init {
            this.paths = paths
        }

        override fun apply(path: com.google.devtools.build.lib.vfs.Path?): String? {
            val localFile: LocalFile? = paths.get(path)
            if (localFile == null) {
                // We should throw here, the file wasn't declared in BuildEvent#referencedLocalFiles
                return null
            }
            val type: LocalFileType = localFile.type
            if (type == LocalFileType.OUTPUT_DIRECTORY
                || type == LocalFileType.OUTPUT_SYMLINK
            ) {
                return null
            }
            return FILE_URI_PATH_CONVERTER.apply(path)
        }
    }

    companion object {
        private val FILE_URI_PATH_CONVERTER: FileUriPathConverter = FileUriPathConverter()
    }
}
