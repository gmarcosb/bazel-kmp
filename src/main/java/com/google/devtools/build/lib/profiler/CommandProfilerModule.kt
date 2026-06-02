// Copyright 2023 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.profiler.CommandProfilerModule
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import java.util.Locale

/** Bazel module to record a Java Flight Recorder profile for a single command.  */
class CommandProfilerModule : BlazeModule() {
    /** The type of profile to capture.  */
    internal enum class ProfileType {
        CPU,
        WALL,
        ALLOC,
        LOCK;

        override fun toString(): String {
            return name().toLowerCase(Locale.US)
        }
    }

    /** Options converter for --experimental_command_profile.  */
    class ProfileTypeConverter : com.google.devtools.common.options.EnumConverter<ProfileType?>(
        ProfileType::class.java,
        "--experimental_command_profile setting"
    )

    /** CommandProfilerModule options.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @com.google.devtools.common.options.Option(
            name = "experimental_command_profile",
            defaultValue = "null",
            converter = ProfileTypeConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
            help = ("Records a Java Flight Recorder profile for the duration of the command. One of the"
                    + " supported profiling event types (cpu, wall, alloc or lock) must be given as an"
                    + " argument. The profile is written to a file named after the event type under the"
                    + " output base directory."
                    + " The syntax and semantics of this flag might change in the future to support"
                    + " additional profile types or output formats; use at your own risk.")
        )
        abstract fun getProfileType(): ProfileType?
    }

    override fun getCommonCommandOptions(): Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> {
        return com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
            com.google.devtools.build.lib.profiler.CommandProfilerModule.Options::class.java
        )
    }

    private var profileType: ProfileType? = null
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var outputBase: com.google.devtools.build.lib.vfs.Path? = null
    private var outputPath: com.google.devtools.build.lib.vfs.Path? = null

    override fun beforeCommand(env: CommandEnvironment) {
        val options: Options? = env.getOptions()
            .getOptions<Options?>(com.google.devtools.build.lib.profiler.CommandProfilerModule.Options::class.java)
        profileType = options!!.getProfileType()
        outputBase = env.getBlazeWorkspace().getOutputBase()
        reporter = env.getReporter()

        if (profileType == null) {
            // Early exit so we don't attempt to load the JNI unless necessary.
            return
        }

        val profiler: one.profiler.AsyncProfiler? = getProfiler()
        if (profiler == null) {
            return
        }

        outputPath = getProfilerOutputPath(profileType)

        try {
            profiler.execute(getProfilerCommand(profileType, outputPath))
        } catch (e: java.lang.Exception) {
            // This may occur if the user has insufficient privileges to capture performance events.
            reporter.handle(
                com.google.devtools.build.lib.events.Event.error(
                    java.lang.String.format(
                        "Starting JFR %s profile failed: %s",
                        profileType,
                        e
                    )
                )
            )
            profileType = null
        }

        if (profileType != null) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.info(
                    java.lang.String.format(
                        "Writing JFR %s profile to %s",
                        profileType,
                        outputPath
                    )
                )
            )
        }
    }

    override fun afterCommand() {
        if (profileType == null) {
            // Early exit so we don't attempt to load the JNI unless necessary.
            return
        }

        val profiler: one.profiler.AsyncProfiler? = getProfiler()
        if (profiler == null) {
            return
        }

        profiler.stop()

        profileType = null
        outputBase = null
        reporter = null
        outputPath = null
    }

    private fun getProfilerOutputPath(profileType: ProfileType?): com.google.devtools.build.lib.vfs.Path? {
        return outputBase.getChild(profileType.toString() + ".jfr")
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        private val PROFILING_INTERVAL: java.time.Duration = java.time.Duration.ofMillis(10)

        private fun getProfiler(): one.profiler.AsyncProfiler? {
            try {
                return one.profiler.AsyncProfiler.getInstance()
            } catch (t: Throwable) {
                // Loading the JNI must be allowed to fail, as we might be running on an unsupported platform.
                logger.atWarning().withCause(t).log("Failed to load async_profiler JNI")
            }
            return null
        }

        private fun getProfilerCommand(
            profileType: ProfileType?,
            outputPath: com.google.devtools.build.lib.vfs.Path?
        ): String? {
            // See https://github.com/async-profiler/async-profiler/blob/master/src/arguments.cpp.
            return java.lang.String.format(
                "start,event=%s,interval=%s,file=%s,jfr",
                profileType, PROFILING_INTERVAL.toNanos(), outputPath
            )
        }
    }
}
