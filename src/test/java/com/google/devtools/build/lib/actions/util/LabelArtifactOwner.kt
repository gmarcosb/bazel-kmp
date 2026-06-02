// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util

import com.google.common.annotations.VisibleForTesting
import com.google.devtools.build.lib.actions.ArtifactOwner

/** ArtifactOwner wrapper for Labels, for use in tests.  */
@VisibleForTesting
class LabelArtifactOwner @VisibleForTesting constructor(label: Label?) : ArtifactOwner {
    private val label: Label?

    init {
        this.label = label
    }

    public override fun getLabel(): Label? {
        return label
    }

    override fun hashCode(): Int {
        return if (label == null) super.hashCode() else label.hashCode()
    }

    override fun equals(that: Any?): Boolean {
        if (that !is LabelArtifactOwner) {
            return false
        }
        return this.label == that.label
    }
}
