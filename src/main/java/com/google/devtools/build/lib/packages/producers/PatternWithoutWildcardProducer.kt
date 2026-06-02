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
package com.google.devtools.build.lib.packages.producers

import com.google.common.base.Preconditions
import com.google.devtools.build.lib.actions.FileValue
import com.google.devtools.build.lib.util.Pair
import java.util.function.Consumer

/**
 * [PatternWithoutWildcardProducer] is a sub-[StateMachine] created by [ ]. It handles glob pattern fragment which does not contain any wildcard
 * characters (`*` or `**`).
 * 
 * 
 * When the pattern does not contain any wildcard character, the path is uniquely determined. So
 * it is only necessary to query the [FileValue] ending with this glob pattern fragment. If a
 * such file exists, we handle it by creating the [DirectoryDirentProducer] under this [ ][.filePath].
 */
internal class PatternWithoutWildcardProducer(
    globDetail: GlobDetail,
    filePath: PathFragment,
    fragmentIndex: Int,
    resultSink: FragmentProducer.ResultSink,
    visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?
) : StateMachine, Consumer<SkyValue?> {
    // -------------------- Input --------------------
    private val globDetail: GlobDetail

    /** The [PathFragment] of the file containing the package fragments.  */
    private val filePath: PathFragment

    private val fragmentIndex: Int

    // -------------------- Internal State --------------------
    private var fileValue: FileValue? = null
    private val visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?

    // -------------------- Output --------------------
    private val resultSink: FragmentProducer.ResultSink

    init {
        this.globDetail = globDetail
        this.filePath = filePath
        this.fragmentIndex = fragmentIndex
        this.resultSink = resultSink
        this.visitedGlobSubTasks = visitedGlobSubTasks
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        tasks.lookUp(
            FileValue.key(RootedPath.toRootedPath(globDetail.packageRoot, filePath)),
            this as Consumer<SkyValue?>
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.processFileValue(tasks) }
    }

    override fun accept(skyValue: SkyValue?) {
        fileValue = skyValue as FileValue?
    }

    /** Processes [FileValue] for the input [.filePath].  */
    private fun processFileValue(tasks: StateMachine.Tasks?): StateMachine {
        Preconditions.checkNotNull<Any?>(fileValue)
        if (!fileValue.exists()) {
            // Early exit if fileValue is null due to exception thrown during computation or the file does
            // not exist.
            return StateMachine.DONE
        }

        if (fileValue.isDirectory()) {
            return DirectoryDirentProducer(
                globDetail, filePath, fragmentIndex, resultSink, visitedGlobSubTasks
            )
        }
        if (FragmentProducer.Companion.shouldAddFileMatchingToResult(fragmentIndex, globDetail)) {
            resultSink.acceptPathFragmentWithPackageFragment(filePath)
        }
        return StateMachine.DONE
    }
}
