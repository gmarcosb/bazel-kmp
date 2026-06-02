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
 * An EventHandler which delegates to another EventHandler. Primarily useful as a base class for
 * extending behavior.
 */
open class DelegatingEventHandler(delegate: com.google.devtools.build.lib.events.ExtendedEventHandler?) :
    com.google.devtools.build.lib.events.ExtendedEventHandler {
    protected val delegate: com.google.devtools.build.lib.events.ExtendedEventHandler

    init {
        this.delegate =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.events.ExtendedEventHandler>(
                delegate
            )
    }

    override fun handle(e: com.google.devtools.build.lib.events.Event?) {
        delegate.handle(e)
    }

    override fun post(obj: com.google.devtools.build.lib.events.ExtendedEventHandler.Postable?) {
        delegate.post(obj)
    }
}
