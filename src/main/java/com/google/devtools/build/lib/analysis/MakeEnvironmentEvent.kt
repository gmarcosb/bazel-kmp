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
package com.google.devtools.build.lib.analysis

/**
 * This event is fired once the global make environment is available.
 */
class MakeEnvironmentEvent(makeEnv: MutableMap<String?, String?>) {
    private val makeEnvMap: MutableMap<String?, String?>

    /**
     * Construct the event.
     */
    init {
        makeEnvMap = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(makeEnv)
    }

    /**
     * Returns make environment variable names and values as a map.
     */
    fun getMakeEnvMap(): MutableMap<String?, String?> {
        return makeEnvMap
    }
}
