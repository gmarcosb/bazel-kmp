// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.profiler

import com.google.common.flogger.GoogleLogger
import com.google.common.flogger.LogSites
import java.util.concurrent.TimeUnit

/** Utility for creating [AutoProfiler] instances from [GoogleLogger] instances.  */
object GoogleAutoProfilerUtils {
    private val selfLogger: GoogleLogger = GoogleLogger.forEnclosingClass()
    private val LOGGING_MESSAGE_TEMPLATE =
        "Spent %d " + com.google.common.base.Ascii.toLowerCase(TimeUnit.MILLISECONDS.toString()) + " doing %s"

    private fun logged(
        description: String?, logger: GoogleLogger, minTimeForLogging: java.time.Duration
    ): com.google.devtools.build.lib.profiler.AutoProfiler {
        return com.google.devtools.build.lib.profiler.AutoProfiler.Companion.create(
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.makeReceiver(
                description,
                logger,
                minTimeForLogging
            )
        )
    }

    fun logged(
        description: String?,
        minTimeForLogging: java.time.Duration
    ): com.google.devtools.build.lib.profiler.AutoProfiler {
        return com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.logged(
            description,
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.selfLogger,
            minTimeForLogging
        )
    }

    @kotlin.jvm.JvmStatic
    fun logged(description: String?): com.google.devtools.build.lib.profiler.AutoProfiler {
        return com.google.devtools.build.lib.profiler.AutoProfiler.Companion.create(
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.createSimpleLogger(
                description,  /* minTimeForLogging= */
                java.time.Duration.ZERO
            )
        )
    }

    private fun createSimpleLogger(
        description: String?, minTimeForLogging: java.time.Duration
    ): com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver {
        return com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver { elapsedTimeNanos: Long ->
            if (elapsedTimeNanos >= minTimeForLogging.toNanos()) {
                com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.log(
                    com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.selfLogger,
                    elapsedTimeNanos,
                    description
                )
            }
        }
    }

    /**
     * Like [.profiledAndLogged] but only logs if the task takes at least
     * `minTimeForLogging`.
     * 
     * 
     * The elapsed time is recorded using [Profiler] even if it is less than `minTimeForLogging`.
     */
    /**
     * Returns an [AutoProfiler] that, when closed, records the elapsed time using [ ] and also logs it (in milliseconds) to the default logger.
     * 
     * 
     * The returned [AutoProfiler] is thread-safe.
     */
    @kotlin.jvm.JvmOverloads
    fun profiledAndLogged(
        taskDescription: String?,
        profilerTaskType: com.google.devtools.build.lib.profiler.ProfilerTask?,
        minTimeForLogging: java.time.Duration = java.time.Duration.ZERO
    ): com.google.devtools.build.lib.profiler.AutoProfiler {
        val profilingReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver =
            com.google.devtools.build.lib.profiler.AutoProfiler.ProfilingElapsedTimeReceiver(
                taskDescription,
                profilerTaskType
            )
        return com.google.devtools.build.lib.profiler.AutoProfiler.Companion.create(
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.SequencedElapsedTimeReceiver(
                profilingReceiver,
                com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.createSimpleLogger(
                    taskDescription,
                    minTimeForLogging
                )
            )
        )
    }

    /**
     * Returns an [AutoProfiler] that, when closed, will log if the operation exceeds provided
     * threshold and call the custom [ElapsedTimeReceiver] for any duration.
     */
    fun loggedAndCustomReceiver(
        taskDescription: String?,
        minTimeForLogging: java.time.Duration,
        customReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver
    ): com.google.devtools.build.lib.profiler.AutoProfiler {
        return com.google.devtools.build.lib.profiler.AutoProfiler.Companion.create(
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.SequencedElapsedTimeReceiver(
                com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.makeReceiver(
                    taskDescription,
                    com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.selfLogger,
                    minTimeForLogging
                ), customReceiver
            )
        )
    }

    private fun makeReceiver(
        description: String?, logger: GoogleLogger, minTimeForLogging: java.time.Duration
    ): com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver {
        return com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.FloggerElapsedTimeReceiver(
            description,
            logger,
            minTimeForLogging
        )
    }

    private fun log(logger: GoogleLogger, elapsedTimeNanos: Long, taskDescription: String?) {
        logger
            .atInfo()
            .withInjectedLogSite(LogSites.callerOf(com.google.devtools.build.lib.profiler.AutoProfiler::class.java))
            .log(
                com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.LOGGING_MESSAGE_TEMPLATE,  // TODO(janakr): confirm that this doesn't show up as a source of garbage. Since it only
                //  happens when we're actually logging, it shouldn't.
                java.time.Duration.ofNanos(elapsedTimeNanos).toMillis(),
                taskDescription
            )
    }

    /** [ElapsedTimeReceiver] that will not log a message if the time elapsed is too small.  */
    private class FloggerElapsedTimeReceiver(
        taskDescription: String?,
        logger: GoogleLogger,
        minTimeForLogging: java.time.Duration
    ) : com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver {
        // Some classes in Google-internal Blaze use a specially configured logger. When those classes
        // record elapsed time using this library, they pass their logger in here, which we use instead
        // of this library's default selfLogger.
        private val logger: GoogleLogger
        private val taskDescription: String?
        private val minTimeForLogging: java.time.Duration

        init {
            this.taskDescription = taskDescription
            this.minTimeForLogging = minTimeForLogging
            this.logger = logger
        }

        override fun accept(elapsedTimeNanos: Long) {
            // We avoid eagerly converting elapsedTimeNanos to a Duration to minimize garbage creation.
            if (elapsedTimeNanos < minTimeForLogging.toNanos()) {
                return
            }
            com.google.devtools.build.lib.profiler.GoogleAutoProfilerUtils.log(
                logger,
                elapsedTimeNanos,
                taskDescription
            )
        }
    }

    private class SequencedElapsedTimeReceiver(
        firstReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver,
        secondReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver
    ) : com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver {
        private val firstReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver
        private val secondReceiver: com.google.devtools.build.lib.profiler.AutoProfiler.ElapsedTimeReceiver

        init {
            this.firstReceiver = firstReceiver
            this.secondReceiver = secondReceiver
        }

        override fun accept(elapsedTimeNanos: Long) {
            firstReceiver.accept(elapsedTimeNanos)
            secondReceiver.accept(elapsedTimeNanos)
        }
    }
}
