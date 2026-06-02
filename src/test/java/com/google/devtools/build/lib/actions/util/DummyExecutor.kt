// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions.util

import com.google.devtools.build.lib.actions.ActionContext
import com.google.devtools.build.lib.clock.Clock
import com.google.devtools.build.lib.testutil.ManualClock

/** A dummy implementation of Executor.  */
class DummyExecutor(
    fileSystem: FileSystem?,
    bugReporter: BugReporter?,
    inputDir: Path?,
    optionsProvider: OptionsProvider?,
    showSubcommands: ShowSubcommands?
) : Executor {
    private val fileSystem: FileSystem?
    private val bugReporter: BugReporter?
    private val inputDir: Path?
    private val clock = ManualClock()
    private val optionsProvider: OptionsProvider?
    private val showSubcommands: ShowSubcommands?

    init {
        this.fileSystem = fileSystem
        this.bugReporter = bugReporter
        this.inputDir = inputDir
        this.optionsProvider = optionsProvider
        this.showSubcommands = showSubcommands
    }

    constructor(fileSystem: FileSystem?, inputDir: Path?, optionsProvider: OptionsProvider?) : this(
        fileSystem,
        BugReporter.defaultInstance(),
        inputDir,
        optionsProvider,  /*showSubcommands=*/
        null
    )

    @kotlin.jvm.JvmOverloads
    constructor(fileSystem: FileSystem? = null, inputDir: Path? = null) : this(fileSystem, inputDir, null)

    public override fun getFileSystem(): FileSystem? {
        return fileSystem
    }

    public override fun getExecRoot(): Path? {
        return inputDir
    }

    public override fun getClock(): Clock {
        return clock
    }

    public override fun getBugReporter(): BugReporter? {
        return bugReporter
    }

    public override fun <T : ActionContext?> getContext(type: Class<T?>?): T? {
        return null
    }

    public override fun getOptions(): OptionsProvider {
        if (optionsProvider != null) {
            return optionsProvider
        }
        throw UnsupportedOperationException()
    }

    public override fun reportsSubcommands(): ShowSubcommands? {
        if (showSubcommands != null) {
            return showSubcommands
        }
        throw UnsupportedOperationException()
    }
}
