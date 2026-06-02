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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.IgnoredSubdirectories

/**
 * Computes [GlobValue]s for a package with only one Glob node created. The recursive globbing
 * logic is inlined in one [com.google.devtools.build.skyframe.SkyFunction.compute]
 * invocation.
 * 
 * 
 * The recursion inlined in one [com.google.devtools.build.skyframe.SkyFunction.compute]
 * invocation is realized by using [com.google.devtools.build.skyframe.state.StateMachine] for
 * structured concurrency when querying dependent [SkyKey]s, and [SkyKeyComputeState] to
 * cache computation state between skyframe restarts.
 */
class GlobFunctionWithRecursionInSingleFunction : GlobFunction() {
    /**
     * Stores [GlobFunctionWithRecursionInSingleFunction] computation state of the same glob
     * pattern between skyframe restarts.
     */
    private class State : SkyKeyComputeState,
        com.google.devtools.build.lib.packages.producers.GlobComputationProducer.ResultSink {
        /**
         * Drives a [GlobComputationProducer] that sets the [.globMatchingResult] when
         * complete.
         */
        // Non-null while in-flight.
        private var globComputationDriver: com.google.devtools.build.skyframe.state.Driver? = null

        var ignoredSubdirectories: IgnoredSubdirectories? = null

        private var globMatchingResult: com.google.common.collect.ImmutableSet<PathFragment?>? = null
        private var error: GlobError? = null

        public override fun acceptPathFragmentsWithoutPackageFragment(
            globMatchingResult: com.google.common.collect.ImmutableSet<PathFragment?>?
        ) {
            if (error == null) {
                // If an exception has already been discovered and accepted during previous computation, we
                // should not accept any matching result.
                this.globMatchingResult = globMatchingResult
            }
        }

        override fun acceptGlobError(error: GlobError?) {
            if (this.error == null) {
                // Keeps the first reported error if there are multiple.
                this.error = error
            }
        }
    }

    @Throws(GlobException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val glob: GlobDescriptor = skyKey.argument() as GlobDescriptor
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.GlobFunctionWithRecursionInSingleFunction.State() })

        if (state.ignoredSubdirectories == null) {
            val repositoryName: RepositoryName? = glob.getPackageId().getRepository()
            val ignoredSubDirectories: IgnoredSubdirectoriesValue? =
                env.getValue(IgnoredSubdirectoriesValue.Companion.key(repositoryName)) as IgnoredSubdirectoriesValue?
            if (env.valuesMissing()) {
                return null
            }
            state.ignoredSubdirectories = ignoredSubDirectories.asIgnoredSubdirectories()
        }

        if (state.globComputationDriver == null) {
            state.globComputationDriver =
                com.google.devtools.build.skyframe.state.Driver(
                    GlobComputationProducer(
                        glob, state.ignoredSubdirectories, regexPatternCache, state
                    )
                )
        }

        if (!state.globComputationDriver.drive(env)) {
            // Even though glob computation has not completed, we still want to throw exceptions
            // discovered in the current Skyframe session.
            GlobException.Companion.handleExceptions(state.error)
            return null
        }

        GlobException.Companion.handleExceptions(state.error)
        return GlobValueWithImmutableSet(state.globMatchingResult)
    }
}
