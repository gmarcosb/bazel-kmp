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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.events.ExtendedEventHandler.Postable

/**
 * A Bazel invocation requested a top-level [BuildConfigurationValue].
 * 
 * @param topLevelConfig the configuration
 * @param testTrimmedTopLevelConfiguration top level configuration trimmed of its [     ]
 */
class TopLevelConfigRequestedEvent(
    topLevelConfig: BuildConfigurationValue?,
    testTrimmedTopLevelConfiguration: BuildOptions?
) : Postable {
    val topLevelConfig: BuildConfigurationValue?
    val testTrimmedTopLevelConfiguration: BuildOptions?

    init {
        this.testTrimmedTopLevelConfiguration = testTrimmedTopLevelConfiguration
        this.topLevelConfig = topLevelConfig
        com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue?>(topLevelConfig)
        com.google.common.base.Preconditions.checkNotNull<BuildOptions?>(testTrimmedTopLevelConfiguration)
    }
}
