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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.AbstractAction

/**
 * A strategy for executing an [ ].
 */
class FileWriteStrategy : FileWriteActionContext {
    @Throws(ExecException::class)
    public override fun writeOutputToFile(
        action: AbstractAction,
        actionExecutionContext: ActionExecutionContext,
        deterministicWriter: DeterministicWriter,
        makeExecutable: Boolean,
        isRemotable: Boolean,
        output: Artifact?
    ): com.google.common.collect.ImmutableList<SpawnResult?> {
        actionExecutionContext.getEventHandler().post(RunningActionEvent(action, "local"))
        com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.logged(
            "running write for action " + action.prettyPrint(), MIN_LOGGING
        ).use { p ->
            val outputPath: com.google.devtools.build.lib.vfs.Path = actionExecutionContext.getInputPath(output)
            try {
                BufferedOutputStream(outputPath.getOutputStream()).use { out ->
                    deterministicWriter.writeTo(out)
                }
                if (makeExecutable) {
                    outputPath.setExecutable(true)
                }
            } catch (e: IOException) {
                throw EnvironmentalExecException(e, Code.FILE_WRITE_IO_EXCEPTION)
            }
        }
        return com.google.common.collect.ImmutableList.of<SpawnResult?>()
    }

    companion object {
        private val MIN_LOGGING: java.time.Duration? = java.time.Duration.ofMillis(100)
    }
}
