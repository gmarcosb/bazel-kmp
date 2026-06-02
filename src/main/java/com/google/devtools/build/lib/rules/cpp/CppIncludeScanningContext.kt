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
 * Context for include scanning.
 */
@ActionContextMarker(name = "IncludeScanning")
interface CppIncludeScanningContext : ActionContext {
    /**
     * Does include scanning to find the list of files needed to execute the action.
     * 
     * 
     * Returns `null` if a skyframe restart is necessary.
     */
    @Throws(ExecException::class, java.lang.InterruptedException::class, ActionExecutionException::class)
    fun findAdditionalInputs(
        action: CppCompileAction?,
        actionExecutionContext: ActionExecutionContext?,
        includeScanningHeaderData: IncludeScanningHeaderData?
    ): MutableList<Artifact?>?
}
