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

import com.google.devtools.build.lib.actions.ActionContext

/**
 * The Executor class provides a dynamic abstraction of the various actual primitive system
 * operations that might be performed during a build step.
 * 
 * 
 * Constructions of this class might perform distributed execution, "virtual" execution for
 * testing purposes, or just print out the sequence of commands that would be executed, like Make's
 * "-n" option.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
class BlazeExecutor(
    fileSystem: com.google.devtools.build.lib.vfs.FileSystem?,
    execRoot: com.google.devtools.build.lib.vfs.Path?,
    reporter: com.google.devtools.build.lib.events.Reporter?,
    clock: com.google.devtools.build.lib.clock.Clock?,
    bugReporter: BugReporter?,
    options: com.google.devtools.common.options.OptionsProvider,
    actionContextRegistry: ModuleActionContextRegistry,
    spawnStrategyRegistry: SpawnStrategyRegistry
) : Executor {
    private val showSubcommands: ShowSubcommands?
    private val fileSystem: com.google.devtools.build.lib.vfs.FileSystem?
    private val execRoot: com.google.devtools.build.lib.vfs.Path?
    private val clock: com.google.devtools.build.lib.clock.Clock?
    private val bugReporter: BugReporter?
    private val options: com.google.devtools.common.options.OptionsProvider
    private val actionContextRegistry: ActionContext.ActionContextRegistry

    /**
     * Constructs an Executor, bound to a specified output base path, and which will use the specified
     * reporter to announce SUBCOMMAND events, the given event bus to delegate events and the given
     * output streams for streaming output. The list of strategy implementation classes is used to
     * construct instances of the strategies mapped by their declared abstract type. This list is
     * uniquified before using. Each strategy instance is created with a reference to this Executor as
     * well as the given options object.
     * 
     * 
     * Don't forget to call startBuildRequest() and stopBuildRequest() for each request, and
     * shutdown() when you're done with this executor.
     */
    init {
        val executionOptions: ExecutionOptions = com.google.common.base.Preconditions.checkNotNull<ExecutionOptions>(
            options.getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
        )
        this.showSubcommands = executionOptions.getShowSubcommands()
        this.fileSystem = fileSystem
        this.execRoot = execRoot
        this.clock = clock
        this.bugReporter = bugReporter
        this.options = options
        this.actionContextRegistry = actionContextRegistry

        spawnStrategyRegistry.logSpawnStrategies()
        actionContextRegistry.logActionContexts()

        actionContextRegistry.notifyUsed()
        spawnStrategyRegistry.notifyUsed(actionContextRegistry)
    }

    public override fun getFileSystem(): com.google.devtools.build.lib.vfs.FileSystem? {
        return fileSystem
    }

    public override fun getExecRoot(): com.google.devtools.build.lib.vfs.Path? {
        return execRoot
    }

    public override fun getClock(): com.google.devtools.build.lib.clock.Clock? {
        return clock
    }

    public override fun getBugReporter(): BugReporter? {
        return bugReporter
    }

    public override fun reportsSubcommands(): ShowSubcommands? {
        return showSubcommands
    }

    public override fun <T : ActionContext?> getContext(type: java.lang.Class<T?>?): T? {
        return actionContextRegistry.getContext(type)
    }

    /** Returns the options associated with the execution.  */
    public override fun getOptions(): com.google.devtools.common.options.OptionsProvider {
        return options
    }
}
