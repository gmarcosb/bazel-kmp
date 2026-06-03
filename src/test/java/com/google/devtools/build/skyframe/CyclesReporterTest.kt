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
package com.google.devtools.build.skyframe

import com.google.devtools.build.skyframe.CyclesReporter.SingleCycleReporter

@RunWith(JUnit4::class)
class CyclesReporterTest {
    @org.junit.Test
    fun nullEventHandler() {
        val cyclesReporter: CyclesReporter = CyclesReporter()
        try {
            cyclesReporter.reportCycles(com.google.common.collect.ImmutableList.of<CycleInfo?>(), DUMMY_KEY, null)
            Truth.assertThat(false).isTrue()
        } catch (e: java.lang.NullPointerException) {
            // Expected.
        }
    }

    @org.junit.Test
    fun notReportedAssertion() {
        val singleReporter: SingleCycleReporter =
            SingleCycleReporter { topLevelKey, cycleInfo, alreadyReported, eventHandler -> false }

        val cycleInfo: CycleInfo = CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(DUMMY_KEY))
        val cyclesReporter: CyclesReporter = CyclesReporter(singleReporter)
        org.junit.Assert.assertThrows<java.lang.IllegalStateException?>(
            java.lang.IllegalStateException::class.java,
            org.junit.function.ThrowingRunnable {
                cyclesReporter.reportCycles(
                    com.google.common.collect.ImmutableList.of<E?>(cycleInfo), DUMMY_KEY, NullEventHandler.INSTANCE
                )
            })
    }

    @org.junit.Test
    fun smoke() {
        val reported: AtomicBoolean = AtomicBoolean()
        val singleReporter: SingleCycleReporter =
            SingleCycleReporter { topLevelKey, cycleInfo, alreadyReported, eventHandler ->
                reported.set(true)
                true
            }

        val cycleInfo: CycleInfo = CycleInfo.createCycleInfo(com.google.common.collect.ImmutableList.of<E?>(DUMMY_KEY))
        val cyclesReporter: CyclesReporter = CyclesReporter(singleReporter)
        cyclesReporter.reportCycles(
            com.google.common.collect.ImmutableList.of<E?>(cycleInfo),
            DUMMY_KEY,
            NullEventHandler.INSTANCE
        )
        Truth.assertThat(reported.get()).isTrue()
    }

    @org.junit.Test
    fun alreadyReportedCycles() {
        val mockReporter: SingleCycleReporter = Mockito.mock<SingleCycleReporter>(SingleCycleReporter::class.java)
        Mockito.`when`<T?>(
            mockReporter.maybeReportCycle(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.anyBoolean(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(true)
        val cyclesReporter: CyclesReporter = CyclesReporter(mockReporter)
        val top1: SkyKey = SkyKey { SkyFunctionName.createHermetic("top1") }
        val top2: SkyKey = SkyKey { SkyFunctionName.createHermetic("top2") }
        val path1: SkyKey = SkyKey { SkyFunctionName.createHermetic("path1") }
        val path2: SkyKey = SkyKey { SkyFunctionName.createHermetic("path2") }
        val cycle1: SkyKey = SkyKey { SkyFunctionName.createHermetic("cycle1") }
        val cycle2: SkyKey = SkyKey { SkyFunctionName.createHermetic("cycle2") }
        val top1FirstCycle: CycleInfo? =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(top1, path1),
                com.google.common.collect.ImmutableList.of<E?>(cycle1, cycle2)
            )
        cyclesReporter.reportCycles(
            com.google.common.collect.ImmutableList.of<E?>(
                top1FirstCycle,
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top1, path2),
                    com.google.common.collect.ImmutableList.of<E?>(cycle1, cycle2)
                ),
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top1, path1),
                    com.google.common.collect.ImmutableList.of<E?>(cycle2, cycle1)
                ),
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top1, path2),
                    com.google.common.collect.ImmutableList.of<E?>(cycle2, cycle1)
                )
            ),
            top1,
            NullEventHandler.INSTANCE
        )
        Mockito.verify<Any?>(mockReporter)
            .maybeReportCycle(
                top1, top1FirstCycle,  /*alreadyReported=*/false, NullEventHandler.INSTANCE
            )
        // Second cycle is filtered out because it is equivalent but for the path and cycle order.
        Mockito.verifyNoMoreInteractions(mockReporter)

        val top2FirstCycle: CycleInfo? =
            CycleInfo.createCycleInfo(
                com.google.common.collect.ImmutableList.of<E?>(top2, path1),
                com.google.common.collect.ImmutableList.of<E?>(cycle1, cycle2)
            )
        cyclesReporter.reportCycles(
            com.google.common.collect.ImmutableList.of<E?>(
                top2FirstCycle,
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top2, path2),
                    com.google.common.collect.ImmutableList.of<E?>(cycle1, cycle2)
                ),
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top2, path1),
                    com.google.common.collect.ImmutableList.of<E?>(cycle2, cycle1)
                ),
                CycleInfo.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<E?>(top2, path2),
                    com.google.common.collect.ImmutableList.of<E?>(cycle2, cycle1)
                )
            ),
            top2,
            NullEventHandler.INSTANCE
        )

        Mockito.verify<Any?>(mockReporter)
            .maybeReportCycle(
                top2, top2FirstCycle,  /*alreadyReported=*/true, NullEventHandler.INSTANCE
            )
        Mockito.verifyNoMoreInteractions(mockReporter)
    }

    companion object {
        private val DUMMY_KEY: SkyKey = SkyKey { SkyFunctionName.createHermetic("func") }
    }
}
