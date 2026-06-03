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

import com.google.devtools.build.lib.actions.ActionContext.ActionContextRegistry
import com.google.devtools.build.lib.actions.ActionExecutionContext
import com.google.devtools.build.lib.actions.ExecException
import com.google.devtools.build.lib.actions.Spawn
import com.google.devtools.build.lib.actions.SpawnResult

/**
 * An implementation of how to [execute][.exec] a [Spawn] instance.
 * 
 * 
 * Strategies are used during the execution phase based on how they were [ ][com.google.devtools.build.lib.runtime.BlazeModule.registerSpawnStrategies]
 * and whether they [match a given spawn based on their abilities][.canExec].
 */
// TODO(blaze-team): If possible, merge this with AbstractSpawnStrategy and SpawnRunner. The former
//  because (almost?) all implementations of this interface extend it; the latter because it forms
//  a shadow hierarchy graph that looks like that of the corresponding strategies.
interface SpawnStrategy {
    /**
     * Executes the given spawn and returns metadata about the execution. Implementations must
     * guarantee that the first list entry represents the successful execution of the given spawn (if
     * no execution was successful, the method must throw an exception instead). The list may contain
     * further entries for (unsuccessful) retries as well as tree artifact management (which may
     * require additional spawn executions).
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun exec(
        spawn: Spawn?,
        actionExecutionContext: ActionExecutionContext?
    ): com.google.common.collect.ImmutableList<SpawnResult?>?

    /** Returns whether this SpawnActionContext supports executing the given Spawn.  */
    fun canExec(spawn: Spawn?, actionContextRegistry: ActionContextRegistry?): Boolean

    /**
     * Performs any actions conditional on this strategy not only being registered but triggered as
     * used because its identifier was requested and it was not overridden.
     * 
     * @param actionContextRegistry a registry containing all available contexts
     */
    // TODO(katre): Remove once strategies are only instantiated if used, the callback can then be
    //  done upon construction.
    fun usedContext(actionContextRegistry: ActionContextRegistry?) {}
}
