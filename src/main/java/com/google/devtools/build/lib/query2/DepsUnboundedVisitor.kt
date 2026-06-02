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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * A helper class that computes unbounded 'deps(<expr>)' via BFS. Re-use logic from [ ] to grab relevant semaphore locks when processing nodes as well as
 * batching visits by packages.
</expr> */
internal class DepsUnboundedVisitor(
    env: SkyQueryEnvironment,
    validDepUniquifier: Uniquifier<SkyKey?>,
    callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?,
    depsNeedFiltering: Boolean,
    context: QueryExpressionContext<Target?>,
    caller: QueryExpression?
) : AbstractTargetOuputtingVisitor<SkyKey?>(env, callback) {
    /**
     * A [Uniquifier] for valid deps. Only used prior to visiting the deps. Deps filtering is
     * done in the [DepsUnboundedVisitor.getVisitResult] stage.
     */
    private val validDepUniquifier: Uniquifier<SkyKey?>

    private val depsNeedFiltering: Boolean
    private val context: QueryExpressionContext<Target?>
    private val caller: QueryExpression?

    init {
        this.validDepUniquifier = validDepUniquifier
        this.depsNeedFiltering = depsNeedFiltering
        this.context = context
        this.caller = caller
    }

    /**
     * A [Factory] for [DepsUnboundedVisitor] instances, each of which will be used to
     * perform visitation of the DTC of the [SkyKey]s passed in a single [ ][Callback.process] call. Note that all the created instances share the same [Uniquifier]
     * so that we don't visit the same Skyframe node more than once.
     */
    internal class Factory(
        env: SkyQueryEnvironment,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?,
        depsNeedFiltering: Boolean,
        context: QueryExpressionContext<Target?>,
        caller: QueryExpression?
    ) : QueryVisitorFactory<SkyKey?, SkyKey?, Target?> {
        private val env: SkyQueryEnvironment
        private val validDepUniquifier: Uniquifier<SkyKey?>
        private val callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
        private val depsNeedFiltering: Boolean
        private val context: QueryExpressionContext<Target?>
        private val caller: QueryExpression?

        init {
            this.env = env
            this.validDepUniquifier = env.createSkyKeyUniquifier()
            this.callback = callback
            this.depsNeedFiltering = depsNeedFiltering
            this.context = context
            this.caller = caller
        }

        public override fun create(): ParallelQueryVisitor<SkyKey?, SkyKey?, Target?> {
            return DepsUnboundedVisitor(
                env, validDepUniquifier, callback, depsNeedFiltering, context, caller
            )
        }
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun getVisitResult(keys: Iterable<SkyKey?>): Visit? {
        if (depsNeedFiltering) {
            // We have to targetify the keys here in order to determine the allowed dependencies.
            val packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?> =
                SkyQueryEnvironment.Companion.makePackageKeyToTargetKeyMap(keys)
            val pkgIdsNeededForTargetification: MutableSet<PackageIdentifier?>? =
                SkyQueryEnvironment.Companion.getPkgIdsNeededForTargetification(packageKeyToTargetKeyMap)
            val packageSemaphore: MultisetSemaphore<PackageIdentifier?> = getPackageSemaphore()
            packageSemaphore.acquireAll(pkgIdsNeededForTargetification)
            var depsAsSkyKeys: Iterable<SkyKey?>?
            try {
                val depsAsTargets: Iterable<Target?> =
                    env.getFwdDeps(
                        env.getTargetKeyToTargetMapForPackageKeyToTargetKeyMap(
                            packageKeyToTargetKeyMap
                        ).values,
                        context
                    )
                depsAsSkyKeys =
                    com.google.common.collect.Iterables.transform<Target?, SkyKey?>(depsAsTargets, Target::getLabel)
            } finally {
                packageSemaphore.releaseAll(pkgIdsNeededForTargetification)
            }

            return Visit( /*keysToUseForResult=*/
                keys,  /*keysToVisit=*/
                depsAsSkyKeys
            )
        }

        return Visit(
            keys,
            com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (
                    com.google.common.collect.Iterables.concat<SkyKey?>(
                        env.getFwdDepLabels(
                            keys,
                            context.extraGlobalDeps()
                        ).values
                    ))
        )
    }

    protected override fun preprocessInitialVisit(visitationKeys: Iterable<SkyKey?>?): Iterable<SkyKey?>? {
        return visitationKeys
    }

    override fun visitationKeyToOutputKey(visitationKey: SkyKey?): SkyKey? {
        return visitationKey
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    protected override fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<SkyKey?>?
    ): Iterable<SkyKey?>? {
        return validDepUniquifier.unique(prospectiveVisitationKeys)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected override fun handleMissingTargets(
        keysWithTargets: MutableMap<out SkyKey?, Target?>, targetKeys: MutableSet<SkyKey?>
    ) {
        env.reportUnsuccessfulOrMissingTargets(keysWithTargets, targetKeys, caller)
    }
}
