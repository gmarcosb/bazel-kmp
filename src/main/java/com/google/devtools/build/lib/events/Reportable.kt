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
package com.google.devtools.build.lib.events

/** An event that can be reported to an [ExtendedEventHandler].  */
interface Reportable {
    fun reportTo(handler: com.google.devtools.build.lib.events.ExtendedEventHandler?)

    /**
     * If this event supports tag-based output filtering, returns a new instance identical to this one
     * but with the given tag. Otherwise returns `this`.
     * 
     * 
     * Tags can be used to apply filtering to events. See [OutputFilter].
     */
    fun withTag(tag: String?): Reportable?

    /**
     * If this event originated from [ ][com.google.devtools.build.skyframe.SkyFunction.Environment.getListener], whether it should be
     * stored in the corresponding Skyframe node to be replayed on incremental builds when the node is
     * deemed up-to-date.
     * 
     * 
     * Events which are crucial to the correctness of the evaluation should return `true` so
     * that they are replayed when the [com.google.devtools.build.skyframe.SkyFunction]
     * invocation is cached. On the other hand, events that are merely informational (such as a
     * progress update) should return `false` to avoid taking up memory.
     * 
     * 
     * Evaluations may disable all event storage and replay by using a custom [ ], in which case this method is only used to
     * fulfill the semantics described at [ ][com.google.devtools.build.skyframe.SkyFunction.Environment.getListener].
     * 
     * 
     * This method is not relevant for events which do not originate from [ ] evaluation.
     * 
     * 
     * Classes returning `true` should have cheap [Object.hashCode] and [ ][Object.equals] implementations.
     */
    fun storeForReplay(): Boolean {
        return false
    }
}
