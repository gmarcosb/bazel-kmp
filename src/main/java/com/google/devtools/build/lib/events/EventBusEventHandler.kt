// Copyright 2026 The Bazel Authors. All rights reserved.
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
 * An event handler that forwards [Postable] events to an [EventBus].
 * 
 * 
 * Lifetime: 1 command.
 */
class EventBusEventHandler(eventBus: com.google.common.eventbus.EventBus?) :
    com.google.devtools.build.lib.events.ExtendedEventHandler {
    // Non-final for cleanup.
    @kotlin.concurrent.Volatile
    private var eventBus: com.google.common.eventbus.EventBus?

    init {
        this.eventBus = eventBus
    }

    override fun handle(event: com.google.devtools.build.lib.events.Event?) {
        // Do nothing. We only handle {@link Postable} events.
    }

    override fun post(obj: com.google.devtools.build.lib.events.ExtendedEventHandler.Postable) {
        if (eventBus != null) {
            eventBus.post(obj)
        }
    }

    override fun cleanup() {
        eventBus = null
    }

    companion object {
        /** Creates a [EventBusEventHandler] with a new [EventBus] enclosed.  */
        @kotlin.jvm.JvmStatic
        fun createWithNewEventBus(): EventBusEventHandler {
            return EventBusEventHandler(com.google.common.eventbus.EventBus())
        }
    }
}
