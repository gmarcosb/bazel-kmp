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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEvent.LocalFile.LocalFileType

/**
 * Used when instrumentation output should be treated as a build event artifact so that it will be
 * uploaded to a specified location.
 */
class BuildEventArtifactInstrumentationOutput(name: String?, buildEventArtifactUploader: BuildEventArtifactUploader?) :
    InstrumentationOutput {
    private val name: String
    private val buildEventArtifactUploader: BuildEventArtifactUploader
    private var uploadContext: UploadContext? = null

    init {
        this.name = com.google.common.base.Preconditions.checkNotNull<String>(name)
        this.buildEventArtifactUploader =
            com.google.common.base.Preconditions.checkNotNull<BuildEventArtifactUploader>(buildEventArtifactUploader)
    }

    override fun publish(buildToolLogCollection: BuildToolLogCollection) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            uploadContext,
            "Cannot publish to buildToolLogCollection if upload never starts."
        )
        buildToolLogCollection.addUriFuture(name, uploadContext.uriFuture())
    }

    override fun createOutputStream(): java.io.OutputStream {
        uploadContext = buildEventArtifactUploader.startUpload(LocalFileType.LOG, null)
        return uploadContext.outputStream
    }

    /** Builder for [BuildEventArtifactInstrumentationOutput]  */
    class Builder : InstrumentationOutputBuilder {
        private var name: String? = null
        private var uploader: BuildEventArtifactUploader? = null

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setName(name: String?): Builder {
            this.name = name
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setUploader(uploader: BuildEventArtifactUploader?): Builder {
            this.uploader = uploader
            return this
        }

        /**
         * {@inheritDoc}
         * 
         * 
         * This is a no-op for [BuildEventArtifactInstrumentationOutput] since it will never be
         * written to a local path.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setCreateParent(createParent: Boolean): Builder {
            return this
        }

        override fun build(): BuildEventArtifactInstrumentationOutput {
            return BuildEventArtifactInstrumentationOutput(
                com.google.common.base.Preconditions.checkNotNull<String?>(
                    name,
                    "Cannot create BuildEventArtifactInstrumentationOutput without name"
                ),
                com.google.common.base.Preconditions.checkNotNull<BuildEventArtifactUploader?>(
                    uploader,
                    "Cannot create BuildEventArtifactInstrumentationOutput without bepUploader"
                )
            )
        }
    }
}
