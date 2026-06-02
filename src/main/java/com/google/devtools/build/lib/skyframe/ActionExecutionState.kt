// Copyright 2019 The Bazel Authors. All rights reserved.
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
 * A state machine representing the synchronous or asynchronous execution of an action. This is
 * shared between all instances of the same shared action and must therefore be thread-safe. Note
 * that only one caller will receive events and output for this action.
 */
internal class ActionExecutionState(actionLookupData: ActionLookupData?, state: ActionStepOrResult?) {
    /** The owner of this object. Only the owner is allowed to continue work on the state machine.  */
    private val actionLookupData: ActionLookupData

    // Both state and completionFuture may only be read or set when holding the lock for this. The
    // state machine for these looks like this:
    //
    // !state.isDone,completionFuture=null -----> !state.isDone,completionFuture=<value>
    //                           |                  |
    //                           |                  | completionFuture.set()
    //                           v                  v
    //                    state.isDone,completionFuture=null
    //
    // (Also, via obsolete(), all states can transition to state==Obsolete.INSTANCE with a null
    // completionFuture, which is terminal.)
    //
    // No other states are legal. In particular, state.isDone,completionFuture=<value> is not a legal
    // state.
    @javax.annotation.concurrent.GuardedBy("this")
    private var state: ActionStepOrResult

    /**
     * A future to represent action completion of the primary action (randomly picked from the set of
     * shared actions). This is initially `null`, and is only set to a non-null value if this
     * turns out to be a shared action, and the primary action is not finished yet (i.e., `!state.isDone`. It is non-null while the primary action is being executed, at which point the
     * thread completing the primary action completes the future, and also sets this field to null.
     * 
     * 
     * The reason for this roundabout approach is to avoid memory allocation if this is not a
     * shared action, and to release the memory once the action is done.
     * 
     * 
     * Skyframe will attempt to cancel this future if the evaluation is interrupted, which violates
     * the concurrency assumptions this class makes. Beware!
     */
    @javax.annotation.concurrent.GuardedBy("this")
    private var completionFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?>? = null

