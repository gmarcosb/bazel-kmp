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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionLookupData

/** Creates the workspace status artifacts and action.  */
class WorkspaceStatusFunction internal constructor(workspaceStatusActionFactory: java.util.function.Supplier<WorkspaceStatusAction>) :
    SkyFunction {
    private val workspaceStatusActionFactory: java.util.function.Supplier<WorkspaceStatusAction>

    init {
        this.workspaceStatusActionFactory = workspaceStatusActionFactory
    }

    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: com.google.devtools.build.skyframe.SkyFunction.Environment?): SkyValue? {
        com.google.common.base.Preconditions.checkState(
            WorkspaceStatusValue.Companion.BUILD_INFO_KEY == skyKey, WorkspaceStatusValue.Companion.BUILD_INFO_KEY
        )
        val action: WorkspaceStatusAction = workspaceStatusActionFactory.get()

        val generatingActionKey: ActionLookupData? =
            ActionLookupData.createUnshareable(WorkspaceStatusValue.Companion.BUILD_INFO_KEY, 0)
        for (output in action.getOutputs()) {
            (output as DerivedArtifact).setGeneratingActionKey(generatingActionKey)
        }

        return WorkspaceStatusValue(action)
    }
}
