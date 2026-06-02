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

import com.google.devtools.build.lib.runtime.BuildEventArtifactUploaderFactory
import java.util.SortedMap
import java.util.TreeMap

/** Selects between multiple available upload strategies.  */
@javax.annotation.concurrent.ThreadSafe
class BuildEventArtifactUploaderFactoryMap private constructor(uploaders: com.google.common.collect.ImmutableMap<String?, BuildEventArtifactUploaderFactory?>) {
    private val uploaders: com.google.common.collect.ImmutableMap<String?, BuildEventArtifactUploaderFactory?>

    init {
        this.uploaders = uploaders
    }

    fun select(name: String?): BuildEventArtifactUploaderFactory? {
        if (name == null && !uploaders.values().isEmpty()) {
            // TODO(b/110235226): We currently choose the strategy with alphabetically first strategy,
            // which happens to be backwards-compatible; we need to set
            // experimental_build_event_upload_strategy to appropriate default values instead, and then
            // make it an error to pass null.
            return uploaders.values().iterator().next()
        }
        return uploaders.getOrDefault(
            name, BuildEventArtifactUploaderFactory.Companion.LOCAL_FILES_UPLOADER_FACTORY
        )
    }

    /** Builder class for [BuildEventArtifactUploaderFactoryMap].  */
    class Builder {
        private val uploaders: SortedMap<String?, BuildEventArtifactUploaderFactory?> =
            TreeMap<String?, BuildEventArtifactUploaderFactory?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(name: String?, uploader: BuildEventArtifactUploaderFactory?): Builder {
            uploaders.put(name, uploader)
            return this
        }

        fun build(): BuildEventArtifactUploaderFactoryMap {
            return BuildEventArtifactUploaderFactoryMap(
                com.google.common.collect.ImmutableMap.copyOf<String?, BuildEventArtifactUploaderFactory?>(
                    uploaders
                )
            )
        }
    }
}
