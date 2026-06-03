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
package com.google.devtools.build.lib.events

import com.google.common.truth.Truth
import com.google.devtools.build.lib.events.ReporterStream
import com.google.devtools.build.lib.testutil.MoreAsserts
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.PrintWriter

@RunWith(JUnit4::class)
class ReporterStreamTest {
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var out: java.lang.StringBuilder? = null
    private var outAppender: com.google.devtools.build.lib.events.EventHandler? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createOutputAppender() {
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        out = java.lang.StringBuilder()
        outAppender =
            object : com.google.devtools.build.lib.events.EventHandler {
                override fun handle(event: com.google.devtools.build.lib.events.Event) {
                    out.append("[" + event.getKind() + ": " + event.getMessage() + "]\n")
                }
            }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun reporterStream() {
        Truth.assertThat(out.toString()).isEmpty()
        reporter.addHandler(outAppender)
        PrintWriter(
            ReporterStream(reporter, com.google.devtools.build.lib.events.EventKind.WARNING),
            true
        ).use { warnWriter ->
            PrintWriter(
                ReporterStream(reporter, com.google.devtools.build.lib.events.EventKind.INFO),
                true
            ).use { infoWriter ->
                infoWriter.println("some info")
                warnWriter.println("a warning")
            }
        }
        reporter.getOutErr().printOutLn("some output")
        reporter.getOutErr().printErrLn("an error")
        MoreAsserts.assertEqualsUnifyingLineEnds(
            ("[INFO: some info\n]\n"
                    + "[WARNING: a warning\n]\n"
                    + "[STDOUT: some output\n]\n"
                    + "[STDERR: an error\n]\n"),
            out.toString()
        )
    }
}
