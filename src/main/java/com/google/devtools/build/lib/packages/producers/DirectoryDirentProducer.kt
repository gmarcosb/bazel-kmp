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
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.cmdline.PackageIdentifier
import com.google.devtools.build.lib.packages.Globber
import com.google.devtools.build.lib.util.Pair
import java.util.function.Consumer
import java.util.function.Predicate

/**
 * Handles package (sub-)directory entry which is also a directory and matches part or all of the
 * glob pattern fragments.
 * 
 * 
 * Created by [PatternWithWildcardProducer] or [PatternWithoutWildcardProducer] after
 * they confirm that [the directory dirent][.direntPath] matches part or all of the glob
 * pattern fragments. File dirents are handled inline within the two upstream producers.
 * 
 * 
 * Checks whether [.direntPath] is a qualified glob matching result, and add to result if
 * so. Before creating the next [FragmentProducer] also checks whether (1) there are pattern
 * fragment(s) left to match and (2) subpackage crossing exists .
 */
internal class DirectoryDirentProducer(
    globDetail: GlobDetail,
    direntPath: PathFragment,
    fragmentIndex: Int,
    resultSink: FragmentProducer.ResultSink,
    visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?
) : StateMachine, Consumer<SkyValue?> {
    // -------------------- Input --------------------
    private val globDetail: GlobDetail

    /** The [PathFragment] of the dirent containing the package fragments.  */
    private val direntPath: PathFragment

    private val fragmentIndex: Int

    // -------------------- Internal State --------------------
    private var packageLookupValue: PackageLookupValue? = null
    private val visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>?

    // -------------------- Output --------------------
    private val resultSink: FragmentProducer.ResultSink

    init {
        // Upstream logic should already have appended some dirent to the package fragment when
        // constructing this `direntPath`.
        Preconditions.checkArgument(
            direntPath != globDetail.packageIdentifier.getPackageFragment()
        )
        this.direntPath = direntPath
        this.globDetail = globDetail
        this.fragmentIndex = fragmentIndex
        this.resultSink = resultSink
        this.visitedGlobSubTasks = visitedGlobSubTasks
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        // Check whether the next directory path should be ignored according to IgnoredSubdirectories.
        if (globDetail.ignoredSubdirectories.matchingEntry(direntPath) != null) {
            return StateMachine.DONE
        }

        tasks.lookUp(
            PackageLookupValue.key(
                PackageIdentifier.create(globDetail.packageIdentifier.getRepository(), direntPath)
            ),
            this as Consumer<SkyValue?>
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.checkSubpackageExistence(tasks) }
    }

    override fun accept(skyValue: SkyValue?) {
        packageLookupValue = skyValue as PackageLookupValue?
    }

    private fun checkSubpackageExistence(tasks: StateMachine.Tasks): StateMachine {
        Preconditions.checkNotNull<PackageLookupValue?>(packageLookupValue)
        if (packageLookupValue
                    is IncorrectRepositoryReferencePackageLookupValue
        ) {
            // Cross repository boundary, so glob expansion should not descend into that subdir.
            return StateMachine.DONE
        }

        if (packageLookupValue.packageExists()) {
            // Cross the package boundary. The subdir contains a BUILD file and thus defines another
            // package, so glob expansion should not descend into that subdir.
            if (globDetail.globOperation == Globber.Operation.SUBPACKAGES) {
                // If this is a subpackages() call, we need to check whether this subpackage is a glob
                // matching before returning.
                if (shouldAddResult( /* isSubpackage= */true)) {
                    resultSink.acceptPathFragmentWithPackageFragment(direntPath)
                }
            }
            return StateMachine.DONE
        }

        return addResultsOrCreateNextFragmentProducer(tasks)
    }

    private fun addResultsOrCreateNextFragmentProducer(tasks: StateMachine.Tasks): StateMachine {
        // Even for directory dirent, we need to check whether this path is a matching result.
        if (shouldAddResult( /* isSubpackage= */false)) {
            resultSink.acceptPathFragmentWithPackageFragment(direntPath)
        }

        val nextFragmentIndex =
            if (globDetail.patternFragments.get(fragmentIndex) == "**")
                fragmentIndex
            else
                fragmentIndex + 1
        if (nextFragmentIndex == globDetail.patternFragments.size()) {
            // When the last glob pattern is not double star, we have already processed all pattern
            // fragments, so execution enters this block and immediately returns.
            return StateMachine.DONE
        }

        if (visitedGlobSubTasks == null
            || visitedGlobSubTasks.add(Pair.of<PathFragment?, Int?>(direntPath, nextFragmentIndex))
        ) {
            // Create the next unvisited `FragmentProducer` if it has not been processed.
            tasks.enqueue(
                FragmentProducer(
                    globDetail, direntPath, nextFragmentIndex, visitedGlobSubTasks, resultSink
                )
            )
        }
        return StateMachine.DONE
    }

    /** Returns `true` if `path` can be added as a glob matching result.  */
    private fun shouldAddResult(isSubpackage: Boolean): Boolean {
        return when (globDetail.globOperation) {
            Globber.Operation.FILES ->  // The dirent is always a directory, so it can never match FILE operation.
                false

            Globber.Operation.SUBPACKAGES -> isSubpackage
                    && allRemainPatternFragmentsDoubleStar(globDetail.patternFragments, fragmentIndex)

            Globber.Operation.FILES_AND_DIRS -> fragmentIndex == globDetail.patternFragments.size() - 1 && !isSubpackage
        }
    }

    companion object {
        private fun allRemainPatternFragmentsDoubleStar(
            patternFragments: ImmutableList<String?>, index: Int
        ): Boolean {
            if (index == patternFragments.size() - 1) {
                // Already covered all pattern fragments at this point, so we don't need to check additional
                // pattern fragments.
                return true
            }
            return patternFragments.subList(index + 1, patternFragments.size()).stream()
                .allMatch(Predicate { anObject: String? -> "**".equals(anObject) })
        }
    }
}
