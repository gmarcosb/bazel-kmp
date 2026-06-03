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
import com.google.devtools.build.lib.events.EventCollector
import com.google.devtools.build.lib.events.EventTestTemplate
import net.starlark.java.syntax.SyntaxError.location
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests the [EventCollector] class.
 */
@RunWith(JUnit4::class)
class EventCollectorTest : EventTestTemplate() {
    @org.junit.Test
    fun usesPassedInCollection() {
        val events: MutableCollection<com.google.devtools.build.lib.events.Event> =
            java.util.ArrayList<com.google.devtools.build.lib.events.Event>()
        val collector: EventCollector =
            EventCollector(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS, events)
        collector.handle(event)
        val onlyEvent: com.google.devtools.build.lib.events.Event = events.iterator().next()
        Truth.assertThat(onlyEvent.getMessage()).isEqualTo(event.getMessage())
        Truth.assertThat<net.starlark.java.syntax.Location?>(onlyEvent.getLocation()).isSameInstanceAs(location)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(onlyEvent.getKind())
            .isEqualTo(event.getKind())
        Truth.assertThat<net.starlark.java.syntax.Location?>(onlyEvent.getLocation()).isEqualTo(event.getLocation())
        Truth.assertThat(collector.count()).isEqualTo(1)
        Truth.assertThat(events).hasSize(1)
    }

    @org.junit.Test
    fun collectsEvents() {
        val collector: EventCollector = EventCollector()
        collector.handle(event)
        val collectedEventIt: MutableIterator<com.google.devtools.build.lib.events.Event> = collector.iterator()
        val onlyEvent: com.google.devtools.build.lib.events.Event = collectedEventIt.next()
        Truth.assertThat(onlyEvent.getMessage()).isEqualTo(event.getMessage())
        Truth.assertThat<net.starlark.java.syntax.Location?>(onlyEvent.getLocation()).isSameInstanceAs(location)
        Truth.assertThat<com.google.devtools.build.lib.events.EventKind?>(onlyEvent.getKind())
            .isEqualTo(event.getKind())
        Truth.assertThat<net.starlark.java.syntax.Location?>(onlyEvent.getLocation()).isEqualTo(event.getLocation())
        Truth.assertThat(collectedEventIt.hasNext()).isFalse()
    }
}
