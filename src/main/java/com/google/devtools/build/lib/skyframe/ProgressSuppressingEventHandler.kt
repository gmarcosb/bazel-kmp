// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import ExtendedEventHandler.Postable
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable

/**
 * Suppresses [.post] when the provided [Postable] represents a progress event (denoted
 * by a return of `false` from [Postable.storeForReplay]), but otherwise delegates calls
 * to its wrapped [ExtendedEventHandler].
 */
internal class ProgressSuppressingEventHandler(listener: ExtendedEventHandler) : ExtendedEventHandler {
    private val delegate: ExtendedEventHandler

    init {
        this.delegate = listener
    }

    override fun post(obj: Postable) {
        if (obj.storeForReplay()) {
            delegate.post(obj)
        }
    }

    override fun handle(event: com.google.devtools.build.lib.events.Event?) {
        delegate.handle(event)
    }
}
