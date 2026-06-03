// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.buildjar.javac

import com.google.devtools.build.buildjar.InvalidCommandLineException
import com.google.devtools.build.buildjar.javac.WorkerCancellationRegistry
import com.google.devtools.build.buildjar.javac.plugins.BlazeJavaCompilerPlugin
import com.google.devtools.build.lib.clock.BlazeClock.instance
import com.sun.source.util.TaskEvent
import com.sun.source.util.TaskListener
import com.sun.tools.javac.api.MultiTaskListener
import com.sun.tools.javac.comp.AttrContext

/**
 * A helper plugin to stop the java compilation at different stages when the worker cancellation is
 * enabled.
 */
class CancelCompilerPlugin(private val requestId: Int, cancellationRegistry: WorkerCancellationRegistry) :
    BlazeJavaCompilerPlugin(), TaskListener {
    private val cancellationRegistry: WorkerCancellationRegistry

    /**
     * @param requestId the id of the javac request that needs to be cancelled.
     * @param cancellationRegistry this registry handles which requests to be cancelled.
     */
    init {
        this.cancellationRegistry = cancellationRegistry
    }

    override fun initializeContext(context: com.sun.tools.javac.util.Context) {
        super.initializeContext(context)
        MultiTaskListener.instance(context).add(this)
    }

    @Throws(InvalidCommandLineException::class)
    override fun processArgs(
        standardJavacopts: com.google.common.collect.ImmutableList<String?>?,
        blazeJavacopts: com.google.common.collect.ImmutableList<String?>?
    ) {
        cancelRequest()
    }

    override fun postAttribute(env: com.sun.tools.javac.comp.Env<AttrContext?>?) {
        cancelRequest()
    }

    override fun postFlow(env: com.sun.tools.javac.comp.Env<AttrContext?>?) {
        cancelRequest()
    }

    override fun started(e: TaskEvent?) {
        cancelRequest()
    }

    override fun finished(e: TaskEvent?) {
        cancelRequest()
    }

    /** A subclass of RuntimeException specific to when compilation fails because of cancellation.  */
    class CancelRequestException : java.lang.RuntimeException()

    private fun cancelRequest() {
        if (cancellationRegistry.checkIfRequestIsCancelled(requestId)) {
            throw CancelRequestException()
        }
    }
}
