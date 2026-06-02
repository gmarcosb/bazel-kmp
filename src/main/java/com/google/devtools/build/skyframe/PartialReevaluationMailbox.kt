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
package com.google.devtools.build.skyframe

import com.google.auto.value.AutoOneOf
import com.google.devtools.build.skyframe.PartialReevaluationMailbox
import com.google.devtools.build.skyframe.SkyFunction.Environment.ClassToInstanceMapSkyKeyComputeState
import com.google.devtools.build.skyframe.SkyFunction.Environment.SkyKeyComputeState
import com.google.devtools.build.skyframe.SkyKey

/**
 * Contains the causes describing why a node, which opted into partial reevaluation, is getting
 * reevaluated.
 * 
 * 
 * Accessible via [SkyKeyComputeState]. Nodes opting into partial reevaluation must access
 * compute states via [ClassToInstanceMapSkyKeyComputeState].
 * 
 * 
 * A node's mailbox may be in one of three general states:
 * 
 * 
 *  1. "freshly initialized",
 *  1. containing causes for the node's partial reevaluation, or,
 *  1. empty of such causes.
 * 
 * 
 * 
 * See [Kind] for details.
 * 
 * 
 * The "Mailbox" naming convention comes from actor models, where concurrent processors of work
 * coordinate by sending each other messages that get stored in "mailboxes" until consumed; see
 * https://wikipedia.org/wiki/Erlang_(programming_language)#Concurrency_and_distribution_orientation
 * for discussion.
 */
class PartialReevaluationMailbox private constructor() : SkyKeyComputeState {
    /** Will be `null` only before the first call to [.getMail].  */
    @javax.annotation.concurrent.GuardedBy("this")
    private var signaledDeps: com.google.common.collect.ImmutableList.Builder<SkyKey?>? = null

    @javax.annotation.concurrent.GuardedBy("this")
    private var other = false

    /** General states that a mailbox may be in.  */
    enum class Kind {
        /**
         * Represents the first time a mailbox is accessed by its node. A mailbox may also be in this
         * state because the mailbox's data was dropped due to memory pressure, or because of other
         * Skyframe nodes completing in error. A [SkyFunction] that observes this state should
         * (re)evaluate "from scratch"; its other [SkyKeyComputeState] data will be in a freshly
         * initialized state too.
         */
        FRESHLY_INITIALIZED,

        /**
         * Represents a nonempty set of causes for a node's reevaluation. See [Causes] for
         * details.
         */
        CAUSES,

        /**
         * Represents an empty set of causes for a node's reevaluation.
         * 
         * 
         * Reading from a mailbox, via [.getMail], empties it. Thereafter, it will no longer
         * be [.FRESHLY_INITIALIZED], unless Skyframe drops its [SkyKeyComputeState].
         * Reading empties its list of signaled dep keys and sets its [Causes.other] flag back to
         * `false`.
         * 
         * 
         * This empty state may be observed during a reevaluation, even from a reevaluation's first
         * read from its mailbox. When an event occurs that may cause a reevaluation (e.g., when a dep
         * completes) adding that cause (e.g., that dep's key) to a parent's mailbox can race with that
         * parent reading its mailbox if the parent is reevaluating at the same time. If such an add
         * wins the race, then the parent consumes the cause during that reevaluation. The event may
         * then schedule a subsequent reevaluation for that parent, which is necessary to handle the
         * case in which the add lost the race. If no other causes get added before the parent reads its
         * mailbox in that subsequent reevaluation, then the mailbox may be empty.
         */
        EMPTY,
    }

    /**
     * A mailbox's detailed state, including whether it was freshly initialized, and the causes it
     * contains for its node's partial reevaluation, if any.
     */
    @AutoOneOf(com.google.devtools.build.skyframe.PartialReevaluationMailbox.Kind::class)
    abstract class Mail {
        abstract fun kind(): Kind?

        abstract fun freshlyInitialized()

        abstract fun empty()

        abstract fun causes(): Causes?

