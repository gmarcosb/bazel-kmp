// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.skyframe.ParallelBuilderTest
import com.google.devtools.build.lib.skyframe.ParallelBuilderTest.StressTest
import org.junit.runner.RunWith
import org.junit.runners.JUnit4


/**
 * Stress tests for the parallel builder.
 */
@RunWith(JUnit4::class)
class ParallelBuilderStressTest : ParallelBuilderTest() {
    /**
     * A larger set of tests using randomly-generated complex dependency graphs.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRandomStressTest1() {
        val numTrials = 2
        val numArtifacts = 100
        val randomSeed = 43
        val test: StressTest = StressTest(numArtifacts, numTrials, randomSeed)
        test.runStressTest()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRandomStressTest2() {
        val numTrials = 10
        val numArtifacts = 10
        val randomSeed = 44
        val test: StressTest = StressTest(numArtifacts, numTrials, randomSeed)
        test.runStressTest()
    }
}
