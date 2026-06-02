// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.ArtifactGroupNamer
import com.google.devtools.build.lib.buildeventstream.BuildEventProtocolOptions

/**
 * Interface for providing [BuildEvent]s with the convertes needed for computing the protobuf
 * representation.
 */
interface BuildEventContext {
    /**
     * Return the [PathConverter] to be used in order to obtain URIs for the file paths to be
     * reported in the event.
     */
    fun pathConverter(): com.google.devtools.build.lib.buildeventstream.PathConverter?

    /**
     * Return the [ArtifactGroupNamer] that can be used to refer to a `NestedSet<Artifact>` declared via the [ ] interface.
     */
    fun artifactGroupNamer(): ArtifactGroupNamer?

    /**
     * Returns the options for the build event stream.
     */
    val options: BuildEventProtocolOptions?

    /** Mode in which files are reported in an output group: recursive filesets, inline, or both.  */
    enum class OutputGroupFileMode {
        NAMED_SET_OF_FILES_ONLY,
        INLINE_ONLY,
        BOTH
    }

    /** Return the [OutputGroupFileMode] to use for a given output group.  */
    fun getFileModeForOutputGroup(outputGroup: String?): OutputGroupFileMode? {
        return OutputGroupFileMode.NAMED_SET_OF_FILES_ONLY
    }
}
