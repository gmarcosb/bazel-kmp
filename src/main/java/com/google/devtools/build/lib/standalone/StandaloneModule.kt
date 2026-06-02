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
package com.google.devtools.build.lib.standalone

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.analysis.actions.FileWriteActionContext
import com.google.devtools.build.lib.vfs.Path

/**
 * StandaloneModule provides pluggable functionality for blaze.
 */
class StandaloneModule : BlazeModule() {
    public override fun registerActionContexts(
        registryBuilder: ModuleActionContextRegistry.Builder,
        env: CommandEnvironment,
        buildRequest: BuildRequest?
    ) {
        // TODO(ulfjack): Move this to another module.
        registryBuilder.register(
            CppIncludeExtractionContext::class.java, DummyCppIncludeExtractionContext(env)
        )
        registryBuilder.register(CppIncludeScanningContext::class.java, DummyCppIncludeScanningContext())

        val executionOptions: ExecutionOptions? = env.getOptions().getOptions(ExecutionOptions::class.java)
        var testSummaryOptions: TestSummaryOptions? = env.getOptions().getOptions(TestSummaryOptions::class.java)
        if (testSummaryOptions == null) {
            // It is possible, though unlikely, that the test summary options have not been set.
            // This can happen if a test runner is being run without the test command having been used.
            testSummaryOptions = TestSummaryOptions.DEFAULTS
        }
        val testTmpRoot: Path? =
            TestStrategy.getTmpRoot(env.getWorkspace(), env.getExecRoot(), executionOptions)
        val testStrategy: TestActionContext =
            StandaloneTestStrategy(executionOptions, testSummaryOptions, testTmpRoot)
        // Keep the standalone test strategy last so that it is the default one.
        registryBuilder.register(
            TestActionContext::class.java, ExclusiveTestStrategy(testStrategy), "exclusive"
        )
        registryBuilder.register(TestActionContext::class.java, testStrategy, "standalone")
        registryBuilder.register(FileWriteActionContext::class.java, FileWriteStrategy(), "local")
        registryBuilder.register(
            TemplateExpansionContext::class.java, LocalTemplateExpansionStrategy(), "local"
        )
    }

    public override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment
    ) {
        val localSpawnRunner: SpawnRunner =
            LocalSpawnRunner(
                env.getExecRoot(),
                env.getOptions().getOptions(LocalExecutionOptions::class.java),
                env.getLocalResourceManager(),
                LocalEnvProvider.forCurrentOs(env.getClientEnv()),
                env.getBlazeWorkspace().getBinTools(),
                ProcessWrapper.fromCommandEnvironment(env),
                RunfilesTreeUpdater.forCommandEnvironment(env)
            )

        val executionOptions: ExecutionOptions =
            Preconditions.checkNotNull<T>(env.getOptions().getOptions(ExecutionOptions::class.java))
        // Order of strategies passed to builder is significant - when there are many strategies that
        // could potentially be used and a spawnActionContext doesn't specify which one it wants, the
        // last one from strategies list will be used
        registryBuilder.registerStrategy(
            StandaloneSpawnStrategy(localSpawnRunner, executionOptions), "standalone", "local"
        )

        // This makes the "standalone" strategy the default Spawn strategy, unless it is overridden by a
        // later BlazeModule.
        registryBuilder.setDefaultStrategies(ImmutableList.of<E?>("standalone"))
    }
}
