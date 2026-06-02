// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.Action

/**
 * An event that is fired when all inputs of an action are collected but before the these inputs are
 * requested to skyframe.
 */
class ActionInputCollectedEvent(
    action: Action?,
    inputs: NestedSet<Artifact?>?,
    actionContextRegistry: ActionContextRegistry?
) : Postable {
    val action: Action?
    val inputs: NestedSet<Artifact?>?
    val actionContextRegistry: ActionContextRegistry?

    init {
        this.actionContextRegistry = actionContextRegistry
        this.inputs = inputs
        this.action = action
        java.util.Objects.requireNonNull<Any?>(action, "action")
        java.util.Objects.requireNonNull<Any?>(inputs, "inputs")
        java.util.Objects.requireNonNull<Any?>(actionContextRegistry, "actionContextRegistry")
    }

    companion object {
        fun create(
            action: Action?, inputs: NestedSet<Artifact?>?, actionContextRegistry: ActionContextRegistry?
        ): ActionInputCollectedEvent {
            return ActionInputCollectedEvent(action, inputs, actionContextRegistry)
        }
    }
}
