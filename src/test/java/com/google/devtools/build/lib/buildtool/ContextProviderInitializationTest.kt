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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.ActionGraph

/**
 * Test to make sure that context provider initialization failure is handled correctly.
 */
@RunWith(JUnit4::class)
class ContextProviderInitializationTest : BuildIntegrationTestCase() {
    private class BadContextProviderModule : BlazeModule() {
        public override fun executorInit(
            env: CommandEnvironment?, request: BuildRequest?, builder: ExecutorBuilder
        ) {
            builder.addExecutorLifecycleListener(
                object : ExecutorLifecycleListener() {
                    public override fun executorCreated() {}

                    @Throws(AbruptExitException::class)
                    public override fun executionPhaseStarting(
                        actionGraph: ActionGraph?,
                        topLevelArtifacts: java.util.function.Supplier<com.google.common.collect.ImmutableSet<Artifact?>?>?,
                        unused: EphemeralCheckIfOutputConsumed?
                    ) {
                        throw AbruptExitException(
                            DetailedExitCode.of(
                                FailureDetail.newBuilder()
                                    .setMessage("eek")
                                    .setCrash(Crash.newBuilder().setCode(Code.CRASH_UNKNOWN))
                                    .build()
                            )
                        )
                    }

                    public override fun executionPhaseEnding() {}
                })
        }
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.getRuntimeBuilder()
            .addBlazeModule(BadContextProviderModule())

    @org.junit.Test
    fun testContextProviderInitializationFailure() {
        org.junit.Assert.assertThrows<T?>(
            AbruptExitException::class.java,
            org.junit.function.ThrowingRunnable { this.buildTarget() })
    }
}
