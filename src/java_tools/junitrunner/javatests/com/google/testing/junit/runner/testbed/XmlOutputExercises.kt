// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.testbed

import com.google.common.truth.Truth
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.experimental.runners.Enclosed
import org.junit.runner.RunWith

/**
 * This is a testbed for testing XML output functionality.
 */
@RunWith(Enclosed::class)
class XmlOutputExercises {
    /**
     * A sample test class testing .compareTo()
     */
    class ComparabilityTest {
        private var exampleObject: ExampleObject? = null

        @Before
        @Throws(Exception::class)
        fun setUp() {
            exampleObject = ExampleObject("example")
        }

        @Test
        @Throws(Exception::class)
        fun compareToEqualInstance() {
            val test = ExampleObject("example")
            Truth.assertThat<ExampleObject?>(test).isEquivalentAccordingToCompareTo(exampleObject)
        }

        @Test
        @Throws(Exception::class)
        fun compareToGreaterInstance() {
            val test = ExampleObject("gxample")
            Truth.assertThat<ExampleObject?>(test).isGreaterThan(exampleObject)
        }

        @Test
        @Throws(Exception::class)
        fun compareToLessInstance() {
            val test = ExampleObject("axample")
            Truth.assertThat<ExampleObject?>(test).isLessThan(exampleObject)
        }
    }

    /**
     * A sample test class testing .equals() and .hashCode()
     */
    class EqualsHashCodeTest {
        private var exampleObject: ExampleObject? = null

        @Before
        @Throws(Exception::class)
        fun setUp() {
            exampleObject = ExampleObject("example")
        }

        @Test
        @Throws(Exception::class)
        fun testEquals() {
            Truth.assertThat<ExampleObject?>(ExampleObject("example")).isEqualTo(exampleObject)
            Truth.assertThat<ExampleObject?>(ExampleObject("wrong")).isNotEqualTo(exampleObject)
        }

        @Test
        @Throws(Exception::class)
        fun testHashCode() {
            Truth.assertThat(exampleObject!!.hashCode()).isEqualTo("example".hashCode())
        }
    }

    /**
     * A sample test class testing .toString()
     */
    class OtherTests {
        private var exampleObject: ExampleObject? = null

        @Before
        @Throws(Exception::class)
        fun setUp() {
            exampleObject = ExampleObject("example")
        }

        @Test
        fun testToString() {
            Truth.assertThat(exampleObject.toString()).isEqualTo("example")
        }
    }


    /**
     * A sample test class testing failures
     */
    class FailureTest {
        @Test
        fun testFail() {
            Assert.fail("This is an expected error. The test is supposed to fail.")
        }
    }
}
