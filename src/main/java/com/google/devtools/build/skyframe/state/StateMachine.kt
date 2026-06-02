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

import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue

/**
 * A simple state machine with structured concurrency.
 * 
 * 
 * This is used to implement [SkyFunction]s with logical concurrency within [ ]. All execution is singly-threaded. It can be used in places where further
 * stateful decomposition of a computation is desirable but more `Skyframe` entries would
 * create too much overhead. However, the key motivation is to facilitate logical concurrency.
 * 
 * 
 * For example, consider a [SkyFunction] that processes dependencies. Each dependency
 * requires a sequence of processing steps, some of which have `Skyframe` lookups. Let `A = <A1, A2, A3>` and `B = <B1, B2, B3>` be sequences of dependent `SkyKey`s. `A3` depends on `A2` depends on `A1` and similarly for `B3, B2, B1`. The
 * processing of `A` and `B` are independent and therefore logically concurrent.
 * 
 * 
 * One conventional approach is to implement the `SkyFunction` to make groups like `(A1,B1), (A2,B2), (A3,B3)`. Grouping is more efficient than one-at-a-time lookups because in
 * builds where lookups can be performed remotely, such implementations can parallelize over groups
 * but process single queries sequentially to avoid wasted speculative work.
 * 
 * 
 * However, this manual batching may creates false dependencies that lead to unnecessary latency.
 * In the example, `A2` can't be evaluated until `B1` is available. If both `B1`
 * and `A2` are slow, the critical path is unnecessarily lengthened by forcing `A2` to
 * happen after `B1` instead of allowing `A2` to proceed once `A1` is available.
 * From the perspective of restarts, if both `B1` and `A2` require restarts, evaluation
 * requires 2 restarts. However, by running concurrently, the restart of `B1` and `A2`
 * can be grouped together, reducing it to 1 restart.
 * 
 * 
 * The [Driver] class and [Driver.drive] are used to run a state machine.
 * 
 * 
 * A guide is available at [](https://bazel.build/contribute/statemachine-guide).
 */
fun interface StateMachine {
    /**
     * Step performs the next computation.
     * 
     * 
     * [Tasks.lookup] may be used to request [SkyKey]s. The next step will not be
     * executed until all requested [SkyValue]s are available and their associated callbacks
     * have been called. Similarly, [Tasks.enqueue] can be used to spawn a concurrent
     * subcomputation, which must also complete before the next computation step.
     * 
     * 
     * Note that recursive decomposition within subtasks is possible and can be used to capture
     * fine-grain dependency structures. This is required to correctly model the example in the class
     * description. `<A1, A2, A3>` and `<B1, B2, B3>` become concurrent, multi-step,
     * subtasks.
     * 
     * @param tasks an interface for adding subtasks, which may be either [SkyKey] lookups or
     * child state machines. The `tasks` handle is associated with this state machine and
     * other state machines should not use it.
     * @return an instance indicating the next computation or [.DONE] on completion.
     */
    @Throws(java.lang.InterruptedException::class)
    fun step(tasks: Tasks?): StateMachine?

    /**
     * Tasks allows registering logically parallel subtasks.
     * 
     * 
     * Completion of the current step waits until all subtasks are complete.
     */
    interface Tasks {
        /**
         * Enqueues a subtask for logically concurrent evaluation.
         * 
         * 
         * The next step will not be executed until the subtask completes. If more than one subtask
         * is enqueued, the next step waits on all subtasks.
         */
        fun enqueue(subtask: StateMachine?)

        /**
         * A lookup that handles no exceptions.
         * 
         * 
         * This lookup is logically concurrent with other subtasks. The state machine [Driver]
         * may defer the callback until after a `Skyframe` restart if it is not immediately
         * available.
         * 
         * 
         * Unhandled exceptions eventually set a fail fast condition over the entire state machine
         * tree and no further processing occurs afterwards.
         * 
         * 
         * IMPLEMENTATION: if an unhandled exception occurs immediately (without a restart) on a
         * lookup, [Driver] observes unavailability and returns an incomplete status. The driver
         * cannot distinguish here between a result that is not yet computed and an unhandled exception.
         * 
         * 
         * After a restart, all previously requested values should be available, so observing
         * unavailability implies an unhandled exception, triggering fail-fast.
         */
        fun lookUp(key: SkyKey?, sink: java.util.function.Consumer<SkyValue?>?)

        /**
         * A lookup that handles exceptions of the specified type.
         * 
         * 
         * The callback could be deferred until the next `Skyframe` restart if the queried key
         * is not immediately available.
         */
        fun <E : java.lang.Exception?> lookUp(
            key: SkyKey?, exceptionClass: java.lang.Class<E?>?, sink: ValueOrExceptionSink<E?>?
        )

        /** A lookup that handles exceptions of the specified 2 types.  */
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> lookUp(
            key: SkyKey?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            sink: ValueOrException2Sink<E1?, E2?>?
        )

        /** A lookup that handles exceptions of the specified 3 types.  */
        fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> lookUp(
            key: SkyKey?,
            exceptionClass1: java.lang.Class<E1?>?,
            exceptionClass2: java.lang.Class<E2?>?,
            exceptionClass3: java.lang.Class<E3?>?,
            sink: ValueOrException3Sink<E1?, E2?, E3?>?
        )
    }

    /**
     * Receives the result of a lookup.
     * 
     * 
     * Exactly one of `value` or `exception` will be non-null.
     */
    fun interface ValueOrExceptionSink<E : java.lang.Exception?> {
        fun acceptValueOrException(value: SkyValue?, exception: E?)
    }

    /**
     * Receives the result of a lookup.
     * 
     * 
     * Exactly one of `value`, `e1` or `e2` will be non-null.
     */
    fun interface ValueOrException2Sink<E1 : java.lang.Exception?, E2 : java.lang.Exception?> {
        fun acceptValueOrException2(value: SkyValue?, e1: E1?, e2: E2?)
    }

    /**
     * Receives the result of a lookup.
     * 
     * 
     * Exactly one of `value`, `e1`, `e2` or `e3` will be non-null.
     */
    fun interface ValueOrException3Sink<E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> {
        fun acceptValueOrException3(
            value: SkyValue?, e1: E1?, e2: E2?, e3: E3?
        )
    }

    companion object {
        /** A sentinel value returned when a `StateMachine` is done.  */
        @kotlin.jvm.JvmField
        val DONE: StateMachine = StateMachine { t: Tasks? ->
            throw java.lang.IllegalStateException("Sentinel DONE state should not be executed.")
        }
    }
}
