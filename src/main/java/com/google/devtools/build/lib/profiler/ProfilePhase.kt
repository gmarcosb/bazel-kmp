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
package com.google.devtools.build.lib.profiler

/** Build phase markers. Used as a separators between different build phases.  */
@com.google.devtools.build.lib.skybridge.SkybridgeInterface
enum class ProfilePhase(nick: String, description: String) {
    LAUNCH("launch", "Launch Blaze"),
    INIT("init", "Initialize command"),
    TARGET_PATTERN_EVAL("target pattern evaluation", "Evaluate target patterns"),
    ANALYZE("interleaved loading-and-analysis", "Load and analyze dependencies"),
    ANALYZE_AND_EXECUTE(
        "interleaved loading, analysis and execution",
        "Load, analyze dependencies and build artifacts"
    ),
    LICENSE("license checking", "Analyze licenses"),
    PREPARE("preparation", "Prepare for build"),
    EXECUTE("execution", "Build artifacts"),
    FINISH("finish", "Complete build"),
    UNKNOWN("unknown", "unknown");

    /** Short name for the phase  */
    val nick: String?

    /** Human readable description for the phase.  */
    @kotlin.jvm.JvmField
    val description: String

    init {
        this.nick = nick
        this.description = description
    }

    companion object {
        fun getPhaseFromDescription(description: String?): ProfilePhase {
            for (profilePhase in com.google.devtools.build.lib.profiler.ProfilePhase.entries) {
                if (profilePhase.description == description) {
                    return profilePhase
                }
            }
            return com.google.devtools.build.lib.profiler.ProfilePhase.UNKNOWN
        }
    }
}
