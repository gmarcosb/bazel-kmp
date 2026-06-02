// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.genrule

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.actions.AbstractAction

/**
 * A spawn action for genrules. Genrules are handled specially in that inputs and outputs are
 * checked for directories.
 */
class GenRuleAction(
    owner: ActionOwner?,
    tools: NestedSet<Artifact?>?,
    inputs: NestedSet<Artifact?>?,
    outputs: ImmutableSet<Artifact?>?,
    commandLines: CommandLines?,
    env: ActionEnvironment?,
    executionInfo: ImmutableMap<String?, String?>?,
    progressMessage: CharSequence?
) : SpawnAction(
    owner,
    tools,
    inputs,
    outputs,
    AbstractAction.DEFAULT_RESOURCE_SET,
    commandLines,
    env,
    executionInfo,
    progressMessage,
    MNEMONIC,
    OutputPathsMode.OFF
) {
    protected val commandLineLimits: CommandLineLimits
        get() = CommandLineLimits.UNLIMITED

    companion object {
        const val MNEMONIC: String = "Genrule"
    }
}
