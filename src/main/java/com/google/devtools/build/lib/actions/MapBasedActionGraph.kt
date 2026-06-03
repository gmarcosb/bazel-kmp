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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.lib.actions.ActionAnalysisMetadata
import com.google.devtools.build.lib.actions.ActionConflictException
import com.google.devtools.build.lib.actions.ActionKeyContext
import com.google.devtools.build.lib.actions.Artifact
import com.google.devtools.build.lib.actions.Artifact.OwnerlessArtifactWrapper
import com.google.devtools.build.lib.actions.MutableActionGraph
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** An action graph that resolves generating actions by looking them up in a map.  */
@javax.annotation.concurrent.ThreadSafe
class MapBasedActionGraph @kotlin.jvm.JvmOverloads constructor(
    actionKeyContext: ActionKeyContext?,
    sizeHint: Int = 16
) : MutableActionGraph {
    private val actionKeyContext: ActionKeyContext?
    private val generatingActionMap: ConcurrentMap<OwnerlessArtifactWrapper?, ActionAnalysisMetadata?>

    init {
        this.actionKeyContext = actionKeyContext
        this.generatingActionMap = ConcurrentHashMap<OwnerlessArtifactWrapper?, ActionAnalysisMetadata?>(sizeHint)
    }

    override fun getGeneratingAction(artifact: Artifact): ActionAnalysisMetadata? {
        return generatingActionMap.get(OwnerlessArtifactWrapper(artifact))
    }

    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    override fun registerAction(action: ActionAnalysisMetadata) {
        for (artifact in action.getOutputs()) {
            val previousAction: ActionAnalysisMetadata? =
                generatingActionMap.putIfAbsent(OwnerlessArtifactWrapper(artifact), action)
            if (previousAction != null && previousAction !== action) {
                if (com.google.devtools.build.lib.actions.Actions.canBeSharedLogForPotentialFalsePositives(
                        actionKeyContext, action, previousAction
                    )
                ) {
                    return  // All outputs can be shared. No need to register the remaining outputs.
                }
                // TODO(blaze-configurability-team): May be possible to do some inspection here and provide
                // advice on possible known-common reasons for conflicts.
                // e.g. If only config diffs where one is missing TestOptions: --trim_test_configuration
                //   or, if the --platforms differ but have same shortname
                throw ActionConflictException.Companion.create(actionKeyContext, artifact, previousAction, action)
            }
        }
    }

    override fun getSize(): Int {
        return generatingActionMap.size()
    }
}
