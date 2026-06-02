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
 * A helper class that computes unbounded 'allrdeps(<expr>)' or 'rdeps(<precomputed-universe>,
 * <expr>)' via BFS.
 * 
 * 
 * The visitor uses [DepAndRdep] to keep track the nodes to visit and avoid dealing with
 * targetification of reverse deps until they are needed. The rdep node itself is needed to filter
 * out disallowed deps later. Compared against the approach using a single SkyKey, it consumes 16
 * more bytes in a 64-bit environment for each edge. However it defers the need to load all the
 * packages which have at least a target as a rdep of the current batch, thus greatly reduces the
 * risk of OOMs. The additional memory usage should not be a large concern here, as even with 10M
 * edges, the memory overhead is around 160M, and the memory can be reclaimed by regular GC.
 * 
 * 
 * TODO(bazel-team): Split this up into two classes: one which does edge filtering and which
 * doesn't.
</expr></precomputed-universe></expr> */
internal class RdepsUnboundedVisitor(
    env: SkyQueryEnvironment,
    validRdepUniquifier: Uniquifier<SkyKey?>,
    unfilteredUniverse: com.google.common.base.Predicate<SkyKey?>,
    extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>,
    callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
) : AbstractTargetOuputtingVisitor<DepAndRdep?>(env, callback) {
    /**
     * A [Uniquifier] for *valid* visitations of rdeps. `env`'s dependency filter might
     * mean that some rdep edges are invalid, meaning that any individual [DepAndRdep]
     * visitation may actually be invalid. Because the same rdep can be reached through more than one
     * reverse edge, it'd be incorrect to naively dedupe visitations solely based on the rdep.
     */
    private val validRdepUniquifier: Uniquifier<SkyKey?>

    private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>

    private val unfilteredUniverse: com.google.common.base.Predicate<SkyKey?>

    init {
        this.validRdepUniquifier = validRdepUniquifier
        this.unfilteredUniverse = unfilteredUniverse
        this.extraGlobalDeps = extraGlobalDeps
    }

    /**
     * A [Factory] for [RdepsUnboundedVisitor] instances, each of which will be used to
     * perform visitation of the reverse transitive closure of the [Target]s passed in a single
     * [Callback.process] call. Note that all the created instances share the same [ ] so that we don't visit the same Skyframe node more than once.
     */
    internal class Factory(
        env: SkyQueryEnvironment,
        unfilteredUniverse: com.google.common.base.Predicate<SkyKey?>,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ) : QueryVisitorFactory<DepAndRdep?, SkyKey?, Target?> {
        private val env: SkyQueryEnvironment
        private val validRdepUniquifier: Uniquifier<SkyKey?>
        private val unfilteredUniverse: com.google.common.base.Predicate<SkyKey?>
        private val extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>
        private val callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?

        init {
            this.env = env
            this.unfilteredUniverse = unfilteredUniverse
            this.validRdepUniquifier = env.createSkyKeyUniquifier()
            this.extraGlobalDeps = extraGlobalDeps
            this.callback = callback
        }

        public override fun create(): ParallelQueryVisitor<DepAndRdep?, SkyKey?, Target?> {
            return RdepsUnboundedVisitor(
                env, validRdepUniquifier, unfilteredUniverse, extraGlobalDeps, callback
            )
        }
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected override fun getVisitResult(depAndRdeps: Iterable<DepAndRdep?>?): Visit? {
        val uniqueValidRdepsbuilder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builder<SkyKey?>()
        for (rdep in RdepsVisitorUtils.getMaybeFilteredRdeps(depAndRdeps, env)) {
            if (validRdepUniquifier.unique(rdep)) {
                uniqueValidRdepsbuilder.add(rdep)
            }
        }
        val uniqueValidRdeps: com.google.common.collect.ImmutableList<SkyKey?> = uniqueValidRdepsbuilder.build()

        // Retrieve the reverse deps as SkyKeys and defer the targetification and filtering to next
        // recursive visitation. Because the universe given to us is unfiltered, we definitely still
        // need to filter out disallowed edges, but cannot do so before targetification occurs. This
        // means we may be wastefully visiting nodes via disallowed edges.
        val depAndRdepsToVisitBuilder: com.google.common.collect.ImmutableList.Builder<DepAndRdep?> =
            com.google.common.collect.ImmutableList.builder<DepAndRdep?>()
        env.getReverseDepLabelsOfLabels(uniqueValidRdeps, extraGlobalDeps)
            .forEach { (key: SkyKey?, value: Iterable<SkyKey?>?) ->
                depAndRdepsToVisitBuilder.addAll(
                    com.google.common.collect.Iterables.transform<SkyKey?, DepAndRdep?>(
                        com.google.common.collect.Iterables.filter<SkyKey?>(
                            value,
                            com.google.common.base.Predicates.and<SkyKey?>(
                                SkyQueryEnvironment.Companion.IS_LABEL,
                                unfilteredUniverse
                            )
                        ),
                        com.google.common.base.Function { rdep: SkyKey? -> DepAndRdep(key, rdep) })
                )
            }

        return Visit( /*keysToUseForResult=*/
            uniqueValidRdeps,  /*keysToVisit=*/
            depAndRdepsToVisitBuilder.build()
        )
    }

    override fun visitationKeyToOutputKey(visitationKey: DepAndRdep): SkyKey? {
        return visitationKey.rdep
    }

    protected override fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<DepAndRdep?>
    ): Iterable<DepAndRdep?> {
        // This isn't correct in isolation, but is end-to-end correct given the way ParallelVisitor
        // works: the contents of the input Iterable are surely unique because of the way this method is
        // called (a single dep can't have duplicate rdeps), and each node is [validly] visited at most
        // once, so by induction each DepAndRdep this RdepsUnboundedVisitor ever sees in this method is
        // unique across all calls.
        return com.google.common.collect.Iterables.filter<DepAndRdep?>(
            prospectiveVisitationKeys,
            com.google.common.base.Predicate { depAndRdep: DepAndRdep? -> validRdepUniquifier.uniquePure(depAndRdep.rdep) })
    }

    protected override fun preprocessInitialVisit(skyKeys: Iterable<SkyKey?>): Iterable<DepAndRdep?> {
        return com.google.common.collect.Iterables.transform<SkyKey?, DepAndRdep?>(
            com.google.common.collect.Iterables.filter<SkyKey?>(
                skyKeys,
                com.google.common.base.Predicate { k: SkyKey? -> unfilteredUniverse.apply(k) }),
            com.google.common.base.Function { key: SkyKey? -> DepAndRdep( /*dep=*/null, key) })
    }
}
