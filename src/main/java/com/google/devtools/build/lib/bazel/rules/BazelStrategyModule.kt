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
package com.google.devtools.build.lib.bazel.rules

import com.google.devtools.build.lib.analysis.actions.FileWriteActionContext

/** Module which registers the strategy options for Bazel.  */
class BazelStrategyModule : BlazeModule() {
    override fun getCommandOptions(commandName: String): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return if (commandName == "build")
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                ExecutionOptions::class.java,
                RemoteOptions::class.java
            )
        else
            com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
    }

    override fun registerActionContexts(
        registryBuilder: com.google.devtools.build.lib.exec.ModuleActionContextRegistry.Builder,
        env: CommandEnvironment?,
        buildRequest: BuildRequest?
    ) {
        registryBuilder
            .restrictTo(CppIncludeExtractionContext::class.java, "")
            .restrictTo(CppIncludeScanningContext::class.java, "")
            .restrictTo(FileWriteActionContext::class.java, "")
            .restrictTo(TemplateExpansionContext::class.java, "")
            .restrictTo(SpawnCache::class.java, "")
    }

    override fun registerSpawnStrategies(
        registryBuilder: com.google.devtools.build.lib.exec.SpawnStrategyRegistry.Builder, env: CommandEnvironment
    ) {
        val options: ExecutionOptions? = env.getOptions().getOptions<ExecutionOptions?>(ExecutionOptions::class.java)
        val remoteOptions: RemoteOptions? = env.getOptions().getOptions<RemoteOptions?>(RemoteOptions::class.java)

        val spawnStrategies: MutableList<String?> = java.util.ArrayList<String?>(options.getSpawnStrategy())

        if (spawnStrategies.isEmpty()) {
            if (RemoteModule.shouldEnableRemoteExecution(remoteOptions)) {
                spawnStrategies.add("remote")
            }
            spawnStrategies.add("worker")
            // Sandboxing is not yet available on Windows.
            if (com.google.devtools.build.lib.util.OS.getCurrent() != com.google.devtools.build.lib.util.OS.WINDOWS) {
                spawnStrategies.add("sandboxed")
            }
            spawnStrategies.add("local")
        }
        registryBuilder.setDefaultStrategies(spawnStrategies)

        // By adding this filter before the ones derived from --strategy the latter can override the
        // former.
        registryBuilder.addMnemonicFilter("Genrule", options.getGenruleStrategy())

        for (strategy in options.getStrategy()) {
            registryBuilder.addMnemonicFilter(strategy.getKey(), strategy.getValue())
        }

        for (entry in options.getStrategyByRegexp()) {
            registryBuilder.addDescriptionFilter(entry.getKey(), entry.getValue())
        }

        for (strategy in options.getAllowedStrategiesByExecPlatform()) {
            registryBuilder.addExecPlatformFilter(strategy.getKey(), strategy.getValue())
        }
    }
}
