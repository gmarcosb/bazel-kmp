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
package com.google.devtools.common.options

/** Utility functions for global RC files.  */
object GlobalRcUtils {
    /* No global RC files in Bazel, so no global configs. */
    val ALLOWED_GLOBAL_CONFIGS: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>()

    private val GLOBAL_RC_FILES: com.google.common.collect.ImmutableList<String?> =
        com.google.common.collect.ImmutableList.of<String?>( // LINT.IfChange
            "client",  // LINT.ThenChange(//src/main/cpp/option_processor.cc)
            // LINT.IfChange
            "Invocation policy" // LINT.ThenChange(//src/main/java/com/google/devtools/common/options/InvocationPolicyEnforcer.java)
        )

    /** No global RC files in Bazel. Consider "client" options to be global.  */
    @JvmField val IS_GLOBAL_RC_OPTION: java.util.function.Predicate<com.google.devtools.common.options.ParsedOptionDescription?> =
        // LINT.IfChange
        java.util.function.Predicate { option: com.google.devtools.common.options.ParsedOptionDescription? ->
            for (globalRc in com.google.devtools.common.options.GlobalRcUtils.GLOBAL_RC_FILES) {
                // Don't match the full RC file location to be resilient to builds with the same global
                // RC but different workspaces.
                if (option?.getOrigin()?.getSource() != null
                    && option?.getOrigin()?.getSource()?.endsWith(globalRc!!) == true
                ) {
                    return@Predicate true
                }
                if (option?.getExpandedFrom() != null) {
                    if (option?.getExpandedFrom()?.getOrigin()?.getSource() != null
                        && option?.getExpandedFrom()?.getOrigin()?.getSource()?.endsWith(globalRc!!) == true
                    ) {
                        return@Predicate true
                    }
                }
            }
            false
        }

    // LINT.ThenChange(//src/main/cpp/option_processor.cc,
    // //src/main/java/com/google/devtools/common/options/InvocationPolicyEnforcer.java)
    /** Is an rc file path a global rc?  */
    fun isGlobalRcFile(rcFilePath: String): Boolean {
        for (globalRc in com.google.devtools.common.options.GlobalRcUtils.GLOBAL_RC_FILES) {
            if (rcFilePath.endsWith(globalRc!!)) {
                return true
            }
        }
        return false
    }
}
