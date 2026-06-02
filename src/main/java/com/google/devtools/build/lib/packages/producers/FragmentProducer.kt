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
import com.google.devtools.build.lib.packages.Globber
import com.google.devtools.build.lib.util.Pair

/**
 * Recursively created to start handling each pattern fragment. Based on whether wildcard character
 * exists in the pattern, it creates either [PatternWithoutWildcardProducer] or [ ] producer.
 * 
 * 
 * [FragmentProducer] also handles special condition when the pattern is `**` by
 * immediately skipping the `**` and creating the next [FragmentProducer].
 */
internal class FragmentProducer(
    globDetail: GlobDetail,
    base: PathFragment,
    fragmentIndex: Int,
    visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?,
    resultSink: ResultSink
) : StateMachine {
    /** Accepts matching [PathFragment]s or any exceptions wrapped in [GlobError].  */
    internal interface ResultSink {
        fun acceptPathFragmentWithPackageFragment(pathFragment: PathFragment?)

        fun acceptGlobError(error: GlobError?)
    }

    // -------------------- Input --------------------
    private val globDetail: GlobDetail

    /**
     * Contains package fragments of the [PackageIdentifier]. It is guaranteed that:
     * 
     * 
     *  * [.base] is a directory;
     *  * there is no subpackage under [.base], when [.base] is not the package
     * fragment.
     * 
     */
    private val base: PathFragment

    /** Position of the pattern in [GlobDetail.patternFragments] to be processed.  */
    private val fragmentIndex: Int

    /**
     * The visited set is created to prevent potential duplicate work when handling glob pattern
     * containing multiple `**`s.
     * 
     * 
     * Each pair in the [.visitedGlobSubTasks] reflects that some previous [ ] has already processed a state when the [.base] is at the `pair.getFirst()` location and [.fragmentIndex] at `pair.getSecond()` position in
     * the [GlobDetail.patternFragments].
     * 
     * 
     * Consider this concrete example: `glob(['**\/a/ **\/foo.txt'])` with the only file being
     * `a/a/foo.txt`.
     * 
     * 
     * There are multiple routes to reach a point when a `FragmentProducer` whose base is
     * `a/a/foo.txt` and fragmentIndex is 3 (at "foo.txt") should be created.
     * 
     * 
     *  * One route starts by recursively globbing 'a/ **\/foo.txt' in the base directory of the
     * package.
     *  * Another route starts by recursively globbing '**\/a/ **\/foo.txt' in subdirectory 'a'.
     * 
     * 
     * 
     * [.visitedGlobSubTasks] prevents such a `FragmentProducer` from being created and
     * processed for the second time, and thus reduces duplicate computation.
     */
    private val visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?

    // -------------------- Output --------------------
    val resultSink: ResultSink

    init {
        // Make sure condition (1) glob patterns contains multiple `**`s and condition (2)
        // `visitedGlobSubTasks` is null should be the same.
        Preconditions.checkState(
            globDetail.containsMultipleDoubleStars == (visitedGlobSubTasks != null)
        )
        this.globDetail = globDetail
        this.base = base
        this.fragmentIndex = fragmentIndex
        this.visitedGlobSubTasks = visitedGlobSubTasks
        this.resultSink = resultSink
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        Preconditions.checkState(fragmentIndex < globDetail.patternFragments.size())

        val patternFragment = globDetail.patternFragments.get(fragmentIndex)
        if (patternFragment != "**") {
            return handlePatternFragment(patternFragment)
        }

        // It is valid that `**` matches nothing, which is handled in the if-else block below.
        if (fragmentIndex < globDetail.patternFragments.size() - 1) {
            // In the case when `**` is not the last pattern, skip `**` and directly move onto the next
            // pattern fragment.
            if (visitedGlobSubTasks == null
                || visitedGlobSubTasks.add(Pair.of<PathFragment?, Int?>(base, fragmentIndex + 1))
            ) {
                tasks.enqueue(
                    FragmentProducer(
                        globDetail, base, fragmentIndex + 1, visitedGlobSubTasks, resultSink
                    )
                )
            }
        } else {
            // In the case when `**` is the last pattern, add `base` to result when operator is
            // FILES_AND_DIRS.
            if (globDetail.globOperation == Globber.Operation.FILES_AND_DIRS
                && base != globDetail.packageIdentifier.getPackageFragment()
            ) {
                resultSink.acceptPathFragmentWithPackageFragment(base)
            }
        }

        // Handle the case when `**` does not match an empty fragment.
        return handlePatternFragment(patternFragment)
    }

    private fun handlePatternFragment(patternFragment: String): StateMachine {
        if (!patternFragment.contains("*") && !patternFragment.contains("?")) {
            return PatternWithoutWildcardProducer(
                globDetail,
                base.getChild(patternFragment),
                fragmentIndex,
                resultSink,
                visitedGlobSubTasks
            )
        }
        return PatternWithWildcardProducer(
            globDetail, base, fragmentIndex, resultSink, visitedGlobSubTasks
        )
    }

    companion object {
        /** Returns if a matching path at the given pattern index should be added to the result.  */
        fun shouldAddFileMatchingToResult(fragmentIndex: Int, globDetail: GlobDetail): Boolean {
            if (globDetail.globOperation == Globber.Operation.SUBPACKAGES) {
                return false
            }
            if (fragmentIndex < globDetail.patternFragments.size() - 1) {
                return false
            }
            return true
        }
    }
}
