// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos

/**
 * Utility methods for the build event stream.
 * 
 * 
 * TODO(aehlig): remove once [BlazeTestStatus] is replaced by [TestStatus] from the
 * [BuildEventStreamProtos].
 */
object BuildEventStreamerUtils {
    /** Map BlazeTestStatus to TestStatus.  */
    fun bepStatus(status: BlazeTestStatus): TestStatus {
        when (status) {
            NO_STATUS -> return BuildEventStreamProtos.TestStatus.NO_STATUS
            PASSED -> return BuildEventStreamProtos.TestStatus.PASSED
            FLAKY -> return BuildEventStreamProtos.TestStatus.FLAKY
            FAILED -> return BuildEventStreamProtos.TestStatus.FAILED
            TIMEOUT -> return BuildEventStreamProtos.TestStatus.TIMEOUT
            FAILED_TO_BUILD -> return BuildEventStreamProtos.TestStatus.FAILED_TO_BUILD
            INCOMPLETE -> return BuildEventStreamProtos.TestStatus.INCOMPLETE
            REMOTE_FAILURE -> return BuildEventStreamProtos.TestStatus.REMOTE_FAILURE
            BLAZE_HALTED_BEFORE_TESTING -> return BuildEventStreamProtos.TestStatus.TOOL_HALTED_BEFORE_TESTING
            else ->         // Not used as the above is a complete case distinction; however, by the open
                // nature of protobuf enums, we need the clause to convice java, that we always
                // have a return statement.
                return BuildEventStreamProtos.TestStatus.NO_STATUS
        }
    }
}
