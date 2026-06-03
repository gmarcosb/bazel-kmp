// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.InconsistentAspectOrderException

/**
 * Exception that signals an error during the evaluation of a configured target dependency.
 * 
 * 
 * If [DependencyEvaluationException.depReportedOwnError]} is true, dependencies are
 * assumed to have reported their own errors. So if configured target P depends on configured target
 * D, and P fails because of a `DependencyEvaluationException` on D, P is responsible for
 * reporting its error details. P should only report what contextualizes P's relationship to D.
 * 
 * 
 * If [DependencyEvaluationException.depReportedOwnError]} is false, P reports both
 * errors in consolidated form as it sees fit. For conceptual simplicity's sake, use this variation
 * sparingly.
 * 
 * 
 * The result is essentially an error reporting stack trace, but presented with user readability
 * in mind.
 */
class DependencyEvaluationException private constructor(
    cause: java.lang.Exception,
    detailedExitCode: DetailedExitCode?,
    location: net.starlark.java.syntax.Location?,
    depReportedOwnError: Boolean
) : java.lang.Exception(cause.getMessage(), cause) {
    /* Null denotes whatever default exit code callers choose. */
    private val detailedExitCode: DetailedExitCode?
    private val location: net.starlark.java.syntax.Location?
    private val depReportedOwnError: Boolean

    init {
        this.detailedExitCode = detailedExitCode
        this.location = location
        this.depReportedOwnError = depReportedOwnError
    }

    constructor(cause: ConfiguredValueCreationException, depReportedOwnError: Boolean) : this(
        cause,
        cause.getDetailedExitCode(),
        cause.getLocation(),
        depReportedOwnError
    )

    constructor(cause: InconsistentAspectOrderException) : this(
        cause,  /*detailedExitCode=*/
        null,
        cause.getLocation(),  /*depReportedOwnError=*/
        false
    )

    constructor(
        cause: EvalException,
        location: net.starlark.java.syntax.Location?
    ) : this(cause,  /* detailedExitCode= */null, location,  /* depReportedOwnError= */false)

    /** Returns the cause's [DetailedExitCode]. If null, the caller should choose a default.  */
    fun getDetailedExitCode(): DetailedExitCode? {
        return detailedExitCode
    }

    fun getLocation(): net.starlark.java.syntax.Location? {
        return location
    }

    fun depReportedOwnError(): Boolean {
        return depReportedOwnError
    }

    @kotlin.jvm.Synchronized
    override fun getCause(): java.lang.Exception? {
        return super.getCause() as java.lang.Exception?
    }
}
