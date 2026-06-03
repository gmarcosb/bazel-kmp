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
import com.google.devtools.build.lib.events.AbstractEventHandler
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests [AbstractEventHandler].  */
@RunWith(JUnit4::class)
class AbstractEventHandlerTest {
    @org.junit.Test
    fun retainsEventMask() {
        Truth.assertThat(create(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS).getEventMask())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.ALL_EVENTS)
        Truth.assertThat(create(com.google.devtools.build.lib.events.EventKind.ERRORS_AND_WARNINGS).getEventMask())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.ERRORS_AND_WARNINGS)
        Truth.assertThat(create(com.google.devtools.build.lib.events.EventKind.ERRORS).getEventMask())
            .isEqualTo(com.google.devtools.build.lib.events.EventKind.ERRORS)
    }

    companion object {
        private fun create(mask: MutableSet<com.google.devtools.build.lib.events.EventKind?>?): AbstractEventHandler {
            return object : AbstractEventHandler(mask) {
                override fun handle(event: com.google.devtools.build.lib.events.Event?) {}
            }
        }
    }
}
