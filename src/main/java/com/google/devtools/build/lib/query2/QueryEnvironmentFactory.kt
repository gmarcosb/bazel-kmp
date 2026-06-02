// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** A factory that creates instances of `AbstractBlazeQueryEnvironment<Target>`.  */
open class QueryEnvironmentFactory {
    /** Creates an appropriate [AbstractBlazeQueryEnvironment] based on the given options.  */
    open fun create(
        queryTransitivePackagePreloader: QueryTransitivePackagePreloader?,
        graphFactory: WalkableGraphFactory?,
        targetProvider: TargetProvider?,
        cachingPackageLocator: CachingPackageLocator?,
        targetPatternPreloader: TargetPatternPreloader?,
        targetParser: TargetPattern.Parser?,
        relativeWorkingDirectory: PathFragment?,
        keepGoing: Boolean,
        strictScope: Boolean,
        orderedResults: Boolean,
        universeScope: UniverseScope?,
        loadingPhaseThreads: Int,
        trackIncrementalState: Boolean,
        labelFilter: com.google.common.base.Predicate<Label?>?,
        eventHandler: ExtendedEventHandler?,
        settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
        extraFunctions: Iterable<QueryFunction?>?,
        packagePath: PathPackageLocator?,
        useGraphlessQuery: Boolean,
        labelPrinter: LabelPrinter?
    ): AbstractBlazeQueryEnvironment<Target?>? {
        com.google.common.base.Preconditions.checkNotNull<UniverseScope?>(universeScope)
        if (canUseSkyQuery(orderedResults, universeScope, packagePath, strictScope, labelFilter)) {
            return SkyQueryEnvironment(
                keepGoing,
                loadingPhaseThreads,
                trackIncrementalState,
                eventHandler,
                settings,
                extraFunctions,
                targetParser,
                relativeWorkingDirectory,
                graphFactory,
                universeScope,
                packagePath,
                labelPrinter
            )
        } else if (useGraphlessQuery) {
            return GraphlessBlazeQueryEnvironment(
                queryTransitivePackagePreloader,
                targetProvider,
                cachingPackageLocator,
                targetPatternPreloader,
                targetParser,
                keepGoing,
                strictScope,
                loadingPhaseThreads,
                labelFilter,
                eventHandler,
                settings,
                extraFunctions,
                labelPrinter
            )
        } else {
            return BlazeQueryEnvironment(
                queryTransitivePackagePreloader,
                targetProvider,
                cachingPackageLocator,
                targetPatternPreloader,
                targetParser,
                keepGoing,
                strictScope,
                loadingPhaseThreads,
                labelFilter,
                eventHandler,
                settings,
                extraFunctions,
                labelPrinter
            )
        }
    }

    companion object {
        protected fun canUseSkyQuery(
            orderedResults: Boolean,
            universeScope: UniverseScope,
            packagePath: PathPackageLocator?,
            strictScope: Boolean,
            labelFilter: com.google.common.base.Predicate<Label?>?
        ): Boolean {
            return !orderedResults && !universeScope.isEmpty() && packagePath != null && strictScope
                    && labelFilter === Rule.ALL_LABELS
        }
    }
}
