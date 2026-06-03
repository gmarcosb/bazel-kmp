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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.events.StoredEventHandler
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests the [StoredEventHandler] class.  */
@RunWith(JUnit4::class)
class StoredErrorEventHandlerTest {
    @org.junit.Test
    fun hasErrors() {
        val eventHandler: StoredEventHandler = StoredEventHandler()
        Truth.assertThat(eventHandler.hasErrors()).isFalse()
        eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("warning"))
        Truth.assertThat(eventHandler.hasErrors()).isFalse()
        eventHandler.handle(com.google.devtools.build.lib.events.Event.info("info"))
        Truth.assertThat(eventHandler.hasErrors()).isFalse()
        eventHandler.handle(com.google.devtools.build.lib.events.Event.error("error"))
        Truth.assertThat(eventHandler.hasErrors()).isTrue()
    }

    @org.junit.Test
    fun replayOnWithoutEvents() {
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val sink: StoredEventHandler = StoredEventHandler()

        eventHandler.replayOn(sink)
        Truth.assertThat(sink.isEmpty()).isTrue()
    }

    @org.junit.Test
    fun replayOn() {
        val eventHandler: StoredEventHandler = StoredEventHandler()
        val sink: StoredEventHandler = StoredEventHandler()

        val events: MutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.events.Event?>(
                com.google.devtools.build.lib.events.Event.warn("a"),
                com.google.devtools.build.lib.events.Event.error("b"),
                com.google.devtools.build.lib.events.Event.info("c"),
                com.google.devtools.build.lib.events.Event.warn("d")
            )
        for (e in events) {
            eventHandler.handle(e)
        }

        eventHandler.replayOn(sink)
        Truth.assertThat(sink.getEvents()).isEqualTo(events)
    }
}
