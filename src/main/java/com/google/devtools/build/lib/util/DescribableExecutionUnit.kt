// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.devtools.build.lib.cmdline.Label

/**
 * Something executable that can be described by [CommandFailureUtils.describeCommandFailure].
 */
interface DescribableExecutionUnit {
    val targetDescription: String?
        get() = null

    /** Returns the command (the first element) and its arguments.  */
    val arguments: com.google.common.collect.ImmutableList<String?>?

    /**
     * Returns the initial environment of the process. If null, the environment is inherited from the
     * parent process.
     */
    val environment: com.google.common.collect.ImmutableMap<String?, String?>?

    val executionPlatformLabel: Label?
        /** Returns the Label of the execution platform for the command, if any, as a String.  */
        get() = null

    val configurationChecksum: String?
        /** Returns the configuration hash for this command, if any.  */
        get() = null

    val mnemonic: String?
}
