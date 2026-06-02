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

import java.util.EnumSet

/** Indicates the kind of an [Event].  */
enum class EventKind {
    /**
     * An unrecoverable error resulting in abrupt termination of the server.
     * 
     * 
     * Events of this kind may be crash bugs (i.e. [RuntimeException]) or other errors such
     * [OutOfMemoryError]. The only time such an event should be emitted is just prior to
     * crashing. It should be the final event presented to the user before terminating.
     */
    FATAL,

    /**
     * For errors that will prevent a successful, correct build. In general, the build tool will not
     * attempt to start or continue a build if an error is encountered (though the behavior specified
     * by `--keep_going` is a counterexample).
     * 
     * 
     * Errors of a more severe nature in the input, such as those which might cause later passes of
     * the analysis to fail catastrophically, should be handled by throwing an exception.
     */
    ERROR,

    /** For warnings of minor problems that do not affect the integrity of a build.  */
    WARNING,

    /**
     * For atemporal information that is true throughout the entire duration of a build (e.g. the
     * number of targets found).
     */
    INFO,

    /**
     * For temporal information that changes during the duration of a build (e.g. what action is
     * executing now).
     */
    PROGRESS,

    /** For debug messages.  */
    DEBUG,  // For progress messages (temporal information) relating to the start and end of particular tasks
    // (e.g. "Loading package foo", "Compiling bar", etc.).

    /** Beginning of a temporal task.  */
    START,

    /** End of a temporal task.  */
    FINISH,

    /** For command lines of subcommands executed by the build tool (like make-dbg "-v").  */
    SUBCOMMAND,  // Output to stdout/stderr from subprocesses.

    /** Output to stdout from subprocess.  */
    STDOUT,

    /** Output to stderr from subprocess.  */
    STDERR,  // Test result messages (similar to the INFO and ERROR, but test-specific)

    /** Test passed.  */
    PASS,

    /** Test failed.  */
    FAIL,

    /** Test timed out.  */
    TIMEOUT,

    /** Test cancelled.  */
    CANCELLED,

    /** For the reasoning of the dependency checker (like GNU Make "-d").  */
    DEPCHECKER;

    companion object {
        @kotlin.jvm.JvmField
        val ALL_EVENTS: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(EnumSet.allOf<EventKind?>(com.google.devtools.build.lib.events.EventKind::class.java))

        val OUTPUT: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.STDOUT,
                com.google.devtools.build.lib.events.EventKind.STDERR
            )

        @kotlin.jvm.JvmField
        val ERRORS: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                com.google.devtools.build.lib.events.EventKind.ERROR,
                com.google.devtools.build.lib.events.EventKind.FAIL,
                com.google.devtools.build.lib.events.EventKind.TIMEOUT
            )

        @kotlin.jvm.JvmField
        val ERRORS_AND_WARNINGS: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                com.google.devtools.build.lib.events.EventKind.ERROR,
                com.google.devtools.build.lib.events.EventKind.WARNING,
                com.google.devtools.build.lib.events.EventKind.DEBUG,
                com.google.devtools.build.lib.events.EventKind.FAIL,
                com.google.devtools.build.lib.events.EventKind.TIMEOUT
            )

        @kotlin.jvm.JvmField
        val ERRORS_WARNINGS_AND_INFO: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                com.google.devtools.build.lib.events.EventKind.ERROR,
                com.google.devtools.build.lib.events.EventKind.WARNING,
                com.google.devtools.build.lib.events.EventKind.DEBUG,
                com.google.devtools.build.lib.events.EventKind.PASS,
                com.google.devtools.build.lib.events.EventKind.FAIL,
                com.google.devtools.build.lib.events.EventKind.TIMEOUT,
                com.google.devtools.build.lib.events.EventKind.INFO
            )

        val ERRORS_AND_OUTPUT: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                com.google.devtools.build.lib.events.EventKind.ERROR,
                com.google.devtools.build.lib.events.EventKind.FAIL,
                com.google.devtools.build.lib.events.EventKind.TIMEOUT,
                com.google.devtools.build.lib.events.EventKind.STDOUT,
                com.google.devtools.build.lib.events.EventKind.STDERR
            )

        @kotlin.jvm.JvmField
        val ERRORS_AND_WARNINGS_AND_OUTPUT: com.google.common.collect.ImmutableSet<EventKind?> =
            com.google.common.collect.Sets.immutableEnumSet<EventKind?>(
                com.google.devtools.build.lib.events.EventKind.FATAL,
                com.google.devtools.build.lib.events.EventKind.ERROR,
                com.google.devtools.build.lib.events.EventKind.WARNING,
                com.google.devtools.build.lib.events.EventKind.DEBUG,
                com.google.devtools.build.lib.events.EventKind.FAIL,
                com.google.devtools.build.lib.events.EventKind.TIMEOUT,
                com.google.devtools.build.lib.events.EventKind.STDOUT,
                com.google.devtools.build.lib.events.EventKind.STDERR
            )
    }
}
