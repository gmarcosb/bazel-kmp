// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionGraph

/**
 * Type that can get informed about executor lifecycle events.
 * 
 * 
 * Notifications occur in this order:
 * 
 * 
 *  1. [.executorCreated]
 *  1. [.executionPhaseStarting]
 *  1. [.executionPhaseEnding]
 * 
 */
interface ExecutorLifecycleListener {
    /** Handles executor creation.  */
    @Throws(AbruptExitException::class)
    fun executorCreated()

    /**
     * Handles the start of the execution phase.
     * 
     * @param actionGraph actions as calculated in the analysis phase. Null in Skymeld mode.
     * @param topLevelArtifacts supplies all output artifacts from top-level targets and aspects. Null
     * in skymeld mode.
     * @param ephemeralCheckIfOutputConsumed tests whether an artifact is consumed in this build.
     */
    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    fun executionPhaseStarting(
        actionGraph: ActionGraph?,
        topLevelArtifacts: java.util.function.Supplier<com.google.common.collect.ImmutableSet<Artifact?>?>?,
        ephemeralCheckIfOutputConsumed: EphemeralCheckIfOutputConsumed?
    )

    /** Handles the end of the execution phase.  */
    fun executionPhaseEnding()
}
