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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.events.EventBusEventHandler

/** Tests [CleanCommand]'s recommendation of the --async flag.  */
@RunWith(org.junit.runners.Parameterized::class)
class CleanCommandRecommendsAsyncTest(
    private val asyncOnCommandLine: Boolean,
    os: com.google.devtools.build.lib.util.OS?,
    expectSuggestion: Boolean
) {
    private val os: com.google.devtools.build.lib.util.OS?
    private val expectSuggestion: Boolean

    init {
        this.os = os
        this.expectSuggestion = expectSuggestion
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCleanProvidesExpectedSuggestion() {
        val reporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter(EventBusEventHandler.createWithNewEventBus())
        val storedEventHandler: StoredEventHandler = StoredEventHandler()
        reporter.addHandler(storedEventHandler)

        val async: Boolean =
            CleanCommand.canUseAsync(this.asyncOnCommandLine,  /* expunge= */false, os, reporter)
        if (os == com.google.devtools.build.lib.util.OS.WINDOWS || os == com.google.devtools.build.lib.util.OS.UNKNOWN) {
            Truth.assertThat(async).isFalse()
        }

        val matches: Boolean =
            storedEventHandler.getEvents().stream()
                .map<String?> { obj: com.google.devtools.build.lib.events.Event? -> obj.getMessage() }
                .anyMatch { event: String? -> event.contains(EXPECTED_SUGGESTION) }
        Truth.assertThat(matches).isEqualTo(expectSuggestion)
    }

    companion object {
        private const val EXPECTED_SUGGESTION = "Use --async"

        @org.junit.runners.Parameterized.Parameters(name = "async={0} on OS {1}")
        fun data(): Iterable<Array<Any?>?> {
            return java.util.Arrays.asList<Array<Any?>?>(
                *arrayOf<Array<Any?>?>(
                    // When --async is provided, don't expect --async to be suggested.
                    arrayOf<Any?>( /* asyncOnCommandLine= */true, com.google.devtools.build.lib.util.OS.LINUX, false),
                    arrayOf<Any?>( /* asyncOnCommandLine= */true, com.google.devtools.build.lib.util.OS.WINDOWS, false),
                    arrayOf<Any?>( /* asyncOnCommandLine= */true, com.google.devtools.build.lib.util.OS.DARWIN, false),
                    arrayOf<Any?>( /* asyncOnCommandLine= */true, com.google.devtools.build.lib.util.OS.FREEBSD, false),
                    arrayOf<Any?>( /* asyncOnCommandLine= */true, com.google.devtools.build.lib.util.OS.OPENBSD, false),
                    arrayOf<Any?>( /* asyncOnCommandLine= */true,
                        com.google.devtools.build.lib.util.OS.UNKNOWN,
                        false
                    ),  // When --async is not provided, expect the suggestion on platforms that support it.

                    arrayOf<Any?>( /* asyncOnCommandLine= */false, com.google.devtools.build.lib.util.OS.LINUX, true),
                    arrayOf<Any?>( /* asyncOnCommandLine= */false,
                        com.google.devtools.build.lib.util.OS.WINDOWS,
                        false
                    ),
                    arrayOf<Any?>( /* asyncOnCommandLine= */false, com.google.devtools.build.lib.util.OS.DARWIN, true),
                    arrayOf<Any?>( /* asyncOnCommandLine= */false, com.google.devtools.build.lib.util.OS.FREEBSD, true),
                    arrayOf<Any?>( /* asyncOnCommandLine= */false, com.google.devtools.build.lib.util.OS.OPENBSD, true),
                    arrayOf<Any?>( /* asyncOnCommandLine= */false,
                        com.google.devtools.build.lib.util.OS.UNKNOWN,
                        false
                    ),
                )
            )
        }
    }
}
