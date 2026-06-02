// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.ActionContext

/**
 * Context for actions that do swig include scanning.
 */
@ActionContextMarker(name = "SwigIncludeScanning")
interface SwigIncludeScanningContext : ActionContext {
    /**
     * Scans includes in a .swig file (`source`), placing the results into `includes`.
     * 
     * 
     * Like [IncludeScanner.processAsync], may short-circuit if a skyframe restart is
     * necessary. Callers must check [ ][com.google.devtools.build.skyframe.SkyFunction.Environment.valuesMissing].
     */
    @Throws(IOException::class, ExecException::class, java.lang.InterruptedException::class)
    fun extractSwigIncludes(
        includes: MutableSet<Artifact?>?,
        actionExecutionMetadata: ActionExecutionMetadata?,
        actionExecContext: ActionExecutionContext?,
        source: Artifact?,
        legalOutputPaths: com.google.common.collect.ImmutableSet<Artifact?>?,
        swigIncludePaths: com.google.common.collect.ImmutableList<PathFragment?>?,
        grepIncludes: Artifact?,
        grepIncludesExecutionPlatform: PlatformInfo?
    )
}
