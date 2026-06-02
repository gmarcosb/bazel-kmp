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
package com.google.devtools.build.lib.events

/**
 * An [EventHandler] that collects all events it encounters, and makes them available via the
 * [Iterable] interface. The collected events contain not just the original event information
 * but also the location context.
 */
class EventCollector @kotlin.jvm.JvmOverloads constructor(
    mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>? = com.google.devtools.build.lib.events.EventKind.Companion.ALL_EVENTS,
    collected: MutableCollection<com.google.devtools.build.lib.events.Event?> = java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()
) : com.google.devtools.build.lib.events.AbstractEventHandler(mask),
    Iterable<com.google.devtools.build.lib.events.Event?> {
    private val collected: MutableCollection<com.google.devtools.build.lib.events.Event?>

    /**
     * This collector will collect all events that match the event mask.
     */
    constructor(vararg mask: com.google.devtools.build.lib.events.EventKind?) : this(
        com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.events.EventKind?>(
            mask
        ), java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()
    )

    /**
     * This collector will save the Event instances in the provided
     * collection.
     */
    /**
     * This collector will collect all events.
     */
    /**
     * This collector will collect all events that match the event mask.
     */
    init {
        this.collected = collected
    }

    /**
     * Implements [EventHandler.handle].
     */
    @kotlin.jvm.Synchronized
    override fun handle(event: com.google.devtools.build.lib.events.Event) {
        if (getEventMask().contains(event.getKind())) {
            collected.add(event)
        }
        if (event.getStdErr() != null) {
            handle(
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.STDERR,
                    null,
                    event.getStdErr()
                )
            )
        }
        if (event.getStdOut() != null) {
            handle(
                com.google.devtools.build.lib.events.Event.Companion.of(
                    com.google.devtools.build.lib.events.EventKind.STDOUT,
                    null,
                    event.getStdOut()
                )
            )
        }
    }

    /**
     * Returns an iterator over the collected events. This must not be called in a scenario where
     * there may still be concurrent modifications to the collector.
     */
    override fun iterator(): MutableIterator<com.google.devtools.build.lib.events.Event?>? {
        return collected.iterator()
    }

    /**
     * Returns an iterator over the collected events of the given kind. This must not be called in a
     * scenario where there may still be concurrent modifications to the collector.
     */
    fun filtered(eventKind: com.google.devtools.build.lib.events.EventKind?): Iterable<com.google.devtools.build.lib.events.Event?> {
        return com.google.common.collect.Iterables.filter<com.google.devtools.build.lib.events.Event?>(
            collected,
            com.google.common.base.Predicate { event: com.google.devtools.build.lib.events.Event? -> event.getKind() == eventKind })
    }

    /**
     * Returns the number of events collected.
     */
    @kotlin.jvm.Synchronized
    fun count(): Int {
        return collected.size()
    }

    /*
   * Clears the collected events
   */
    @kotlin.jvm.Synchronized
    fun clear() {
        collected.clear()
    }

    @kotlin.jvm.Synchronized
    override fun toString(): String {
        return "EventCollector: " + com.google.common.collect.Iterables.toString(collected)
    }
}
