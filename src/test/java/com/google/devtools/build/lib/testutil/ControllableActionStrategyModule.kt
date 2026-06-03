// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.actions.SpawnStrategy

/**
 * A [BlazeModule] that uses [SpawnController] to inject custom behavior.
 * 
 * 
 * The identifiers of strategies to make controllable are passed to the constructor. These
 * strategies are expected to already exist in the [SpawnStrategyRegistry.Builder] when [ ][.registerSpawnStrategies] is called, so the modules responsible for registering them should be
 * added to the runtime builder *before* the `ControllableActionStrategyModule`. Each
 * strategy corresponding to a specified identifier is replaced in the [ ] with a [controllable wrapper][SpawnController.wrap].
 */
class ControllableActionStrategyModule(spawnController: SpawnController?, vararg identifiers: String?) : BlazeModule() {
    private val spawnController: SpawnController
    private val identifiers: com.google.common.collect.ImmutableList<String?>

    init {
        com.google.common.base.Preconditions.checkArgument(identifiers.size > 0, "No identifiers given")
        this.spawnController = com.google.common.base.Preconditions.checkNotNull<SpawnController>(spawnController)
        this.identifiers = com.google.common.collect.ImmutableList.copyOf<String?>(identifiers)
    }

    @Throws(AbruptExitException::class)
    public override fun registerSpawnStrategies(
        registryBuilder: SpawnStrategyRegistry.Builder, env: CommandEnvironment?
    ) {
        for (identifier in identifiers) {
            val delegate: SpawnStrategy? = registryBuilder.toStrategy(identifier, javaClass.getSimpleName())
            registryBuilder.registerStrategy(spawnController.wrap(delegate), identifier)
        }
    }
}
