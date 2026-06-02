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

/** Stores error and warning events, and later replays them. Thread-safe.  */
open class StoredEventHandler : com.google.devtools.build.lib.events.ExtendedEventHandler {
    private val events: MutableList<com.google.devtools.build.lib.events.Event?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.Event?>()
    private val posts: MutableList<com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?> =
        java.util.ArrayList<com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?>()
    private var hasErrors = false

    @get:kotlin.jvm.Synchronized
    val andClearEvents: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?>
        /** Returns the events and clears the internal storage.  */
        get() {
            val eventsCopy: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
                com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(events)
            clear()
            return eventsCopy
        }

    @kotlin.jvm.Synchronized
    fun getEvents(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> {
        return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(events)
    }

    @kotlin.jvm.Synchronized
    fun getPosts(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?> {
        return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?>(
            posts
        )
    }

    @get:kotlin.jvm.Synchronized
    val isEmpty: Boolean
        /** Returns true if there are no stored events.  */
        get() = events.isEmpty() && posts.isEmpty()

    @kotlin.jvm.Synchronized
    override fun handle(e: com.google.devtools.build.lib.events.Event) {
        hasErrors = hasErrors or (e.getKind() == com.google.devtools.build.lib.events.EventKind.ERROR)
        events.add(e)
    }

    @kotlin.jvm.Synchronized
    override fun post(e: com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?) {
        posts.add(e)
    }

    /** Replay all events stored in this object on the given eventHandler, in the same order.  */
    @kotlin.jvm.Synchronized
    fun replayOn(eventHandler: com.google.devtools.build.lib.events.ExtendedEventHandler?) {
        com.google.devtools.build.lib.events.Event.Companion.replayEventsOn(eventHandler, events)
        com.google.devtools.build.lib.events.ExtendedEventHandler.Postable.Companion.replayPostsOn(eventHandler, posts)
    }

    /** Returns whether any of the events on this objects were errors.  */
    @kotlin.jvm.Synchronized
    fun hasErrors(): Boolean {
        return hasErrors
    }

    @kotlin.jvm.Synchronized
    fun clear() {
        events.clear()
        posts.clear()
        hasErrors = false
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("events", events)
            .add("posts", posts)
            .add("hasErrors", hasErrors)
            .toString()
    }
}
