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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.UserExecException

/** Container for test target properties available to the TestRunnerAction instance.  */
class TestTargetProperties internal constructor(
    ruleContext: RuleContext,
    executionRequirements: ExecutionInfo?,
    testExecProperties: com.google.common.collect.ImmutableMap<String?, String?>?
) {
    private val size: TestSize
    private val timeout: TestTimeout?
    private val tags: MutableList<String?>?
    private val isRemotable: Boolean
    private val isFlaky: Boolean
    private val isExternal: Boolean
    private val language: String?
    private val executionInfo: com.google.common.collect.ImmutableMap<String?, String?>
    private val testConfiguration: TestConfiguration?

    /**
     * Creates test target properties instance. Constructor expects that it will be called only for
     * test configured targets.
     */
    init {
        val rule: Rule? = ruleContext.getRule()

        com.google.common.base.Preconditions.checkState(TargetUtils.isTestRule(rule))
        size = TestSize.getTestSize(rule)
        timeout = TestTimeout.getTestTimeout(rule)
        tags = ruleContext.attributes().get("tags", Types.STRING_LIST)

        // We need to use method on ruleConfiguredTarget to perform validation.
        isFlaky = ruleContext.attributes().get("flaky", Type.BOOLEAN)
        isExternal = TargetUtils.isExternalTestRule(rule)

        val executionInfo: MutableMap<String?, String?> = LinkedHashMap<String?, String?>()
        executionInfo.putAll(TargetUtils.getExecutionInfo(rule))
        executionInfo.putAll(testExecProperties)

        var incompatibleExclusiveTestSandboxed = false

        testConfiguration = ruleContext.getFragment<T?>(TestConfiguration::class.java)
        if (testConfiguration != null) {
            incompatibleExclusiveTestSandboxed = testConfiguration.incompatibleExclusiveTestSandboxed()
        }

        if (incompatibleExclusiveTestSandboxed) {
            if (TargetUtils.isLocalTestRule(rule)) {
                executionInfo.put(ExecutionRequirements.LOCAL, "")
            } else if (TargetUtils.isExclusiveTestRule(rule)) {
                executionInfo.put(ExecutionRequirements.NO_REMOTE_EXEC, "")
            }
        } else {
            if (TargetUtils.isLocalTestRule(rule) || TargetUtils.isExclusiveTestRule(rule)) {
                executionInfo.put(ExecutionRequirements.LOCAL, "")
            }
        }

        if (TargetUtils.isNoTestloasdTestRule(rule)) {
            executionInfo.put(ExecutionRequirements.LOCAL, "")
            executionInfo.put(ExecutionRequirements.NO_TESTLOASD, "")
        }

        if (executionRequirements != null) {
            // This will overwrite whatever TargetUtils put there, which might be confusing.
            executionInfo.putAll(executionRequirements.getExecutionInfo())
        }
        ruleContext.getConfiguration().modifyExecutionInfo(executionInfo, TestRunnerAction.Companion.MNEMONIC)
        this.executionInfo = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(executionInfo)

        isRemotable =
            !executionInfo.containsKey(ExecutionRequirements.LOCAL) && !executionInfo.containsKey(ExecutionRequirements.NO_REMOTE) && !executionInfo.containsKey(
                ExecutionRequirements.NO_REMOTE_EXEC
            )

        language = TargetUtils.getRuleLanguage(rule)
    }

    fun getSize(): TestSize {
        return size
    }

    fun getTimeout(): TestTimeout? {
        return timeout
    }

    fun getTags(): MutableList<String?>? {
        return tags
    }

    fun isRemotable(): Boolean {
        return isRemotable
    }

    fun isFlaky(): Boolean {
        return isFlaky
    }

    fun isExternal(): Boolean {
        return isExternal
    }

    @Throws(UserExecException::class)
    fun getLocalResourceUsage(label: Label?, usingLocalTestJobs: Boolean): ResourceSet? {
        if (usingLocalTestJobs) {
            return LOCAL_TEST_JOBS_BASED_RESOURCES
        }

        val defaultResources: ResourceSet = getResourceSetFromSize(size)
        val configResources: com.google.common.collect.ImmutableMap<String?, Double?> =
            if (testConfiguration == null) com.google.common.collect.ImmutableMap.of<String?, Double?>() else testConfiguration.getTestResources(
                size
            )
        return defaultResources.withResourceOverrides(configResources)
    }

    /**
     * Returns a map of execution info. See [ ][com.google.devtools.build.lib.actions.Spawn.getExecutionInfo].
     */
    fun getExecutionInfo(): com.google.common.collect.ImmutableMap<String?, String?> {
        return executionInfo
    }

    fun getLanguage(): String? {
        return language
    }

    companion object {
        /**
         * Resources used by local tests of various sizes.
         * 
         * 
         * When changing these values, remember to update the documentation at
         * attributes/test/size.html.
         */
        private val SMALL_RESOURCES: ResourceSet = ResourceSet.Companion.create(20.0, 1.0, 1)

        private val MEDIUM_RESOURCES: ResourceSet = ResourceSet.Companion.create(100.0, 1.0, 1)
        private val LARGE_RESOURCES: ResourceSet = ResourceSet.Companion.create(300.0, 1.0, 1)
        private val ENORMOUS_RESOURCES: ResourceSet = ResourceSet.Companion.create(800.0, 1.0, 1)
        private val LOCAL_TEST_JOBS_BASED_RESOURCES: ResourceSet = ResourceSet.Companion.createWithLocalTestCount(1)

        private fun getResourceSetFromSize(size: TestSize): ResourceSet {
            return when (size) {
                SMALL -> SMALL_RESOURCES
                MEDIUM -> MEDIUM_RESOURCES
                LARGE -> LARGE_RESOURCES
                else -> ENORMOUS_RESOURCES
            }
        }
    }
}
