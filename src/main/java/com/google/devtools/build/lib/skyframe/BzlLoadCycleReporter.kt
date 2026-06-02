// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId

/** Reports cycles of recursive import of Starlark files.  */
class BzlLoadCycleReporter : SingleCycleReporter {
    public override fun maybeReportCycle(
        topLevelKey: SkyKey?,
        cycleInfo: CycleInfo,
        alreadyReported: Boolean,
        eventHandler: ExtendedEventHandler
    ): Boolean {
        val pathToCycle: com.google.common.collect.ImmutableList<SkyKey> = cycleInfo.getPathToCycle()
        val cycle: com.google.common.collect.ImmutableList<SkyKey?> = cycleInfo.getCycle()
        if (pathToCycle.isEmpty()) {
            return false
        }
        val lastPathElement: SkyKey = pathToCycle.get(pathToCycle.size() - 1)
        if (alreadyReported) {
            return true
        }

        if (com.google.common.collect.Iterables.all<SkyKey?>(
                cycle,
                IS_BZL_LOAD
            ) // The last element before the cycle has to be a PackageFunction, a .bzl file, or an
            // extension eval.
            && (IS_PACKAGE_SKY_KEY.apply(lastPathElement)
                    || IS_BZL_LOAD.apply(lastPathElement)
                    || IS_BZLMOD_EXTENSION.apply(lastPathElement))
        ) {
            val printer: com.google.common.base.Function<Any?, String?> =
                com.google.common.base.Function { rawInput: Any? ->
                    val input: SkyKey? = rawInput as SkyKey?
                    if (input.argument() is com.google.devtools.build.lib.skyframe.BzlLoadValue.Key) {
                        return@Function (input.argument() as com.google.devtools.build.lib.skyframe.BzlLoadValue.Key).getLabel()
                            .toString()
                    }
                    if (input.argument() is PackageIdentifier) {
                        return@Function input.argument().toString() + "/BUILD"
                    }
                    com.google.common.base.Preconditions.checkArgument(input.argument() is ModuleExtensionId)
                    val id: ModuleExtensionId? = input.argument() as ModuleExtensionId?
                    "module extension " + id
                }

            val cycleMessage: java.lang.StringBuilder =
                java.lang.StringBuilder().append("cycle detected in extension files: ")

            // go back the path that lead to the cycle till we found the BUILD file or extension eval
            // that lead to the circular load,
            var startIndex: Int = pathToCycle.size() - 1
            while (startIndex > 0
                && (IS_PACKAGE_SKY_KEY.apply(pathToCycle.get(startIndex - 1))
                        || IS_BZL_LOAD.apply(pathToCycle.get(startIndex - 1))
                        || IS_BZLMOD_EXTENSION.apply(pathToCycle.get(startIndex - 1)))
            ) {
                startIndex--
            }
            for (i in startIndex..<pathToCycle.size()) {
                cycleMessage.append("\n    ").append(printer.apply(pathToCycle.get(i)))
            }
            AbstractLabelCycleReporter.printCycle(cycleInfo.getCycle(), cycleMessage, printer)
            // TODO(bazel-team): it would be nice to pass the Location of the load Statement in the
            // BUILD file.
            eventHandler.handle(com.google.devtools.build.lib.events.Event.error(null, cycleMessage.toString()))
            return true
        } else if (com.google.common.collect.Iterables.all<SkyKey?>(
                cycle, com.google.common.base.Predicates.or<SkyKey?>(
                    IS_PACKAGE_LOOKUP, IS_REPOSITORY_DIRECTORY
                )
            )
        ) {
            val cycleMessage: java.lang.StringBuilder =
                java.lang.StringBuilder().append("Circular definition of repositories:")
            val repos: Iterable<SkyKey> =
                com.google.common.collect.Iterables.filter<SkyKey?>(cycle, IS_REPOSITORY_DIRECTORY)
            val printer: com.google.common.base.Function<Any?, String?> =
                com.google.common.base.Function { input: Any? ->
                    if (input is RepositoryDirectoryValue.Key) {
                        return@Function (input as RepositoryDirectoryValue.Key).argument().toString()
                    } else {
                        throw java.lang.UnsupportedOperationException()
                    }
                }
            AbstractLabelCycleReporter.printCycle(
                com.google.common.collect.ImmutableList.< E > copyOf < E ? > (repos),
                cycleMessage,
                printer
            )
            eventHandler.handle(com.google.devtools.build.lib.events.Event.error(null, cycleMessage.toString()))
            // To help debugging, request that the information be printed about where the respective
            // repositories were defined.
            requestRepoDefinitions(eventHandler, repos)
            return true
        }
        return false
    }

    companion object {
        private val IS_PACKAGE_SKY_KEY: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE)

        private val IS_PACKAGE_LOOKUP: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.PACKAGE_LOOKUP)

        private val IS_REPOSITORY_DIRECTORY: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.REPOSITORY_DIRECTORY)

        private val IS_BZL_LOAD: com.google.common.base.Predicate<SkyKey?> =
            SkyFunctions.isSkyFunction(SkyFunctions.BZL_LOAD)

        private val IS_BZLMOD_EXTENSION: com.google.common.base.Predicate<SkyKey?> =
            com.google.common.base.Predicates.or<SkyKey?>(
                SkyFunctions.isSkyFunction(SkyFunctions.SINGLE_EXTENSION),
                SkyFunctions.isSkyFunction(SkyFunctions.SINGLE_EXTENSION_EVAL)
            )

        private fun requestRepoDefinitions(
            eventHandler: ExtendedEventHandler, repos: Iterable<SkyKey>
        ) {
            for (repo in repos) {
                if (repo is RepositoryDirectoryValue.Key) {
                    eventHandler.post(
                        RequestRepositoryInformationEvent(
                            (repo as RepositoryDirectoryValue.Key).argument().name
                        )
                    )
                }
            }
        }
    }
}
