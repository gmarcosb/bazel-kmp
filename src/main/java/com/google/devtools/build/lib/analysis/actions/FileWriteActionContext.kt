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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * The action context for [AbstractFileWriteAction] instances (technically instances of
 * subclasses).
 */
interface FileWriteActionContext : ActionContext {
    @Throws(java.lang.InterruptedException::class, ExecException::class)
    fun writeOutputToFile(
        action: AbstractAction?,
        actionExecutionContext: ActionExecutionContext?,
        deterministicWriter: DeterministicWriter?,
        makeExecutable: Boolean,
        isRemotable: Boolean,
        output: Artifact?
    ): com.google.common.collect.ImmutableList<SpawnResult?>?

    /**
     * Writes the output created by the [DeterministicWriter] to the sole output of the given
     * action.
     */
    @Throws(java.lang.InterruptedException::class, ExecException::class)
    fun writeOutputToFile(
        action: AbstractAction,
        actionExecutionContext: ActionExecutionContext?,
        deterministicWriter: DeterministicWriter?,
        makeExecutable: Boolean,
        isRemotable: Boolean
    ): com.google.common.collect.ImmutableList<SpawnResult?>? {
        return writeOutputToFile(
            action,
            actionExecutionContext,
            deterministicWriter,
            makeExecutable,
            isRemotable,
            com.google.common.collect.Iterables.getOnlyElement<T?>(action.getOutputs())
        )
    }
}
