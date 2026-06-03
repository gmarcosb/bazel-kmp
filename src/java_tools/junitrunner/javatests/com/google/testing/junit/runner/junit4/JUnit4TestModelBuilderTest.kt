// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.common.truth.Truth
import com.google.devtools.build.docgen.annot.GlobalMethods.Environment.getDescription
import com.google.testing.junit.runner.internal.junit4.MemoizingRequest.getRunner
import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.junit4.JUnit4Runner.model
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import com.google.testing.junit.runner.model.AntXmlResultWriter
import com.google.testing.junit.runner.model.TestNode
import com.google.testing.junit.runner.model.TestNode.children
import com.google.testing.junit.runner.model.TestNode.description
import com.google.testing.junit.runner.model.TestSuiteModel
import com.google.testing.junit.runner.model.TestSuiteModel.getNumTestCases
import com.google.testing.junit.runner.model.TestSuiteModel.getTopLevelTestSuites
import com.google.testing.junit.runner.model.TestSuiteNode.getChildren
import com.google.testing.junit.runner.model.XmlResultWriter
import com.google.testing.junit.runner.sharding.ShardingEnvironment
import com.google.testing.junit.runner.sharding.ShardingFilters
import com.google.testing.junit.runner.util.FakeTestClock
import com.google.testing.junit.runner.util.TestClock
import org.junit.Ignore
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses
import org.mockito.Mockito

/**
 * Tests for `JUnit4TestModelBuilder`
 */
@RunWith(JUnit4::class)
class JUnit4TestModelBuilderTest {
    private val fakeTestClock: TestClock = FakeTestClock()
    private val stubShardingEnvironment: ShardingEnvironment =
        com.google.testing.junit.runner.sharding.testing.StubShardingEnvironment()
    private val xmlResultWriter: XmlResultWriter = AntXmlResultWriter()

    private fun builder(
        request: org.junit.runner.Request, suiteName: String?,
        shardingEnvironment: ShardingEnvironment, shardingFilters: ShardingFilters,
        xmlResultWriter: XmlResultWriter
    ): JUnit4TestModelBuilder {
        return JUnit4TestModelBuilder(
            request,
            suiteName,
            com.google.testing.junit.runner.model.TestSuiteModel.Builder(
                fakeTestClock, shardingFilters, shardingEnvironment, xmlResultWriter
            )
        )
    }

    @org.junit.Test
    fun testTouchesShardFileWhenShardingEnabled() {
        val testClass: java.lang.Class<*> = SampleTestCaseWithTwoTests::class.java
        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(testClass)
        val mockShardingEnvironment: ShardingEnvironment =
            Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        val shardingFilters: ShardingFilters = ShardingFilters(
            mockShardingEnvironment, ShardingFilters.Companion.DEFAULT_SHARDING_STRATEGY
        )
        val modelBuilder: JUnit4TestModelBuilder = builder(
            request, testClass.getCanonicalName(), mockShardingEnvironment, shardingFilters,
            xmlResultWriter
        )

        Mockito.`when`<Boolean?>(mockShardingEnvironment.isShardingEnabled()).thenReturn(true)
        Mockito.`when`<Int?>(mockShardingEnvironment.getTotalShards()).thenReturn(2)
        modelBuilder.get()

        Mockito.verify<ShardingEnvironment?>(mockShardingEnvironment).touchShardFile()
    }

