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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * Record errors, such as missing package/target or rules containing errors, encountered during
 * visitation. Emit an error message upon encountering missing edges.
 * 
 * 
 * The accessor [.hasErrors]) may not be called until the concurrent phase is over, i.e.
 * all external calls to visit() methods have completed.
 */
@ConditionallyThreadSafe
internal class ErrorPrintingTargetEdgeErrorObserver(eventHandler: com.google.devtools.build.lib.events.EventHandler) :
    TargetEdgeErrorObserver() {
    private val eventHandler: com.google.devtools.build.lib.events.EventHandler

    /** @param eventHandler eventHandler to route exceptions to as errors.
     */
    init {
        this.eventHandler = eventHandler
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun missingEdge(target: Target?, label: Label?, e: NoSuchThingException) {
        val detailedExitCode: DetailedExitCode? = e.getDetailedExitCode()
        eventHandler.handle(
            com.google.devtools.build.lib.events.Event.error(
                TargetUtils.getLocationMaybe(target),
                TargetUtils.formatMissingEdge(target, label, e)
            )
                .withProperty<DetailedExitCode?>(DetailedExitCode::class.java, detailedExitCode)
        )
        super.missingEdge(target, label, e)
    }
}
