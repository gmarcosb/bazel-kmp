// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.rewinding

import com.google.devtools.build.lib.server.FailureDetails.ActionRewinding

/** Exception thrown by [ActionRewindStrategy] when it cannot compute a rewind plan.  */
abstract class ActionRewindException internal constructor(message: String?) : java.lang.Exception(message),
    DetailedException {
    internal class GenericActionRewindException(message: String?, code: ActionRewinding.Code?) :
        ActionRewindException(message) {
        private val code: ActionRewinding.Code?

        init {
            this.code = code
        }

        val detailedExitCode: DetailedExitCode
            get() = DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(getMessage())
                    .setActionRewinding(ActionRewinding.newBuilder().setCode(code))
                    .build()
            )
    }

    internal class FallbackToBuildRewindingException(message: String?) : ActionRewindException(message) {
        val detailedExitCode: DetailedExitCode
            get() = DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(getMessage())
                    .setSpawn(Spawn.newBuilder().setCode(Spawn.Code.REMOTE_CACHE_EVICTED))
                    .build()
            )
    }
}
