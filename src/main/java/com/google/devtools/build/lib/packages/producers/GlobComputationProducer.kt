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
import com.google.common.base.Splitter
import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories
import com.google.devtools.build.lib.packages.Globber
import com.google.devtools.build.lib.util.Pair
import java.util.Objects
import java.util.function.Function
import java.util.regex.Pattern
import kotlin.collections.MutableSet

/**
 * Serves as the entrance [StateMachine] to compute a single glob. There are two ways to
 * create [GlobComputationProducer]:
 * 
 * 
 *  * When each glob within a package is represented as an individual GLOB node, [       ] creates
 * [GlobComputationProducer] to start the computation of each GLOB node.
 *  * All globs within a package are held by a single GLOBS node. When a package's depending
 * [com.google.devtools.build.lib.skyframe.GlobsFunction.compute] is called for the
 * first time, multiple [GlobComputationProducer]s are created for each individual
 * package glob, and they shall be driven in-parallel.
 * 
 */
class GlobComputationProducer(
    globDescriptor: GlobDescriptor,
    ignoredSubdirectories: IgnoredSubdirectories?,
    regexPatternCache: ConcurrentHashMap<String?, Pattern?>?,
    resultSink: ResultSink
) : StateMachine, FragmentProducer.ResultSink {
    /**
     * Propagates all glob matching [PathFragment]s or any [Exception].
     * 
     * 
     * If any [GlobError] is accepted, the already discovered path fragments are still
     * reported. However, [com.google.devtools.build.lib.skyframe.GlobFunction] and [ ] throw the first discovered [ ] wrapped in a [com.google.devtools.build.lib.skyframe.GlobException].
     * 
     * 
     * The already discovered path fragments should be considered as undefined. Since: (1) there is
     * no skyframe restart after glob computation throws an exception, so the discovered path
     * fragments can miss some matchings; (2) these discovered path fragments are not used to
     * construct a [com.google.devtools.build.lib.skyframe.GlobValue] or [ ].
     */
    interface ResultSink {
        fun acceptPathFragmentsWithoutPackageFragment(pathFragments: ImmutableSet<PathFragment?>?)

        fun acceptGlobError(error: GlobError?)
    }

    // -------------------- Input --------------------
    private val globDescriptor: GlobDescriptor
    private val resultSink: ResultSink

    // -------------------- Internal State --------------------
    private val pathFragmentsWithPackageFragment: ImmutableSet.Builder<PathFragment?>
    private val ignoredSubdirectories: IgnoredSubdirectories?
    private val regexPatternCache: ConcurrentHashMap<String?, Pattern?>?

    init {
        this.globDescriptor = globDescriptor
        this.ignoredSubdirectories = ignoredSubdirectories
        this.regexPatternCache = regexPatternCache
        this.resultSink = resultSink
        this.pathFragmentsWithPackageFragment = ImmutableSet.builder<PathFragment?>()
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        Preconditions.checkNotNull<Any?>(ignoredSubdirectories)
        val patterns =
            ImmutableList.copyOf<String?>(Splitter.on('/').split(globDescriptor.getPattern()))
        val globDetail =
            GlobDetail.Companion.create(
                globDescriptor.getPackageId(),
                globDescriptor.getPackageRoot(),
                patterns,  /* containsMultipleDoubleStars= */
                Collections.frequency(patterns, "**") > 1,
                ignoredSubdirectories,
                regexPatternCache,
                globDescriptor.globberOperation()
            )
        var visitedGlobSubTasks: MutableSet<Pair<PathFragment?, Int?>?>? = null
        if (globDetail.containsMultipleDoubleStars) {
            visitedGlobSubTasks = HashSet<Pair<PathFragment?, Int?>?>()
        }
        tasks.enqueue(
            FragmentProducer(
                globDetail,
                globDetail
                    .packageIdentifier
                    .getPackageFragment()
                    .getRelative(globDescriptor.getSubdir()),  /* fragmentIndex= */
                0,
                visitedGlobSubTasks,
                this as FragmentProducer.ResultSink
            )
        )
        return StateMachine { tasks: StateMachine.Tasks? -> this.buildAndBubbleUpResult(tasks) }
    }

    override fun acceptPathFragmentWithPackageFragment(pathFragment: PathFragment?) {
        pathFragmentsWithPackageFragment.add(pathFragment)
    }

    override fun acceptGlobError(error: GlobError?) {
        resultSink.acceptGlobError(error)
    }

    /**
     * Removes the package fragment from all accepted matching path fragments before [ ] accepts them.
     */
    fun buildAndBubbleUpResult(tasks: StateMachine.Tasks?): StateMachine {
        resultSink.acceptPathFragmentsWithoutPackageFragment(
            pathFragmentsWithPackageFragment.build().parallelStream()
                .map<Any?>(Function { p: PathFragment? ->
                    p.relativeTo(
                        globDescriptor.getPackageId().getPackageFragment()
                    )
                })
                .collect(ImmutableSet.toImmutableSet<Any?>())
        )
        return StateMachine.DONE
    }

    /**
     * Container which holds all constant information needed for globbing.
     * 
     * 
     * This object is created and passed into [FragmentProducer] so that we only need one
     * reference of [GlobDetail] downstream.
     * 
     * @param containsMultipleDoubleStars When multiple `**` s appear in pattern fragments, a
     * set is created to track visited glob subtasks in order to prevent duplicate work.
     * 
     * See [FragmentProducer.visitedGlobSubTasks] for more details.
     */
    @kotlin.jvm.JvmRecord
    internal data class GlobDetail(
        packageIdentifier: PackageIdentifier?,
        packageRoot: Root?,
        patternFragments: ImmutableList<String?>?,
        containsMultipleDoubleStars: Boolean,
        ignoredSubdirectories: IgnoredSubdirectories?,
        regexPatternCache: ConcurrentHashMap<String?, Pattern?>?,
        globOperation: Globber.Operation?
    ) {
        val packageIdentifier: PackageIdentifier?
        val packageRoot: Root?
        val patternFragments: ImmutableList<String?>?
        val containsMultipleDoubleStars: Boolean
        val ignoredSubdirectories: IgnoredSubdirectories?
        val regexPatternCache: ConcurrentHashMap<String?, Pattern?>?
        val globOperation: Globber.Operation?

        init {
            this.globOperation = globOperation
            this.regexPatternCache = regexPatternCache
            this.ignoredSubdirectories = ignoredSubdirectories
            this.containsMultipleDoubleStars = containsMultipleDoubleStars
            this.patternFragments = patternFragments
            this.packageRoot = packageRoot
            this.packageIdentifier = packageIdentifier
            Object > Objects.requireNonNull<Any?>(packageIdentifier, "packageIdentifier")
            Root > Objects.requireNonNull<Root?>(packageRoot, "packageRoot")
            Objects.requireNonNull<ImmutableList<String?>?>(patternFragments, "patternFragments")
            Object > Objects.requireNonNull<Any?>(ignoredSubdirectories, "ignoredSubdirectories")
            Objects.requireNonNull<ConcurrentHashMap<String?, Pattern?>?>(regexPatternCache, "regexPatternCache")
            Operation > Objects.requireNonNull<Globber.Operation?>(globOperation, "globOperation")
        }

        companion object {
            fun create(
                packageIdentifier: PackageIdentifier?,
                packageRoot: Root?,
                patternFragments: ImmutableList<String?>?,
                containsMultipleDoubleStars: Boolean,
                ignoredSubdirectories: IgnoredSubdirectories?,
                regexPatternCache: ConcurrentHashMap<String?, Pattern?>?,
                globOperation: Globber.Operation?
            ): GlobDetail {
                return GlobDetail(
                    packageIdentifier,
                    packageRoot,
                    patternFragments,
                    containsMultipleDoubleStars,
                    ignoredSubdirectories,
                    regexPatternCache,
                    globOperation
                )
            }
        }
    }
}
