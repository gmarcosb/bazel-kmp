// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.test

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.actions.ActionInput
import org.junit.Test

/** Tests for [TestTargetProperties].  */
@RunWith(JUnit4::class)
class TestTargetPropertiesTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testTestWithCpusTagHasCorrectLocalResourcesEstimate() {
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = ["cpu:4"],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        val localResourceUsage: ResourceSet = createTestSpawn(testAction).getLocalResources()
        assertThat(localResourceUsage.getCpuUsage()).isEqualTo(4.0)
    }

    @Test
    @Throws(Exception::class)
    fun testTestResourcesFlag() {
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "medium",
            srcs = ["test.sh"],
            tags = ["resources:gpu:4"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--default_test_resources=memory=10,20,30,40",
            "--default_test_resources=cpu=1,2,3,4",
            "--default_test_resources=gpu=1",
            "--default_test_resources=cpu=5"
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        val localResourceUsage: ResourceSet = createTestSpawn(testAction).getLocalResources()
        // Tags-specified resources overrides --default_test_resources=gpu.
        assertThat(localResourceUsage.getResources()).containsEntry("gpu", 4.0)
        // The last-specified value of --default_test_resources=cpu is used.
        assertThat(localResourceUsage.getCpuUsage()).isEqualTo(5.0)
        assertThat(localResourceUsage.getMemoryMb()).isEqualTo(20)
    }

    @Test
    @Throws(Exception::class)
    fun testTestWithExclusiveRunLocallyByDefault() {
        useConfiguration("--noincompatible_exclusive_test_sandboxed")
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = ["exclusive"],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        assertThat(testAction.getExecutionInfo()).containsKey(ExecutionRequirements.LOCAL)
        assertThat(testAction.getExecutionInfo()).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun testTestWithExclusiveIfRunLocally_notTaggedLocal() {
        useConfiguration("--noincompatible_exclusive_test_sandboxed")
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = ["exclusive-if-local"],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        // "exclusive" tests become local when TestTargetProperties adds local executionInfo.
        // Ensure this is not the case for "exclusive-if-local"
        assertThat(testAction.getExecutionInfo()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testTestWithExclusiveDisablesRemoteExecution() {
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = ["exclusive"],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        assertThat(testAction.getExecutionInfo()).containsKey(ExecutionRequirements.NO_REMOTE_EXEC)
        assertThat(testAction.getExecutionInfo()).hasSize(1)
    }

    @Test
    @Throws(Exception::class)
    fun testTestWithExclusiveIfRunLocally_notTaggedNoRemote() {
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = ["exclusive-if-local"],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        // "exclusive" tests become local when TestTargetProperties adds a no-remote-exec requirement
        // to the execution info. Ensure this is not the case for "exclusive-if-local"
        assertThat(testAction.getExecutionInfo()).isEmpty()
    }

    @Test
    @Throws(Exception::class)
    fun testTestWithExclusiveAndLocalRunLocally() {
        useConfiguration("--incompatible_exclusive_test_sandboxed")
        scratch.file("tests/test.sh", "#!/bin/bash", "exit 0")
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(
            name = "test",
            size = "small",
            srcs = ["test.sh"],
            tags = [
                "exclusive",
                "local",
            ],
        )
        
        """.trimIndent()
        )
        val testTarget: ConfiguredTarget = getConfiguredTarget("//tests:test")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(testTarget).get(0)) as TestRunnerAction
        assertThat(testAction.getExecutionInfo()).containsKey(ExecutionRequirements.LOCAL)
        assertThat(testAction.getExecutionInfo()).hasSize(1)
    }

    companion object {
        /** Creates a spawn from a test action, mirroring what StandaloneTestStrategy does.  */
        private fun createTestSpawn(action: TestRunnerAction): SimpleSpawn {
            val outputs: ImmutableList<ActionInput?> = ImmutableList.copyOf(action.getSpawnOutputs())
            return SimpleSpawn(
                action,  /* arguments= */
                ImmutableList.of<E?>(),  /* environment= */
                ImmutableMap.of<K?, V?>(),  /* executionInfo= */
                action.getExecutionInfo(),  /* inputs= */
                action.getInputs(),  /* tools= */
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                outputs,  /* mandatoryOutputs= */
                null,
                { action.getTestProperties().getLocalResourceUsage(action.getOwner().getLabel(), false) })
        }
    }
}
