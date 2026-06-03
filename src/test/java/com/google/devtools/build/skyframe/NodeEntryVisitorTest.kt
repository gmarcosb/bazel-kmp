// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor

/** Tests for [NodeEntryVisitor].  */
@RunWith(JUnit4::class)
class NodeEntryVisitorTest {
    @org.junit.Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @org.mockito.Mock
    private val executor: MultiThreadPoolsQuiescingExecutor? = null

    @org.mockito.Mock
    private val receiver: InflightTrackingProgressReceiver? = null

    @org.mockito.Mock
    private val runnableMaker: RunnableMaker? = null

    @org.mockito.Mock
    private val stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>? = null

    @org.junit.Test
    fun enqueueEvaluation_multiThreadPoolsQuiescingExecutor_nonCPUHeavyKey() {
        val nodeEntryVisitor: NodeEntryVisitor =
            NodeEntryVisitor(executor, receiver, runnableMaker, stateCache)
        val nonCPUHeavyKey: SkyKey? = Mockito.mock<SkyKey?>(SkyKey::class.java)

        nodeEntryVisitor.enqueueEvaluation(nonCPUHeavyKey, null)

        Mockito.verify<Any?>(executor)
            .execute(ArgumentMatchers.any<T?>(), < T > eq < T ? > (ThreadPoolType.REGULAR), ArgumentMatchers.anyBoolean())
    }

    @org.junit.Test
    fun enqueueEvaluation_multiThreadPoolsQuiescingExecutor_cpuHeavyKey() {
        val nodeEntryVisitor: NodeEntryVisitor =
            NodeEntryVisitor(executor, receiver, runnableMaker, stateCache)
        val cpuHeavyKey: CPUHeavySkyKey? = Mockito.mock<CPUHeavySkyKey?>(CPUHeavySkyKey::class.java)

        nodeEntryVisitor.enqueueEvaluation(cpuHeavyKey, null)

        Mockito.verify<Any?>(executor)
            .execute(ArgumentMatchers.any<T?>(), < T > eq < T ? > (ThreadPoolType.CPU_HEAVY), ArgumentMatchers.anyBoolean())
    }
}
