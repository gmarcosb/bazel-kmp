// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.actiongraph.v2

import com.google.devtools.build.lib.analysis.AnalysisProtosV2.Action

/** Handles the monolithic output channel. Supports only the JSON format.  */
class MonolithicOutputHandler(printStream: PrintStream) : AqueryOutputHandler {
    private val actionGraphContainerBuilder: ActionGraphContainer.Builder = ActionGraphContainer.newBuilder()
    private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer()
    private val printStream: PrintStream

    init {
        this.printStream = printStream
    }

    @Throws(IOException::class)
    override fun outputArtifact(message: Artifact?) {
        actionGraphContainerBuilder.addArtifacts(message)
    }

    @Throws(IOException::class)
    override fun outputAction(message: Action?) {
        actionGraphContainerBuilder.addActions(message)
    }

    @Throws(IOException::class)
    override fun outputTarget(message: Target?) {
        actionGraphContainerBuilder.addTargets(message)
    }

    @Throws(IOException::class)
    override fun outputDepSetOfFiles(message: DepSetOfFiles?) {
        actionGraphContainerBuilder.addDepSetOfFiles(message)
    }

    @Throws(IOException::class)
    override fun outputConfiguration(message: Configuration?) {
        actionGraphContainerBuilder.addConfiguration(message)
    }

    @Throws(IOException::class)
    override fun outputAspectDescriptor(message: AspectDescriptor?) {
        actionGraphContainerBuilder.addAspectDescriptors(message)
    }

    @Throws(IOException::class)
    override fun outputRuleClass(message: RuleClass?) {
        actionGraphContainerBuilder.addRuleClasses(message)
    }

    @Throws(IOException::class)
    override fun outputPathFragment(message: PathFragment?) {
        actionGraphContainerBuilder.addPathFragments(message)
    }

    @Throws(IOException::class)
    override fun close() {
        jsonPrinter.appendTo(actionGraphContainerBuilder.build(), printStream)
        printStream.println()
    }
}
