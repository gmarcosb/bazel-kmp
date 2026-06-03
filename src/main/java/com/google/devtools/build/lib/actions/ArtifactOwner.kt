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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.cmdline.Label

/**
 * An interface for `ActionLookupKey`, or at least for a [Label]. Only tests and
 * internal [Artifact]-generators should implement this interface -- otherwise, `ActionLookupKey` and its subclasses should be the only implementation.
 */
interface ArtifactOwner {
    fun getLabel(): Label?

    companion object {
        /**
         * An [ArtifactOwner] that just returns null for its label. Only for use with resolved
         * source artifacts and tests.
         */
        @SerializationConstant
        val NULL_OWNER: ArtifactOwner = object : ArtifactOwner {
            override fun getLabel(): Label? {
                return null
            }

            override fun toString(): String {
                return "NULL_OWNER"
            }
        }
    }
}
