// Copyright 2018 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventArtifactUploader

/** A factory for [BuildEventArtifactUploader].  */
interface BuildEventArtifactUploaderFactory {
    /**
     * Returns a new instance of a [BuildEventArtifactUploader]. The call is responsible for
     * calling [BuildEventArtifactUploader.release] on the returned instance.
     */
    @Throws(InvalidPackagePathSymlinkException::class)
    fun create(env: CommandEnvironment?): BuildEventArtifactUploader?

    /**
     * If the factory reuses a BuildEventArtifactUploader across commands, tear down that uploader now
     * to prepare for *blaze* shutdown.
     */
    fun shutdown() {}

    /**
     * Exception thrown when initializing the BuildEventArtifactUploader fails due to the package path
     * following invalid symlinks.
     */
    class InvalidPackagePathSymlinkException(e: IOException?) : IOException(e)
    companion object {
        @kotlin.jvm.JvmField
        val LOCAL_FILES_UPLOADER_FACTORY: BuildEventArtifactUploaderFactory =
            BuildEventArtifactUploaderFactory { env: CommandEnvironment? -> LocalFilesArtifactUploader() }
    }
}
