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
package com.google.devtools.build.lib.util

import java.util.HashMap

/**
 * Anything marked FAILURE is generally from a problem with the source code under consideration. In
 * these cases, a re-run in an identical client should produce an identical return code all things
 * being constant.
 * 
 * 
 * Anything marked as an ERROR is generally a problem unrelated to the source code itself. It is
 * either something wrong with the user's command line or the user's machine or environment.
 * 
 * 
 * Note that these exit codes should be kept consistent with the codes returned by Blaze's
 * launcher in //devtools/blaze/main:blaze.cc Blaze exit codes should be consistently classified as
 * permanent vs. transient (i.e. retriable) vs. unknown transient/permanent because users, in
 * particular infrastructure users, will use the exit code to decide whether the request should be
 * retried or not.
 */
@com.google.errorprone.annotations.Immutable
class ExitCode private constructor(
    /**
     * Returns the error's int value.
     */
    @kotlin.jvm.JvmField val numericExitCode: Int, private val name: String,
    /**
     * Returns true if the current exit code represents a failure of Blaze infrastructure,
     * vs. a build failure.
     */
    val isInfrastructureFailure: Boolean, register: Boolean
) {
    /**
     * Whenever a new exit code is created, it is registered (to prevent exit codes with identical
     * numeric codes from being created).  However, there are some exit codes in this file that have
     * duplicate numeric codes, so these are not registered.
     */
    init {
        if (register) {
            register(this)
        }
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(numericExitCode, name, this.isInfrastructureFailure)
    }

    override fun equals(`object`: Any?): Boolean {
        if (`object` is ExitCode) {
            val that = `object`
            return this.numericExitCode == that.numericExitCode && this.name == that.name
                    && this.isInfrastructureFailure == that.isInfrastructureFailure
        }
        return false
    }

    /**
     * Returns the human-readable name for this exit code.  Not guaranteed to be stable, use the
     * numeric exit code for that.
     */
    override fun toString(): String {
        return name
    }

    /**
     * Returns the human-readable name.
     */
    fun name(): String {
        return name
    }

    companion object {
        // Tracks all exit codes defined here and elsewhere in Bazel.
        private val exitCodeRegistry: HashMap<Int?, ExitCode?> = HashMap<Int?, ExitCode?>()

        @kotlin.jvm.JvmField
        val SUCCESS: ExitCode = create(0, "SUCCESS")
        @kotlin.jvm.JvmField
        val BUILD_FAILURE: ExitCode = create(1, "BUILD_FAILURE")
        val PARSING_FAILURE: ExitCode = createUnregistered(1, "PARSING_FAILURE")
        @kotlin.jvm.JvmField
        val COMMAND_LINE_ERROR: ExitCode = create(2, "COMMAND_LINE_ERROR")
        val TESTS_FAILED: ExitCode = create(3, "TESTS_FAILED")
        @kotlin.jvm.JvmField
        val PARTIAL_ANALYSIS_FAILURE: ExitCode = createUnregistered(3, "PARTIAL_ANALYSIS_FAILURE")
        val NO_TESTS_FOUND: ExitCode = create(4, "NO_TESTS_FOUND")
        val RUN_FAILURE: ExitCode = create(6, "RUN_FAILURE")
        @kotlin.jvm.JvmField
        val ANALYSIS_FAILURE: ExitCode = create(7, "ANALYSIS_FAILURE")
        @kotlin.jvm.JvmField
        val INTERRUPTED: ExitCode = create(8, "INTERRUPTED")
        val LOCK_HELD_NOBLOCK_FOR_LOCK: ExitCode = create(9, "LOCK_HELD_NOBLOCK_FOR_LOCK")

        val REMOTE_ENVIRONMENTAL_ERROR: ExitCode = createInfrastructureFailure(32, "REMOTE_ENVIRONMENTAL_ERROR")
        @kotlin.jvm.JvmField
        val OOM_ERROR: ExitCode = createInfrastructureFailure(33, "OOM_ERROR")

        @kotlin.jvm.JvmField
        val REMOTE_ERROR: ExitCode = createInfrastructureFailure(34, "REMOTE_ERROR")
        @kotlin.jvm.JvmField
        val LOCAL_ENVIRONMENTAL_ERROR: ExitCode = createInfrastructureFailure(36, "LOCAL_ENVIRONMENTAL_ERROR")
        @kotlin.jvm.JvmField
        val BLAZE_INTERNAL_ERROR: ExitCode = createInfrastructureFailure(37, "BLAZE_INTERNAL_ERROR")
        @kotlin.jvm.JvmField
        val TRANSIENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR: ExitCode = createInfrastructureFailure(38, "PUBLISH_ERROR")
        val REMOTE_CACHE_EVICTED: ExitCode = createInfrastructureFailure(39, "REMOTE_CACHE_EVICTED")
        @kotlin.jvm.JvmField
        val PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR: ExitCode =
            create(45, "PERSISTENT_BUILD_EVENT_SERVICE_UPLOAD_ERROR")
        val EXTERNAL_DEPS_ERROR: ExitCode = create(48, "EXTERNAL_DEPS_ERROR")

        /**
         * Creates and returns an ExitCode.  Requires a unique exit code number.
         * 
         * @param code the int value for this exit code
         * @param name a human-readable description
         */
        fun create(code: Int, name: String): ExitCode {
            return ExitCode(code, name,  /*infrastructureFailure=*/false,  /*register=*/true)
        }

        /**
         * Creates and returns an ExitCode that represents an infrastructure failure.
         * 
         * @param code the int value for this exit code
         * @param name a human-readable description
         */
        fun createInfrastructureFailure(code: Int, name: String): ExitCode {
            return ExitCode(code, name,  /*infrastructureFailure=*/true,  /*register=*/true)
        }

        /**
         * Creates and returns an ExitCode that has the same numeric code as another ExitCode. This is to
         * allow the duplicate error codes listed above to be registered, but is private to prevent other
         * users from creating duplicate error codes in the future.
         * 
         * @param code the int value for this exit code
         * @param name a human-readable description
         */
        private fun createUnregistered(code: Int, name: String): ExitCode {
            return ExitCode(code, name,  /*infrastructureFailure=*/false,  /*register=*/false)
        }

        /**
         * Add the given exit code to the registry.
         * 
         * @param exitCode the exit code to register
         * @throws IllegalStateException if the numeric exit code is already in the registry.
         */
        private fun register(exitCode: ExitCode) {
            synchronized(exitCodeRegistry) {
                val codeNum = exitCode.numericExitCode
                check(!exitCodeRegistry.containsKey(codeNum)) { "Exit code " + codeNum + " (" + exitCode.name + ") already registered" }
                exitCodeRegistry.put(codeNum, exitCode)
            }
        }

        /**
         * Returns all registered ExitCodes.
         */
        fun values(): MutableCollection<ExitCode?> {
            synchronized(exitCodeRegistry) {
                return exitCodeRegistry.values()
            }
        }

        /**
         * Returns a registered [ExitCode] with the given `code`.
         * 
         * 
         * Note that there *are* unregistered ExitCodes. This will never return them.
         */
        fun forCode(code: Int): ExitCode? {
            synchronized(exitCodeRegistry) {
                return exitCodeRegistry.get(code)
            }
        }
    }
}
