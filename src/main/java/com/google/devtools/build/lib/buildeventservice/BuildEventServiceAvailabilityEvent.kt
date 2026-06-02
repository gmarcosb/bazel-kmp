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
package com.google.devtools.build.lib.buildeventservice

/** Event fired from [BuildEventServiceUploader].  */
class BuildEventServiceAvailabilityEvent(exitCode: ExitCode?, failureDetail: FailureDetail?) {
    private val exitCode: ExitCode?
    private val failureDetail: FailureDetail?

    init {
        this.exitCode = exitCode
        this.failureDetail = failureDetail
    }

    /**
     * Returns [ExitCode.SUCCESS] if the build event upload was a success, otherwise, return an
     * exit code that corresponds to the error that occurred during the build event upload.
     */
    fun getExitCode(): ExitCode? {
        return exitCode
    }

    /**
     * Returns a failure detail containing the status of the build event that was uploaded to the
     * build event service. This returns null if the upload completed successfully, otherwise, the
     * contents will contain an [ExitCode] and a [BuildProgress.Code].
     */
    fun getFailureDetail(): FailureDetail? {
        return failureDetail
    }

    companion object {
        fun ofSuccess(): BuildEventServiceAvailabilityEvent {
            return BuildEventServiceAvailabilityEvent(ExitCode.SUCCESS, null)
        }
    }
}
