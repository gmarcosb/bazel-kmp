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

import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.ACTION_CONFLICTS

/** Check all transitive actions of an [ActionLookupValue] for action conflicts.  */
class ActionLookupConflictFindingFunction(cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider?>) :
    SkyFunction {
    private val cachingDependenciesSupplier: java.util.function.Supplier<RemoteAnalysisCacheReaderDepsProvider?>

    init {
        this.cachingDependenciesSupplier = cachingDependenciesSupplier
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            ACTION_CONFLICTS.get(env)
        val lookupKey: ActionLookupKey = (skyKey as ActionLookupConflictFindingValue.Key).argument()
        val alValue: ActionLookupValue? = env.getValue(lookupKey) as ActionLookupValue?
        if (env.valuesMissing()) {
            if (!lookupKey.equals(CoverageReportValue.COVERAGE_REPORT_KEY) // When remote retrieval is enabled, the analysis graph might be pruned, so missing action
                // lookup values are expected.
                && !cachingDependenciesSupplier.get().mode().isRetrievalEnabled()
            ) {
                BugReport.sendNonFatalBugReport(
                    java.lang.IllegalStateException(
                        "Unexpected missing action lookup value during action conflict finding: "
                                + skyKey
                    )
                )
            }
            return null
        }

        val depKeys: MutableSet<ActionLookupConflictFindingValue.Key?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<ActionLookupConflictFindingValue.Key?>()
        for (action in alValue.getActions()) {
            if (actionConflicts.containsKey(action)) {
                throw ActionConflictFunctionException(actionConflicts.get(action))
            }
            convertArtifacts(action.getInputs()).forEach(java.util.function.Consumer { e: ActionLookupConflictFindingValue.Key? ->
                depKeys.add(
                    e
                )
            })
        }
        // Avoid silly cycles.
        depKeys.remove(skyKey)

        val result: SkyframeLookupResult = env.getValuesAndExceptions(depKeys)
        if (env.valuesMissing()) {
            return null
        }
        for (key in depKeys) {
            if (result.get(key) == null) {
                return null
            }
        }
        return ActionLookupConflictFindingValue.INSTANCE
    }

    override fun extractTag(skyKey: SkyKey): String? {
        return Label.print((skyKey.argument() as ConfiguredTargetKey).getLabel())
    }

    internal class ActionConflictFunctionException(e: ActionConflictException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    companion object {
        fun convertArtifacts(
            artifacts: NestedSet<Artifact?>
        ): java.util.stream.Stream<ActionLookupConflictFindingValue.Key?> {
            return artifacts.toList().stream()
                .filter({ a -> !a.isSourceArtifact() })
                .map(ActionLookupConflictFindingValue::key)
        }
    }
}
