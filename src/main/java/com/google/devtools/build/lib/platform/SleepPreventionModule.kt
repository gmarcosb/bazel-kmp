// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.platform

import com.google.devtools.build.lib.analysis.BlazeDirectories

/** Prevents the computer from going to sleep while a Bazel command is running.  */
class SleepPreventionModule : BlazeModule() {
    private var service: PlatformNativeDepsService? = null

    override fun workspaceInit(
        runtime: BlazeRuntime, directories: BlazeDirectories?, builder: WorkspaceBuilder?
    ) {
        service = com.google.common.base.Preconditions.checkNotNull<PlatformNativeDepsService>(
            runtime.getBlazeService<PlatformNativeDepsService?>(PlatformNativeDepsService::class.java)
        )
    }

    override fun beforeCommand(env: CommandEnvironment?) {
        service.pushDisableSleep()
    }

    override fun afterCommand() {
        service.popDisableSleep()
    }
}
