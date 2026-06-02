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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.actions.Artifact.ArtifactSerializationContext

/** Utilities for testing with serialization dependencies.  */
object SerializationDepsUtils {
    /** Default serialization dependencies for testing.  */
    @kotlin.jvm.JvmField
    val SERIALIZATION_DEPS_FOR_TEST: com.google.common.collect.ImmutableClassToInstanceMap<*> =
        com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
            .put<ArtifactSerializationContext?>(
                ArtifactSerializationContext::class.java,
                ArtifactSerializationContext { execPath, root, owner -> SourceArtifact(root, execPath, owner) })
            .put<OptionsChecksumCache?>(OptionsChecksumCache::class.java, MapBackedChecksumCache())
            .build()
}
