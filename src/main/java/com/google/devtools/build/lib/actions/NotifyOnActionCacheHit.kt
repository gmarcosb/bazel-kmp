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

import com.google.devtools.build.lib.events.ExtendedEventHandler

/**
 * An action which must know when it is skipped due to an action cache hit.
 * 
 * 
 * Use should be rare, as the action graph is a functional model.
 */
// TODO(lberki): Maybe merge this with RichDataProducingAction?
interface NotifyOnActionCacheHit : com.google.devtools.build.lib.actions.Action {
    /** A custom interface similar to [ActionExecutionContext], but specific to cache hits.  */
    interface ActionCachedContext {
        /**
         * An event listener to report messages to. Errors that signal an action failure should use
         * ActionExecutionException.
         */
        fun getEventHandler(): ExtendedEventHandler?

        /** Returns the execution root. See [CommandEnvironment.getExecRoot].  */
        fun getExecRoot(): Path?

        /** Returns the [ArtifactPathResolver] for this action.  */
        fun getPathResolver(): ArtifactPathResolver?

        /**
         * Looks up and returns an action context implementation of the given interface type.
         */
        fun <T : ActionContext?> getContext(type: java.lang.Class<out T?>?): T?
    }

    /**
     * Called when action has "cache hit", and therefore need not be executed. Returns false if there
     * was a failure to record the action cache hit, and so the action must be executed.
     * 
     * @param context the action context for a cache hit
     */
    fun actionCacheHit(context: ActionCachedContext?): Boolean
}
