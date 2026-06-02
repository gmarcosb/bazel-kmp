// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.buildeventstream

import com.google.devtools.build.lib.buildeventstream.BuildEventTransport

/**
 * An event signaling that a [BuildEventTransport] has been closed.
 */
class BuildEventTransportClosedEvent(transport: BuildEventTransport?) :
    com.google.devtools.build.lib.events.ExtendedEventHandler.Postable {
    private val transport: BuildEventTransport?

    init {
        this.transport = transport
    }

    /**
     * Returns the [BuildEventTransport] that has been closed.
     */
    fun transport(): BuildEventTransport? {
        return transport
    }
}
