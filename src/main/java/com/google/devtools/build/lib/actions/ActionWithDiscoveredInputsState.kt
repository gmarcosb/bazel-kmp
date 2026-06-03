// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * Interface for actions that need to retain discovered inputs state across Skyframe restarts.
 * 
 * 
 * While most [Action] implementations are immutable after analysis, actions that [ ][.discoversInputs] frequently need to store the results of that discovery to avoid redundant
 * work upon Skyframe restarts (e.g., when a discovered input is missing from the graph).
 * 
 * 
 * This interface is preferred over relying solely on `discoversInputs()` for several
 * reasons:
 * 
 * 
 *  * **Immutability Policy:** It allows immutable actions to remain immutable by default,
 * while providing a type-safe way for specific actions to opt-in to state restoration.
 *  * **State Restoration:** When `ActionExecutionFunction` restarts, it loses its local
 * state. Discovered inputs preserved in `SkyKeyComputeState` must be re-injected into
 * the action instance via [.setAdditionalInputs] before execution.
 *  * **Decoupling:** It enables the Skyframe execution engine to interact with many different
 * action types without needing to cast to concrete implementation classes.
 * 
 */
interface ActionWithDiscoveredInputsState : com.google.devtools.build.lib.actions.Action {
    /** Sets the discovered inputs on the action instance.  */
    fun setAdditionalInputs(inputs: NestedSet<Artifact?>?)
}
