// Copyright 2009 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.exec.util

import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.ActionContext
import com.google.devtools.build.lib.clock.BlazeClock
import com.google.devtools.build.lib.events.Reporter
import com.google.errorprone.annotations.CanIgnoreReturnValue

/** Builder for the test instance of the [BlazeExecutor] class.  */
class TestExecutorBuilder(fileSystem: FileSystem?, execRoot: Path?) {
    private val fileSystem: FileSystem?
    private val execRoot: Path?
    private var reporter: Reporter? = Reporter(EventBusEventHandler.createWithNewEventBus())
    private var optionsParser: OptionsParser = OptionsParser.builder().optionsClasses(DEFAULT_OPTIONS).build()
    private val actionContextRegistryBuilder: ModuleActionContextRegistry.Builder =
        ModuleActionContextRegistry.builder()
    private val strategyRegistryBuilder: SpawnStrategyRegistry.Builder = SpawnStrategyRegistry.builder()

    constructor(fileSystem: FileSystem?, directories: BlazeDirectories) : this(
        fileSystem,
        directories.getExecRoot(TestConstants.WORKSPACE_NAME)
    )

    init {
        this.fileSystem = fileSystem
        this.execRoot = execRoot
        addContext<T?>(FileWriteActionContext::class.java, FileWriteStrategy())
        addContext<T?>(TemplateExpansionContext::class.java, LocalTemplateExpansionStrategy())
        addContext<T?>(
            SymlinkTreeActionContext::class.java,
            SymlinkTreeStrategy(null, TestConstants.WORKSPACE_NAME)
        )
        addContext<T?>(SpawnStrategyResolver::class.java, SpawnStrategyResolver())
    }

    @CanIgnoreReturnValue
    fun setReporter(reporter: Reporter?): TestExecutorBuilder {
        this.reporter = reporter
        return this
    }

    @CanIgnoreReturnValue
    fun setOptionsParser(optionsParser: OptionsParser): TestExecutorBuilder {
        this.optionsParser = optionsParser
        return this
    }

    @CanIgnoreReturnValue
    @Throws(OptionsParsingException::class)
    fun parseOptions(vararg options: String?): TestExecutorBuilder {
        this.optionsParser.parse(*options)
        return this
    }

    @CanIgnoreReturnValue
    @Throws(OptionsParsingException::class)
    fun parseOptions(options: MutableList<String?>?): TestExecutorBuilder {
        this.optionsParser.parse(options)
        return this
    }

    /**
     * Makes the given action context available in the execution phase.
     * 
     * 
     * If two action contexts are registered with the same identifying type and commandline
     * identifier the last registered will take precedence.
     */
    @CanIgnoreReturnValue
    fun <T : ActionContext?> addContext(
        identifyingType: Class<T?>?, context: T?, vararg commandlineIdentifiers: String?
    ): TestExecutorBuilder {
        actionContextRegistryBuilder.register(identifyingType, context, commandlineIdentifiers)
        return this
    }

    /** Makes the given strategy available in the execution phase.  */
    @CanIgnoreReturnValue
    fun addStrategy(strategy: SpawnStrategy?, vararg commandlineIdentifiers: String?): TestExecutorBuilder {
        strategyRegistryBuilder.registerStrategy(strategy, commandlineIdentifiers)
        return this
    }

    /**
     * Sets the default strategies to use if none are supplied by the user.
     * 
     * 
     * Replaces any previously set default strategies.
     */
    @CanIgnoreReturnValue
    fun setDefaultStrategies(vararg strategies: String?): TestExecutorBuilder {
        strategyRegistryBuilder.setDefaultStrategies(ImmutableList.< E > copyOf < E ? > (strategies))
        return this
    }

    @CanIgnoreReturnValue
    fun setExecution(mnemonic: String?, strategy: String): TestExecutorBuilder {
        strategyRegistryBuilder.addMnemonicFilter(mnemonic, ImmutableList.of<E?>(strategy))
        return this
    }

    @Throws(AbruptExitException::class)
    fun build(): BlazeExecutor? {
        val strategyRegistry: SpawnStrategyRegistry? = strategyRegistryBuilder.build()
        addContext<T?>(SpawnStrategyRegistry::class.java, strategyRegistry)
        val actionContextRegistry: ModuleActionContextRegistry? = actionContextRegistryBuilder.build()
        return BlazeExecutor(
            fileSystem,
            execRoot,
            reporter,
            BlazeClock.instance(),
            BugReporter.defaultInstance(),
            optionsParser,
            actionContextRegistry,
            strategyRegistry
        )
    }

    companion object {
        val DEFAULT_OPTIONS: ImmutableList<Class<out OptionsBase?>?> = ImmutableList.of<E?>(
            ExecutionOptions::class.java,
            CommonCommandOptions::class.java,
            CoreOptions::class.java
        )
    }
}
