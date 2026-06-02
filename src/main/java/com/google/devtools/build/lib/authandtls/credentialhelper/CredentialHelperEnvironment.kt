// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.authandtls.credentialhelper

import com.google.auto.value.AutoBuilder

/**
 * Environment for running [CredentialHelper]s in.
 * 
 * @param eventReporter Returns the reporter for reporting events related to [     ]s.
 * @param workspacePath Returns the absolute path to the workspace, or null if Bazel was invoked
 * outside a workspace.
 * 
 * If available, it will be used as the working directory when invoking the helper
 * subprocess. Otherwise, the working directory is inherited from the Bazel server process.
 * @param clientEnvironment Returns the environment from the Bazel client.
 * 
 * Passed as environment variables to the subprocess.
 * @param helperExecutionTimeout Returns the execution timeout for the helper subprocess.
 */
class CredentialHelperEnvironment(
    eventReporter: com.google.devtools.build.lib.events.Reporter?,
    workspacePath: com.google.devtools.build.lib.vfs.Path?,
    clientEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?,
    helperExecutionTimeout: java.time.Duration?
) {
    /** Builder for [CredentialHelperEnvironment].  */
    @AutoBuilder
    abstract class Builder {
        /** Sets the reporter for reporting events related to [CredentialHelper]s.  */
        abstract fun setEventReporter(reporter: com.google.devtools.build.lib.events.Reporter?): Builder?

        /**
         * Sets the absolute path to the workspace, or null if Bazel was invoked outside a workspace.
         */
        abstract fun setWorkspacePath(path: com.google.devtools.build.lib.vfs.Path?): Builder?

        /**
         * Sets the environment from the Bazel client to pass as environment variables to the
         * subprocess.
         */
        abstract fun setClientEnvironment(environment: com.google.common.collect.ImmutableMap<String?, String?>?): Builder?

        /** Sets the execution timeout for the helper subprocess.  */
        abstract fun setHelperExecutionTimeout(timeout: java.time.Duration?): Builder?

        /** Returns the newly constructed [CredentialHelperEnvironment].  */
        abstract fun build(): CredentialHelperEnvironment?
    }

    val eventReporter: com.google.devtools.build.lib.events.Reporter?
    val workspacePath: com.google.devtools.build.lib.vfs.Path?
    val clientEnvironment: com.google.common.collect.ImmutableMap<String?, String?>?
    val helperExecutionTimeout: java.time.Duration?

    init {
        this.helperExecutionTimeout = helperExecutionTimeout
        this.clientEnvironment = clientEnvironment
        this.workspacePath = workspacePath
        this.eventReporter = eventReporter
        Reporter > java.util.Objects.requireNonNull<com.google.devtools.build.lib.events.Reporter?>(
            eventReporter,
            "eventReporter"
        )
        java.util.Objects.requireNonNull<com.google.common.collect.ImmutableMap<String?, String?>?>(
            clientEnvironment,
            "clientEnvironment"
        )
        Duration > java.util.Objects.requireNonNull<java.time.Duration?>(
            helperExecutionTimeout,
            "helperExecutionTimeout"
        )
    }

    companion object {
        /** Returns a new builder for [CredentialHelperEnvironment].  */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return AutoBuilder_CredentialHelperEnvironment_Builder()
        }
    }
}
