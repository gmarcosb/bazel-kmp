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
import com.google.devtools.build.lib.skyframe.FileKey
import java.util.function.Consumer

/**
 * Looks up [FileValue] for a [FileKey] which is guaranteed to be a symlink.
 * 
 * 
 * Used when [PatternWithWildcardProducer] handles [ ]. For any [ ] which is a `SYMLINK`, computes its [ ] to know the target path.
 * 
 * 
 * When handling each `DirectoryListingValue`, multiple [SymlinkProducer]s can be
 * created so that [com.google.devtools.build.skyframe.state.Driver] is able to query the
 * symlink dirents in a batch. All symlink dirents [FileValue] will be collected in an array
 * list and the runAfter method should be executed only once. So [SymlinkProducer] does not
 * expect any runAfter [StateMachine] to be passed in.
 * 
 * 
 * If the [FileValue] from skyframe shows that this is not a symlink, accepts an [ ] which will be bubbled up.
 */
internal class SymlinkProducer(symlinkKey: FileKey, resultSink: ResultSink) : StateMachine, Consumer<SkyValue?> {
    internal interface ResultSink {
        fun acceptSymlinkFileValue(symlinkValue: FileValue?, symlinkKey: FileKey?)

        fun acceptInconsistentFilesystemException(exception: InconsistentFilesystemException?)
    }

    // -------------------- Input --------------------
    private val symlinkKey: FileKey

    // -------------------- Output --------------------
    private val resultSink: ResultSink

    init {
        this.symlinkKey = symlinkKey
        this.resultSink = resultSink
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        tasks.lookUp(symlinkKey, this as Consumer<SkyValue?>)
        return StateMachine.DONE
    }

    override fun accept(skyValue: SkyValue?) {
        Preconditions.checkState(skyValue is FileValue)
        val symlinkValue: FileValue = skyValue as FileValue

        if (!symlinkValue.isSymlink()) {
            resultSink.acceptInconsistentFilesystemException(
                InconsistentFilesystemException(
                    ("readdir and stat disagree about whether "
                            + symlinkKey.argument().asPath()
                            + " is a symlink.")
                )
            )
            return
        }

        resultSink.acceptSymlinkFileValue(symlinkValue, symlinkKey)
    }
}