        companion object {
            fun ofFreshlyInitialized(): Mail {
                return AutoOneOf_PartialReevaluationMailbox_Mail.freshlyInitialized()
            }

            fun ofEmpty(): Mail {
                return AutoOneOf_PartialReevaluationMailbox_Mail.empty()
            }

            fun ofCauses(causes: Causes?): Mail {
                return AutoOneOf_PartialReevaluationMailbox_Mail.causes(causes)
            }
        }
    }

    /**
     * A nonempty set of causes for a node's partial reevaluation.
     * 
     * 
     * A dep which a parent node previously requested and observed to not be done will have its key
     * added to that parent's mailbox after the dep completes and before the dep signals the parent.
     * [.signaledDeps] returns that list of keys.
     * 
     * 
     * Skyframe may enqueue a node for evaluation for several other reasons, such as when the node
     * declared an external dependency (via [SkyFunction.Environment.dependOnFuture]) that
     * completes, or when the node's [SkyFunction.compute] method returns a [Reset] value
     * and the node is restarted. In some of these cases (e.g. returning a [Reset] value), the
     * node's [SkyKeyComputeState] will be invalidated, which also drops its mailbox, and the
     * next time that mailbox is read it will return a "freshly initialized" state. But in others
     * (e.g. an external dependency completes), the node's [SkyKeyComputeState] is retained. In
     * any of these cases in which a node is enqueued for evaluation and its mailbox is retained, a
     * flag will be set in the node's mailbox to indicate that the node's [SkyFunction] should
     * try its best to make progress, by, e.g., checking whether its external dep futures have
     * completed, checking whether its previously requested deps are done, or reevaluating from
     * scratch. ([Causes.other]) returns the value of that flag.
     * 
     * @param signaledDeps [SkyKey] s of previously requested deps which have completed since
     * the last time the mailbox was read.
     * @param other Whether Skyframe enqueued a reevaluation for any other reason besides a dep
     * completing normally, in such a way that the dep's key would be added to [     ][.signaledDeps].
     */
    class Causes(signaledDeps: com.google.common.collect.ImmutableList<SkyKey?>?, val other: Boolean) {
        val signaledDeps: com.google.common.collect.ImmutableList<SkyKey?>?

        init {
            this.signaledDeps = signaledDeps
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<SkyKey?>?>(
                signaledDeps,
                "signaledDeps"
            )
        }

        companion object {
            fun create(signaledDeps: com.google.common.collect.ImmutableList<SkyKey?>?, other: Boolean): Causes {
                return Causes(signaledDeps, other)
            }
        }
    }

    /** Used by Skyframe to record that a dep has signaled a node opting into partial reevaluation.  */
    @kotlin.jvm.Synchronized
    fun signal(dep: SkyKey) {
        if (signaledDeps != null) {
            signaledDeps.add(dep)
        }
    }

    /**
     * Used by Skyframe to record that a node opting into partial reevaluation has been enqueued for
     * evaluation in contexts where that happens for reasons other than a dep signaling it.
     */
    @kotlin.jvm.Synchronized
    fun enqueuedNotByDeps() {
        other = true
    }

    /** Gets and clears the current causes for a node's partial reevaluation.  */
    fun getMail(): Mail {
        val signaledDeps: com.google.common.collect.ImmutableList.Builder<SkyKey?>?
        val other: Boolean
        val newBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.Builder<SkyKey?>()
        synchronized(this) {
            signaledDeps = this.signaledDeps
            this.signaledDeps = newBuilder

            other = this.other
            this.other = false
        }
        if (signaledDeps == null) {
            return Mail.Companion.ofFreshlyInitialized()
        }
        val signaledDepsList: com.google.common.collect.ImmutableList<SkyKey?> = signaledDeps.build()
        if (signaledDepsList.isEmpty() && !other) {
            return Mail.Companion.ofEmpty()
        }
        return Mail.Companion.ofCauses(Causes.Companion.create(signaledDepsList, other))
    }

    companion object {
        fun from(computeState: ClassToInstanceMapSkyKeyComputeState): PartialReevaluationMailbox? {
            return computeState.getInstance<PartialReevaluationMailbox?>(
                PartialReevaluationMailbox::class.java, java.util.function.Supplier { PartialReevaluationMailbox() })
        }
    }
}
