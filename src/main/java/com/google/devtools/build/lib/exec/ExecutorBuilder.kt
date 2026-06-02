// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionInputPrefetcher

/**
 * Builder class to create an [com.google.devtools.build.lib.actions.Executor] instance. This
 * class is part of the module API, which allows modules to affect how the executor is initialized.
 */
class ExecutorBuilder {
    private val executorLifecycleListeners: MutableSet<ExecutorLifecycleListener?> =
        LinkedHashSet<ExecutorLifecycleListener?>()
    private var prefetcher: ActionInputPrefetcher? = null
    private var actionExecutionSalt: String? = null

    /** Returns all executor lifecycle listeners registered with this builder so far.  */
    fun getExecutorLifecycleListeners(): com.google.common.collect.ImmutableSet<ExecutorLifecycleListener?> {
        return com.google.common.collect.ImmutableSet.copyOf<ExecutorLifecycleListener?>(executorLifecycleListeners)
    }

    val actionInputPrefetcher: ActionInputPrefetcher?
        get() = if (prefetcher == null) ActionInputPrefetcher.NONE else prefetcher

    /**
     * Sets the action input prefetcher. Only one module may set the prefetcher. If multiple modules
     * set it, this method will throw an [IllegalStateException].
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setActionInputPrefetcher(prefetcher: ActionInputPrefetcher?): ExecutorBuilder {
        com.google.common.base.Preconditions.checkState(this.prefetcher == null, "prefetcher already set")
            .also {
                this.prefetcher = it
            }<ActionInputPrefetcher> com . google . common . base . Preconditions . checkNotNull < kotlin . Any ? > (prefetcher, "cannot set prefetcher to null")
        return this
    }

    /**
     * Registers an executor lifecycle listener which will receive notifications throughout the
     * execution phase (if one occurs).
     * 
     * @see ExecutorLifecycleListener for events that can be listened to
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addExecutorLifecycleListener(listener: ExecutorLifecycleListener?): ExecutorBuilder {
        executorLifecycleListeners.add(listener)
        return this
    }

    /**
     * Returns the action execution salt previously set by [.setActionExecutionSalt], or the
     * empty string if it was never set.
     */
    fun getActionExecutionSalt(): String {
        return com.google.common.base.Strings.nullToEmpty(actionExecutionSalt)
    }

    /**
     * Sets the action execution salt.
     * 
     * 
     * The salt is an opaque value (typically a digest) used by Skyframe and the persistent action
     * cache to invalidate prior action executions against a different value. It may be suitable for
     * communicating information about the action execution environment that is not already
     * incorporated in the action key.
     * 
     * 
     * At most one module may set the salt. If no module sets it, it defaults to the empty string.
     * If multiple modules set it, an [IllegalStateException] is thrown.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setActionExecutionSalt(actionExecutionSalt: String?): ExecutorBuilder {
        com.google.common.base.Preconditions.checkState(this.actionExecutionSalt == null, "salt already set")
            .also {
                this.actionExecutionSalt = it
            }<String> com . google . common . base . Preconditions . checkNotNull < kotlin . String ? > (actionExecutionSalt, "cannot set salt to null")
        return this
    }
}
