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
package com.google.devtools.build.skyframe.state

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.state.Lookup.ConsumerLookup
import com.google.devtools.build.skyframe.state.Lookup.ValueOrException2Lookup
import com.google.devtools.build.skyframe.state.Lookup.ValueOrException3Lookup
import com.google.devtools.build.skyframe.state.Lookup.ValueOrExceptionLookup
import com.google.devtools.build.skyframe.state.StateMachine
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrException2Sink
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrException3Sink
import com.google.devtools.build.skyframe.state.StateMachine.ValueOrExceptionSink

/**
 * Represents the thread that runs a [StateMachine].
 * 
 * 
 * Concurrency in the [StateMachine] is organized as a tree where the root is the only node
 * with a `null` parent.
 */
internal class TaskTreeNode(
    driver: com.google.devtools.build.skyframe.state.Driver,
    parent: TaskTreeNode?,
    state: StateMachine
) : com.google.devtools.build.skyframe.state.StateMachine.Tasks {
    private val driver: com.google.devtools.build.skyframe.state.Driver

    // Null for the root state machine.
    private val parent: TaskTreeNode?
    private var state: StateMachine
    private var pendingChildCount = 0

    init {
        this.driver = driver
        this.parent = parent
        this.state = state
    }

    override fun enqueue(subtask: StateMachine) {
        ++pendingChildCount
        driver.addReady(TaskTreeNode(driver, this, subtask))
    }

    override fun lookUp(key: SkyKey?, sink: java.util.function.Consumer<SkyValue?>?) {
        ++pendingChildCount
        driver.addLookup(ConsumerLookup(this, key, sink))
    }

    override fun <E : java.lang.Exception?> lookUp(
        key: SkyKey?, exceptionClass: java.lang.Class<E?>?, sink: ValueOrExceptionSink<E?>?
    ) {
        ++pendingChildCount
        driver.addLookup(ValueOrExceptionLookup<E?>(this, key, exceptionClass, sink))
    }

    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> lookUp(
        key: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        sink: ValueOrException2Sink<E1?, E2?>?
    ) {
        ++pendingChildCount
        driver.addLookup(
            ValueOrException2Lookup<E1?, E2?>(this, key, exceptionClass1, exceptionClass2, sink)
        )
    }

    override fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> lookUp(
        key: SkyKey?,
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?,
        sink: ValueOrException3Sink<E1?, E2?, E3?>?
    ) {
        ++pendingChildCount
        driver.addLookup(
            ValueOrException3Lookup<E1?, E2?, E3?>(
                this, key, exceptionClass1, exceptionClass2, exceptionClass3, sink
            )
        )
    }

    /** Runs the state machine bound to this node.  */
    @Throws(java.lang.InterruptedException::class)
    fun run() {
        com.google.common.base.Preconditions.checkState(pendingChildCount == 0)
        while (state !== StateMachine.Companion.DONE) {
            state = state.step(this)
            if (pendingChildCount > 0) {
                return
            }
        }
        if (parent != null) {
            parent.signalChildDoneAndEnqueueIfReady()
        }
    }

    /**
     * Signals that a previously requested child is done.
     * 
     * 
     * Enqueues this node if all children are done.
     */
    fun signalChildDoneAndEnqueueIfReady() {
        if (--pendingChildCount == 0) {
            driver.addReady(this)
        }
    }

    override fun toString(): String {
        val stack: java.util.ArrayList<TaskTreeNode?> = java.util.ArrayList<TaskTreeNode?>()
        var next: TaskTreeNode? = this
        while (next != null) {
            stack.add(next)
            next = next.parent
        }
        val buf: java.lang.StringBuilder = java.lang.StringBuilder("TaskTreeNode[")
        var isFirst = true
        // Traverses the stack backwards so the output is in root to leaf order.
        for (i in stack.indices.reversed()) {
            if (isFirst) {
                isFirst = false
            } else {
                buf.append(",\n ")
            }
            buf.append(stack.get(i).state)
        }
        buf.append("]\n")
        return buf.toString()
    }
}
