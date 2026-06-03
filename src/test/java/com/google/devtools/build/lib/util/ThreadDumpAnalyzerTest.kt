// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.ByteArrayInputStream
import java.io.IOException

/** Tests for the [ThreadDumpAnalyzer] class.  */
@RunWith(JUnit4::class)
class ThreadDumpAnalyzerTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analyze_groupsThreadsWithSameStackTrace() {
        val input: String =
            """
        #1 "Thread 1"
            at Test.foo(Test.java:1)
            at Test.bar(Test.java:2)

        #2 "Thread 2"
            at Test.baz(Test.java:1)

        #3 "Thread 3"
            at Test.foo(Test.java:1)
            at Test.bar(Test.java:2)

        

        """.trimIndent()

        val output = analyze(input)

        Truth.assertThat(output)
            .isEqualTo(
                """
            #1 "Thread 1"
            #3 "Thread 3"
                at Test.foo(Test.java:1)
                at Test.bar(Test.java:2)

            #2 "Thread 2"
                at Test.baz(Test.java:1)

            

            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analyze_sortsThreadsByName() {
        val input: String =
            """
        #1 "Thread 4"
            at Test.foo(Test.java:1)
            at Test.bar(Test.java:2)

        #2 "Thread 2"
            at Test.baz(Test.java:1)

        #3 "Thread 3"
            at Test.foo(Test.java:1)
            at Test.bar(Test.java:2)

        

        """.trimIndent()

        val output = analyze(input)

        Truth.assertThat(output)
            .isEqualTo(
                """
            #2 "Thread 2"
                at Test.baz(Test.java:1)

            #3 "Thread 3"
            #1 "Thread 4"
                at Test.foo(Test.java:1)
                at Test.bar(Test.java:2)

            

            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analyze_groupsThreadsWithEmptyStackTrace() {
        val input: String =
            """
        #1 "Thread 1"

        #2 "Thread 2"
            at Test.baz(Test.java:1)

        #3 "Thread 3"

        

        """.trimIndent()

        val output = analyze(input)

        Truth.assertThat(output)
            .isEqualTo(
                """
            #1 "Thread 1"
            #3 "Thread 3"

            #2 "Thread 2"
                at Test.baz(Test.java:1)

            

            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analyze_keepsNonThreadLines() {
        val input: String =
            """
        #1 "Thread 1"

        #2 "Thread 2"

        foo
            bar

        #3 "Thread 3"

        

        """.trimIndent()

        val output = analyze(input)

        Truth.assertThat(output)
            .isEqualTo(
                """
            foo
                bar

            #1 "Thread 1"
            #2 "Thread 2"
            #3 "Thread 3"

            

            """.trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun analyze_groupsThreadsWithSameStackTraceButDifferentStates() {
        val input: String =
            """
        #1 "Thread 1" WAITING
            at Test.foo(Test.java:1)
            - waiting on <Object@1>
            at Test.bar(Test.java:2)

        #2 "Thread 2" RUNNABLE
            at Test.foo(Test.java:1)
            - locked <Object@1>
            at Test.bar(Test.java:2)

        #3 "Thread 3" RUNNABLE
            at Test.baz(Test.java:1)

        

        """.trimIndent()

        val output = analyze(input)

        Truth.assertThat(output)
            .isEqualTo(
                """
            #1 "Thread 1" WAITING
                - waiting on <Object@1>
            #2 "Thread 2" RUNNABLE
                - locked <Object@1>
                at Test.foo(Test.java:1)
                at Test.bar(Test.java:2)

            #3 "Thread 3" RUNNABLE
                at Test.baz(Test.java:1)

            

            """.trimIndent()
            )
    }

    companion object {
        @Throws(IOException::class)
        private fun analyze(input: String): String? {
            val out: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
            val analyzer: ThreadDumpAnalyzer = ThreadDumpAnalyzer()
            analyzer.analyze(ByteArrayInputStream(input.toByteArray(java.nio.charset.StandardCharsets.UTF_8)), out)
            return out.toString(java.nio.charset.StandardCharsets.UTF_8)
        }
    }
}
