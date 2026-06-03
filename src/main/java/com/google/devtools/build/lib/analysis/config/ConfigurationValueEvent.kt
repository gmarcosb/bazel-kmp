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

/** Posted when a [BuildConfigurationValue] is created.  */
class ConfigurationValueEvent(configuration: BuildConfigurationValue?) : Postable {
    val configuration: BuildConfigurationValue?

    init {
        this.configuration = configuration
        java.util.Objects.requireNonNull<BuildConfigurationValue?>(configuration, "configuration")
    }

    companion object {
        fun create(configuration: BuildConfigurationValue?): ConfigurationValueEvent {
            return ConfigurationValueEvent(configuration)
        }
    }
}
