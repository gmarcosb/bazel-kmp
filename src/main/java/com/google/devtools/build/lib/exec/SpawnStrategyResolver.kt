// Copyright 2020 The Bazel Authors. All rights reserved.
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
 * Resolver that looks up the right strategy for a spawn during [.exec] (via a [ ]) and uses it to execute the spawn.
 */
class SpawnStrategyResolver : ActionContext {
    /**
     * Executes the given spawn with the [highest priority strategy][SpawnStrategyRegistry]
     * that can be found for it.
     * 
     * @param actionExecutionContext context in which to execute the spawn
     * @return result(s) from the spawn's execution
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class)
    fun exec(
        spawn: Spawn,
        actionExecutionContext: ActionExecutionContext
    ): com.google.common.collect.ImmutableList<SpawnResult?> {
        return resolveOne(spawn, actionExecutionContext).exec(spawn, actionExecutionContext)
    }

    @Throws(UserExecException::class)
    private fun resolveOne(spawn: Spawn, actionExecutionContext: ActionExecutionContext): SpawnStrategy? {
        val strategies: MutableList<out SpawnStrategy?> = resolve(spawn, actionExecutionContext)

        // Because the strategies are ordered by preference, we can execute the spawn with the best
        // possible one by simply filtering out the ones that can't execute it and then picking the
        // first one from the remaining strategies in the list.
        return strategies.get(0)
    }

    /**
     * Returns the list of [SpawnStrategy]s that should be used to execute the given spawn.
     * 
     * @param spawn spawn for which the correct [SpawnStrategy] should be determined
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(UserExecException::class)
    fun resolve(
        spawn: Spawn, actionExecutionContext: ActionExecutionContext
    ): MutableList<out SpawnStrategy?> {
        val strategies: MutableList<out SpawnStrategy?> =
            actionExecutionContext
                .getContext(SpawnStrategyRegistry::class.java)
                .getStrategies(spawn, actionExecutionContext.getEventHandler())

        val execableStrategies: MutableList<out SpawnStrategy?> =
            strategies.stream()
                .filter { spawnActionContext: SpawnStrategy? ->
                    spawnActionContext.canExec(
                        spawn,
                        actionExecutionContext
                    )
                }
                .collect(Collectors.toList())

        if (execableStrategies.isEmpty()) {
            val message: String? =
                java.lang.String.format(
                    """
              %s spawn%s cannot be executed with any of the available strategies: %s. Your --spawn_strategy, --genrule_strategy, --strategy and/or --allowed_strategies_by_exec_platform flags are probably too strict. Visit https://github.com/bazelbuild/bazel/issues/7480 for advice.
              
              """.trimIndent(),
                    spawn.getMnemonic(),
                    if (Spawns.usesPathMapping(spawn))
                        ", which requires sandboxing due to path mapping,"
                    else
                        "",
                    strategies
                )
            throw UserExecException(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setSpawn(FailureDetails.Spawn.newBuilder().setCode(Code.NO_USABLE_STRATEGY_FOUND))
                    .build()
            )
        }

        return execableStrategies
    }
}
