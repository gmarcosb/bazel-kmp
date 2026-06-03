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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.events.Event

/**
 * [Subject] for `Iterable<Event>` that provides an [IterableSubject] of [ ] objects as opposed to the harder-to-assert-on [Event] objects.
 */
internal class EventIterableSubject(failureMetadata: FailureMetadata?, actual: Iterable<Event?>?) :
    com.google.common.truth.Subject(failureMetadata, actual) {
    private val actual: Iterable<Event?>?

    init {
        this.actual = actual
    }

    fun hasEventsThat(): IterableSubject {
        return Truth.assertThat(com.google.common.collect.Iterables.transform<Event?, Any?>(actual, Event::getMessage))
    }
}
