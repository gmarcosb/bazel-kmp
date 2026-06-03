// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.FileArtifactValue

/**
 * An interface used to check whether an output should be downloaded or its metadata revalidated
 * when it is stored with a bounded lifetime.
 */
interface OutputChecker {
    /** Returns whether the given output should be downloaded.  */
    fun shouldDownloadOutput(output: ActionInput?, metadata: FileArtifactValue?): Boolean {
        return !shouldTrustMetadata(output, metadata)
    }

    /** Returns whether the given metadata should be trusted.  */
    fun shouldTrustMetadata(file: ActionInput?, metadata: FileArtifactValue?): Boolean

    companion object {
        val TRUST_ALL: OutputChecker = OutputChecker { file: ActionInput?, metadata: FileArtifactValue? -> true }
        val TRUST_LOCAL_ONLY: OutputChecker =
            OutputChecker { file: ActionInput?, metadata: FileArtifactValue? -> !metadata.isRemote() }
    }
}
