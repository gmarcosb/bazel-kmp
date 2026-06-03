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
import com.google.devtools.build.lib.events.AbstractEventHandler
import com.google.devtools.build.lib.events.EventCollector
import com.google.devtools.build.lib.events.EventTestTemplate
import com.google.devtools.build.lib.events.ExtendedEventHandler
import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable
import net.starlark.java.syntax.SyntaxError.location
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests the [Reporter] class.  */
@RunWith(JUnit4::class)
class ReporterTest : EventTestTemplate() {
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var out: java.lang.StringBuilder? = null
    private var outAppender: AbstractEventHandler? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun initializeOutput() {
        reporter = com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        out = java.lang.StringBuilder()
        outAppender =
            object : AbstractEventHandler(com.google.devtools.build.lib.events.EventKind.ERRORS) {
                override fun handle(event: com.google.devtools.build.lib.events.Event) {
                    out.append(event.getMessage())
                }
            }
    }

    @org.junit.Test
    fun reporterShowOutput() {
        reporter.setOutputFilter(com.google.devtools.build.lib.events.OutputFilter.RegexOutputFilter.forRegex("naughty"))
        val collector: EventCollector = EventCollector()
        reporter.addHandler(collector)
        val interesting: com.google.devtools.build.lib.events.Event =
            com.google.devtools.build.lib.events.Event.warn(null, "show-me").withTag("naughty")

        reporter.handle(interesting)
        reporter.handle(com.google.devtools.build.lib.events.Event.warn(null, "ignore-me").withTag("good"))

        Truth.assertThat(
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.events.Event?>(
                interesting
            )
        ).isEqualTo(
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(collector)
        )
    }

    @org.junit.Test
    fun reporterCollectsEvents() {
        val want: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.events.Event?>(
                com.google.devtools.build.lib.events.Event.warn("xyz"),
                com.google.devtools.build.lib.events.Event.error("err")
            )
        val collector: EventCollector = EventCollector()
        reporter.addHandler(collector)
        for (e in want) {
            reporter.handle(e)
        }
        val got: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.events.Event?> =
            com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.events.Event?>(collector)
        Truth.assertThat(want).isEqualTo(got)
    }

    @org.junit.Test
    fun reporterCopyConstructorCopiesHandlersList() {
        reporter.addHandler(outAppender)
        reporter.addHandler(outAppender)
        val copiedReporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter(reporter)
        copiedReporter.addHandler(outAppender) // Should have 3 handlers now.
        reporter.addHandler(outAppender)
        reporter.addHandler(outAppender) // Should have 4 handlers now.
        copiedReporter.handle(com.google.devtools.build.lib.events.Event.error(location, "."))
        Truth.assertThat(out.toString()).isEqualTo("...") // The copied reporter has 3 handlers.
        out = java.lang.StringBuilder()
        reporter.handle(com.google.devtools.build.lib.events.Event.error(location, "."))
        Truth.assertThat(out.toString()).isEqualTo("....") // The old reporter has 4 handlers.
    }

    @org.junit.Test
    fun removeHandlerUndoesAddHandler() {
        Truth.assertThat(out.toString()).isEmpty()
        reporter.addHandler(outAppender)
        reporter.handle(com.google.devtools.build.lib.events.Event.error(location, "Event gets registered."))
        Truth.assertThat(out.toString()).isEqualTo("Event gets registered.")
        out = java.lang.StringBuilder()
        reporter.removeHandler(outAppender)
        reporter.handle(com.google.devtools.build.lib.events.Event.error(location, "Event gets ignored."))
        Truth.assertThat(out.toString()).isEmpty()
    }

    @org.junit.Test
    fun propagatePostCalls() {
        val extendedEventHandler = FakeExtendedEventHandler()
        Truth.assertThat(extendedEventHandler.calledPost).isEqualTo(0)

        reporter.addHandler(extendedEventHandler)
        reporter.post(FakePostable())

        Truth.assertThat(extendedEventHandler.calledPost).isEqualTo(1)
    }

    private class FakeExtendedEventHandler : ExtendedEventHandler {
        var calledPost: Int = 0

        override fun post(obj: Postable?) {
            calledPost++
        }

        override fun handle(event: com.google.devtools.build.lib.events.Event?) {
            throw java.lang.UnsupportedOperationException()
        }
    }

    private class FakePostable : Postable
}
