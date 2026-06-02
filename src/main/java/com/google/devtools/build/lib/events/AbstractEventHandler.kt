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
 * An abstract event handler that keeps track of the event mask. Events matching the mask will be
 * handled.
 */
abstract class AbstractEventHandler(mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>?) :
    com.google.devtools.build.lib.events.EventHandler {
    private val mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>?

    /**
     * Events matching the mask will be handled.
     */
    init {
        this.mask = mask
    }

    val eventMask: MutableSet<com.google.devtools.build.lib.events.EventKind>?
        get() = mask
}
