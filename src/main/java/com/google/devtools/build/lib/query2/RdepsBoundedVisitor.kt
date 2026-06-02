// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2

/**
 * A helper class that computes bounded 'allrdeps(<expr>, <depth>)' or
 * 'rdeps(<precomputed-universe>, <expr>, <depth>)' via BFS.
 * 
 * 
 * This is very similar to [RdepsUnboundedVisitor]. A lot of the same concerns apply here
 * but there are additional subtle concerns about the correctness of the bounded traversal: just
 * like for the sequential implementation of bounded allrdeps, we use [MinDepthUniquifier].
</depth></expr></precomputed-universe></depth></expr> */
internal class RdepsBoundedVisitor private constructor(
    env: SkyQueryEnvironment,
    private val depth: Int,
    validRdepMinDepthUniquifier: MinDepthUniquifier<SkyKey?>,
    universe: com.google.common.base.Predicate<SkyKey?>,
    extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>,
    callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
) : AbstractTargetOuputtingVisitor<DepAndRdepAtDepth?>(env, callback) {
    private val validRdepMinDepthUniquifier: MinDepthUniquifier<SkyKey?>
    private val universe: com.google.common.base.Predicate<SkyKey?>
    private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>

    init {
        this.validRdepMinDepthUniquifier = validRdepMinDepthUniquifier
        this.extraGlobalDeps = extraGlobalDeps
        this.universe = universe
    }

    internal class Factory(
        env: SkyQueryEnvironment,
        depth: Int,
        universe: com.google.common.base.Predicate<SkyKey?>,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ) : QueryVisitorFactory<DepAndRdepAtDepth?, SkyKey?, Target?> {
        private val env: SkyQueryEnvironment
        private val depth: Int
        private val validRdepMinDepthUniquifier: MinDepthUniquifier<SkyKey?>
        private val universe: com.google.common.base.Predicate<SkyKey?>
        private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>
        private val callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?

        init {
            this.env = env
            this.depth = depth
            this.universe = universe
            this.validRdepMinDepthUniquifier = env.createMinDepthSkyKeyUniquifier()
            this.extraGlobalDeps = extraGlobalDeps
            this.callback = callback
        }

        public override fun create(): ParallelQueryVisitor<DepAndRdepAtDepth?, SkyKey?, Target?> {
            return RdepsBoundedVisitor(
                env, depth, validRdepMinDepthUniquifier, universe, extraGlobalDeps, callback
            )
        }
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun getVisitResult(depAndRdepAtDepths: Iterable<DepAndRdepAtDepth?>): Visit? {
        val shallowestRdepDepthMap: MutableMap<SkyKey?, Int?> = HashMap<SkyKey?, Int?>()
        depAndRdepAtDepths.forEach(
            java.util.function.Consumer { depAndRdepAtDepth: DepAndRdepAtDepth? ->
                shallowestRdepDepthMap.merge(
                    depAndRdepAtDepth.depAndRdep.rdep, depAndRdepAtDepth.rdepDepth
                ) { a: Int?, b: Int? -> java.lang.Integer.min(a, b) }
            })

        val uniqueValidRdepsBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builder<SkyKey?>()
        for (validRdep in RdepsVisitorUtils.getMaybeFilteredRdeps(
            com.google.common.collect.Iterables.transform<DepAndRdepAtDepth?, DepAndRdep?>(
                depAndRdepAtDepths,
                com.google.common.base.Function { depAndRdepAtDepth: DepAndRdepAtDepth? -> depAndRdepAtDepth.depAndRdep }),
            env
        )) {
            if (validRdepMinDepthUniquifier.uniqueAtDepthLessThanOrEqualTo(
                    validRdep, shallowestRdepDepthMap.get(validRdep)
                )
            ) {
                uniqueValidRdepsBuilder.add(validRdep)
            }
        }
        val uniqueValidRdeps: com.google.common.collect.ImmutableList<SkyKey?> = uniqueValidRdepsBuilder.build()

        // Don't bother getting the rdeps of the rdeps that are already at the depth bound.
        val uniqueValidRdepsBelowDepthBound: Iterable<SkyKey?> =
            com.google.common.collect.Iterables.filter<SkyKey?>(
                uniqueValidRdeps,
                com.google.common.base.Predicate { uniqueValidRdep: SkyKey? ->
                    shallowestRdepDepthMap.get(
                        uniqueValidRdep
                    )!! < depth
                })

        // Retrieve the reverse deps as SkyKeys and defer the targetification and filtering to next
        // recursive visitation.
        val unfilteredRdepsOfRdeps: MutableMap<SkyKey?, Iterable<SkyKey?>?> =
            env.getReverseDepLabelsOfLabels(uniqueValidRdepsBelowDepthBound, extraGlobalDeps)

        val depAndRdepAtDepthsToVisitBuilder: com.google.common.collect.ImmutableList.Builder<DepAndRdepAtDepth?> =
            com.google.common.collect.ImmutableList.builder<DepAndRdepAtDepth?>()
        unfilteredRdepsOfRdeps
            .entries
            .forEach(
                java.util.function.Consumer { entry: MutableMap.MutableEntry<SkyKey?, Iterable<SkyKey?>?>? ->
                    val rdep: SkyKey? = entry!!.key
                    val depthOfRdepOfRdep = shallowestRdepDepthMap.get(rdep)!! + 1
                    com.google.common.collect.Streams.stream<SkyKey?>(entry.value)
                        .filter(
                            com.google.common.base.Predicates.and<SkyKey?>(
                                SkyQueryEnvironment.Companion.IS_LABEL,
                                universe
                            )
                        )
                        .forEachOrdered { rdepOfRdep: SkyKey? ->
                            depAndRdepAtDepthsToVisitBuilder.add(
                                DepAndRdepAtDepth(
                                    DepAndRdep(rdep, rdepOfRdep), depthOfRdepOfRdep
                                )
                            )
                        }
                })

        return Visit( /*keysToUseForResult=*/
            uniqueValidRdeps,  /*keysToVisit=*/
            depAndRdepAtDepthsToVisitBuilder.build()
        )
    }

    override fun visitationKeyToOutputKey(visitationKey: DepAndRdepAtDepth): SkyKey? {
        return visitationKey.depAndRdep.rdep
    }

    protected override fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<DepAndRdepAtDepth?>
    ): Iterable<DepAndRdepAtDepth?> {
        // See the comment in RdepsUnboundedVisitor#noteAndReturnUniqueVisitationKeys.
        return com.google.common.collect.Iterables.filter<DepAndRdepAtDepth?>(
            prospectiveVisitationKeys,
            com.google.common.base.Predicate { depAndRdepAtDepth: DepAndRdepAtDepth? ->
                validRdepMinDepthUniquifier.uniqueAtDepthLessThanOrEqualToPure(
                    depAndRdepAtDepth.depAndRdep.rdep, depAndRdepAtDepth.rdepDepth
                )
            })
    }

    protected override fun preprocessInitialVisit(skyKeys: Iterable<SkyKey?>): Iterable<DepAndRdepAtDepth?> {
        return com.google.common.collect.Iterables.transform<SkyKey?, DepAndRdepAtDepth?>(
            skyKeys,
            com.google.common.base.Function { key: SkyKey? -> DepAndRdepAtDepth(DepAndRdep( /*dep=*/null, key), 0) })
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun outputKeysToOutputValues(targetKeys: Iterable<SkyKey>): Iterable<Target?>? {
        // Can't use Iterables.filter() with the uniquifier here because the filter function has
        // side-effects and the resulting Iterable will be consumed more than once.
        val notYetOutputKeysBuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<SkyKey?>(
                com.google.common.collect.Iterables.size(
                    targetKeys
                )
            )
        for (targetKey in targetKeys) {
            if (validRdepMinDepthUniquifier.uniqueForOutput(targetKey)) {
                notYetOutputKeysBuilder.add(targetKey)
            }
        }
        return super.outputKeysToOutputValues(notYetOutputKeysBuilder.build())
    }
}