    init {
        this.actionLookupData = com.google.common.base.Preconditions.checkNotNull<ActionLookupData>(actionLookupData)
        this.state = com.google.common.base.Preconditions.checkNotNull<ActionStepOrResult>(state)
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    fun getResultOrDependOnFuture(
        env: SkyFunction.Environment,
        actionLookupData: ActionLookupData?,
        action: Action,
        sharedActionCallback: SharedActionCallback
    ): ActionExecutionValue? {
        if (this.actionLookupData.equals(actionLookupData)) {
            // This object is owned by the Skyframe node executed by the current thread, so we use it to
            // run the state machine.
            return runStateMachine(env)
        }

        // This is a shared action, and the primary action is owned by another Skyframe node. If the
        // primary action is done, we simply return the done value. If this state is obsolete (e.g.
        // because the other node is rewinding), we restart. Otherwise we register a dependency on the
        // completionFuture and return null.
        val result: ActionExecutionValue
        synchronized(this) {
            if (state === com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete.Companion.INSTANCE) {
                scheduleRestart(env)
                return null
            }
            if (!state.isDone) {
                if (completionFuture == null) {
                    completionFuture = com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
                }
                // We expect to only call this once per shared action; this method should only be called
                // again after the future is completed.
                sharedActionCallback.actionStarted()
                env.dependOnFuture(completionFuture)
                if (!env.valuesMissing()) {
                    com.google.common.base.Preconditions.checkState(
                        completionFuture.isCancelled(), "%s %s", this.actionLookupData, actionLookupData
                    )
                    // The future is unexpectedly done. This must be because it was registered by another
                    // thread earlier and was canceled by Skyframe. We are about to be interrupted ourselves,
                    // but have to do something in the meantime. We can just register a dep with a new future,
                    // then complete it and return. If for some reason this argument is incorrect, we will be
                    // restarted immediately and hopefully have a more consistent result.
                    scheduleRestart(env)
                }
                return null
            }
            result = state.get()
        }
        sharedActionCallback.actionCompleted()

        val transformed: ActionExecutionValue?
        try {
            transformed = result.transformForSharedAction(action)
        } catch (e: ActionTransformException) {
            throw java.lang.IllegalStateException(
                String.format("Cannot share %s and %s", this.actionLookupData, actionLookupData), e
            )
        }
        env.getListener().post(SharedActionEvent(result, transformed))
        return transformed
    }

    @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
    private fun runStateMachine(env: SkyFunction.Environment): ActionExecutionValue? {
        val original: ActionStepOrResult
        synchronized(this) {
            if (state === com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete.Companion.INSTANCE) {
                scheduleRestart(env)
                return null
            }
            original = state
        }
        var current = original
        // We do the work _outside_ a synchronized block to avoid blocking threads working on shared
        // actions that only want to register with the completionFuture.
        try {
            while (!current.isDone) {
                // Run the state machine for one step; isDone returned false, so this is safe.
                current = current.run(env)

                // This method guarantees that it either blocks until the action is completed and returns
                // a non-null value, or it registers a dependency with Skyframe and returns null; it must
                // not return null without registering a dependency, i.e., if {@code !env.valuesMissing()}.
                if (env.valuesMissing()) {
                    if (current.isDone) {
                        // This can happen if there was an error in a dep, but another dep was missing. The
                        // Skyframe contract is that this SkyFunction should eagerly process that exception, so
                        // that errors can be transformed in --nokeep_going mode.
                        val value: ActionExecutionValue = current.get()
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                actionLookupData.toString() + " returned " + value + " with values missing"
                            )
                        )
                    }
                    return null
                }
            }
        } finally {
            synchronized(this) {
                if (state !== com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete.Companion.INSTANCE) {
                    com.google.common.base.Preconditions.checkState(
                        state === original,
                        "Another thread illegally modified state"
                    )
                    state = current
                    if (current.isDone && completionFuture != null) {
                        completionFuture.set(null)
                        completionFuture = null
                    }
                }
            }
        }
        // We're done, return the value to the caller (or throw an exception).
        return current.get()
    }

    /**
     * Removes this state from `buildActionMap`, marks it obsolete so that racing shared actions
     * with a reference to this state will restart, and signals to coalesced shared actions that they
     * should re-evaluate.
     */
    @kotlin.jvm.Synchronized
    fun obsolete(
        requester: SkyKey?,
        buildActionMap: ConcurrentMap<OwnerlessArtifactWrapper?, ActionExecutionState>,
        ownerlessArtifactWrapper: OwnerlessArtifactWrapper?
    ) {
        if (actionLookupData.equals(requester)) {
            // An action state's owner only obsoletes it when rewinding. The lost inputs exception thrown
            // from ActionStepOrResult#run left its state undone.
            com.google.common.base.Preconditions.checkState(
                !state.isDone, "owner unexpectedly obsoleted done state: %s", actionLookupData
            )
            val removedState: ActionExecutionState = buildActionMap.remove(ownerlessArtifactWrapper)
            com.google.common.base.Preconditions.checkState(
                removedState == this,
                "owner removed unexpected state from buildActionMap; owner: %s, removed: %s",
                actionLookupData,
                removedState.actionLookupData
            )
            state = com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete.Companion.INSTANCE
            if (completionFuture != null) {
                completionFuture.set(null)
                completionFuture = null
            }
            return
        }
        if (!state.isDone) {
            // An action obsoletes other actions' states when rewinding its dependencies. It may race with
            // other actions to do so. Removing the buildActionMap entry must only be done by the race's
            // winner, to ensure the removal only happens once and removes this state.
            //
            // An action may also attempt to obsolete a dependency's not-done state, if it lost the race
            // with another rewinding action, and the dep started evaluating. If so, then do nothing,
            // because that dep is already doing what it needs to.
            return
        }
        val removedState: ActionExecutionState = buildActionMap.remove(ownerlessArtifactWrapper)
        com.google.common.base.Preconditions.checkState(
            removedState == this,
            "removed unexpected state from buildActionMap; requester: %s, this: %s, removed: %s",
            requester,
            actionLookupData,
            removedState.actionLookupData
        )
        state = com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete.Companion.INSTANCE
    }

    /** A callback to receive events for shared actions that are not executed.  */
    interface SharedActionCallback {
        /** Called if the action is shared and the primary action is already executing.  */
        fun actionStarted()

        /**
         * Called when the primary action is done (on the next call to [ ][.getResultOrDependOnFuture].
         */
        fun actionCompleted()
    }

    /**
     * A state machine where instances of this interface either represent an intermediate state that
     * requires more work to be done (possibly waiting for a ListenableFuture to complete) or the
     * final result of the executed action (either an ActionExecutionValue or an Exception).
     * 
     * 
     * This design allows us to store the current state of the in-progress action execution using a
     * single object reference.
     * 
     * 
     * Do not implement this interface directly! In order to implement an action step, subclass
     * [ActionStep], and implement [.run]. In order to represent a result, use [ ][.of].
     */
    internal interface ActionStepOrResult {
        /**
         * Returns true if and only if the underlying action is complete, i.e., it is legal to call
         * [.get]. The return value of a single object must not change over time. Instead, call
         * [ActionStepOrResult.of] to return a final result (or exception).
         */
        val isDone: Boolean

        /**
         * Returns the next state of the state machine after performing some work towards the end goal
         * of executing the action. This must only be called if [.isDone] returns false, and must
         * only be called by one thread at a time for the same instance.
         */
        @Throws(LostInputsActionExecutionException::class, java.lang.InterruptedException::class)
        fun run(env: SkyFunction.Environment?): ActionStepOrResult

        /**
         * Returns the final value of the action or an exception to indicate that the action failed (or
         * the process was interrupted). This must only be called if [.isDone] returns true.
         */
        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        fun get(): ActionExecutionValue

        companion object {
            fun of(value: ActionExecutionValue?): ActionStepOrResult {
                return com.google.devtools.build.lib.skyframe.ActionExecutionState.Finished(value)
            }

            /**
             * Must not be called with a [LostInputsActionExecutionException]. Throw it from [ ][.run] instead.
             */
            fun of(exception: ActionExecutionException): ActionStepOrResult {
                com.google.common.base.Preconditions.checkArgument(
                    exception !is LostInputsActionExecutionException,
                    "unexpected LostInputs exception: %s",
                    exception
                )
                return Exceptional(exception)
            }

            @com.google.errorprone.annotations.DoNotCall("Throw from #run instead.")
            fun of(ignored: LostInputsActionExecutionException?): ActionStepOrResult? {
                throw java.lang.IllegalArgumentException()
            }

            fun of(exception: java.lang.InterruptedException): ActionStepOrResult {
                return Exceptional(exception)
            }
        }
    }

    /**
     * Abstract implementation of [ActionStepOrResult] that declares final implementations for
     * [.isDone] (to return false) and [.get] (to throw [IllegalStateException]).
     * 
     * 
     * The framework prevents concurrent calls to [.run], so implementations can keep state
     * without having to lock. Note that there may be multiple calls to [.run] from different
     * threads, as long as they do not overlap in time.
     */
    internal abstract class ActionStep : ActionStepOrResult {
        override fun isDone(): Boolean {
            return false
        }

        override fun get(): ActionExecutionValue? {
            throw java.lang.IllegalStateException()
        }
    }

    /**
     * Represents a finished action with a specific value. We specifically avoid anonymous inner
     * classes to not accidentally retain a reference to the ActionRunner.
     */
    private class Finished(value: ActionExecutionValue?) : ActionStepOrResult {
        private val value: ActionExecutionValue?

        init {
            this.value = value
        }

        override fun isDone(): Boolean {
            return true
        }

        override fun run(env: SkyFunction.Environment?): ActionStepOrResult? {
            throw java.lang.IllegalStateException()
        }

        override fun get(): ActionExecutionValue? {
            return value
        }
    }

    /**
     * Represents a finished action with an exception. We specifically avoid anonymous inner classes
     * to not accidentally retain a reference to the ActionRunner.
     */
    private class Exceptional : ActionStepOrResult {
        private val e: java.lang.Exception

        internal constructor(e: ActionExecutionException) {
            this.e = e
        }

        internal constructor(e: java.lang.InterruptedException) {
            this.e = e
        }

        override fun isDone(): Boolean {
            return true
        }

        override fun run(env: SkyFunction.Environment?): ActionStepOrResult? {
            throw java.lang.IllegalStateException()
        }

        @Throws(ActionExecutionException::class, java.lang.InterruptedException::class)
        override fun get(): ActionExecutionValue? {
            if (e is java.lang.InterruptedException) {
                throw e
            }
            throw e as ActionExecutionException?
        }
    }

    /**
     * Represents an action state that is obsolete. Any non-primary shared actions observing this
     * state must restart (see [.scheduleRestart].
     */
    private class Obsolete : ActionStepOrResult {
        override fun isDone(): Boolean {
            return false
        }

        override fun run(env: SkyFunction.Environment?): ActionStepOrResult? {
            throw java.lang.IllegalStateException()
        }

        override fun get(): ActionExecutionValue? {
            throw java.lang.IllegalStateException()
        }

        companion object {
            private val INSTANCE: Obsolete = com.google.devtools.build.lib.skyframe.ActionExecutionState.Obsolete()
        }
    }

    companion object {
        private fun scheduleRestart(env: SkyFunction.Environment) {
            val dummyFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
                com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
            env.dependOnFuture(dummyFuture)
            dummyFuture.set(null)
        }
    }
}