    @org.junit.Test
    fun testDoesNotTouchShardFileWhenShardingDisabled() {
        val testClass: java.lang.Class<*> = SampleTestCaseWithTwoTests::class.java
        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(testClass)
        val mockShardingEnvironment: ShardingEnvironment =
            Mockito.mock<ShardingEnvironment>(ShardingEnvironment::class.java)
        val shardingFilters: ShardingFilters = ShardingFilters(
            mockShardingEnvironment, ShardingFilters.Companion.DEFAULT_SHARDING_STRATEGY
        )
        val modelBuilder: JUnit4TestModelBuilder = builder(
            request, testClass.getCanonicalName(), mockShardingEnvironment, shardingFilters,
            xmlResultWriter
        )

        Mockito.`when`<Boolean?>(mockShardingEnvironment.isShardingEnabled()).thenReturn(false)
        modelBuilder.get()

        Mockito.verify<ShardingEnvironment?>(mockShardingEnvironment, Mockito.never()).touchShardFile()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateModel_topLevelIgnore() {
        val testClass: java.lang.Class<*> = SampleTestCaseWithTopLevelIgnore::class.java
        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(testClass)
        val testClassName: String = testClass.getCanonicalName()
        val modelBuilder: JUnit4TestModelBuilder =
            builder(request, testClassName, stubShardingEnvironment, null, xmlResultWriter)

        val testSuiteModel: TestSuiteModel? = modelBuilder.get()
        Truth.assertThat(testSuiteModel.getNumTestCases()).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateModel_singleTestClass() {
        val testClass: java.lang.Class<*> = SampleTestCaseWithTwoTests::class.java
        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(testClass)
        val testClassName: String = testClass.getCanonicalName()
        val modelBuilder: JUnit4TestModelBuilder = builder(
            request, testClassName, stubShardingEnvironment, null, xmlResultWriter
        )

        val suite: org.junit.runner.Description = request.getRunner().getDescription()
        val testOne: org.junit.runner.Description? = suite.getChildren().get(0)
        val testTwo: org.junit.runner.Description? = suite.getChildren().get(1)

        val model: TestSuiteModel? = modelBuilder.get()
        val suiteNode: TestNode? =
            com.google.common.collect.Iterables.getOnlyElement<TestNode?>(model.getTopLevelTestSuites())
        Truth.assertThat(suiteNode.description).isEqualTo(suite)
        val testCases: MutableList<TestNode>? = suiteNode.children
        Truth.assertThat(testCases).hasSize(2)
        val testOneNode: TestNode = testCases!!.get(0)
        val testTwoNode: TestNode = testCases.get(1)
        Truth.assertThat(testOneNode.description).isEqualTo(testOne)
        Truth.assertThat(testTwoNode.description).isEqualTo(testTwo)
        Truth.assertThat(testOneNode.children).isEmpty()
        Truth.assertThat(testTwoNode.children).isEmpty()
        Truth.assertThat(model.getNumTestCases()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateModel_simpleSuite() {
        val suiteClass: java.lang.Class<*> =
            com.google.testing.junit.runner.junit4.JUnit4TestModelBuilderTest.SampleSuite::class.java
        val request: org.junit.runner.Request = org.junit.runner.Request.classWithoutSuiteMethod(suiteClass)
        val suiteClassName: String = suiteClass.getCanonicalName()
        val modelBuilder: JUnit4TestModelBuilder = builder(
            request, suiteClassName, stubShardingEnvironment, null, xmlResultWriter
        )

        val topSuite: org.junit.runner.Description = request.getRunner().getDescription()
        val innerSuite: org.junit.runner.Description = topSuite.getChildren().get(0)
        val testOne: org.junit.runner.Description? = innerSuite.getChildren().get(0)

        val model: TestSuiteModel? = modelBuilder.get()
        val topSuiteNode: TestNode? =
            com.google.common.collect.Iterables.getOnlyElement<TestNode?>(model.getTopLevelTestSuites())
        Truth.assertThat(topSuiteNode.description).isEqualTo(topSuite)
        val innerSuiteNode: TestNode? =
            com.google.common.collect.Iterables.getOnlyElement<TestNode?>(topSuiteNode.children)
        Truth.assertThat(innerSuiteNode.description).isEqualTo(innerSuite)
        val testOneNode: TestNode? =
            com.google.common.collect.Iterables.getOnlyElement<TestNode?>(innerSuiteNode.children)
        Truth.assertThat(testOneNode.description).isEqualTo(testOne)
        Truth.assertThat(testOneNode.children).isEmpty()
        Truth.assertThat(model.getNumTestCases()).isEqualTo(1)
    }

    /** Sample test case with two tests.  */
    @RunWith(JUnit4::class)
    class SampleTestCaseWithTwoTests {
        @org.junit.Test
        fun testOne() {
        }

        @org.junit.Test
        fun testTwo() {
        }
    }

    /** Sample test case with top level @Ignore  */
    @Ignore
    @RunWith(JUnit4::class)
    class SampleTestCaseWithTopLevelIgnore {
        @org.junit.Test
        fun testOne() {
        }

        @org.junit.Test
        fun testTwo() {
        }
    }

    /** Sample test case with one test.  */
    @RunWith(JUnit4::class)
    class SampleTestCaseWithOneTest {
        @org.junit.Test
        fun testOne() {
        }
    }

    /** Sample suite with one test.  */
    @RunWith(Suite::class)
    @SuiteClasses(SampleTestCaseWithOneTest::class)
    class SampleSuite
}
