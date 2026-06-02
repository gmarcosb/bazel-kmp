// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.aquery

import com.google.devtools.build.lib.actions.ActionLookupKey

/**
 * A [TargetAccessor] for [ConfiguredTargetValue] objects.
 * 
 * 
 * Incomplete; we'll implement getPrerequisites and getVisibility when needed.
 */
class ConfiguredTargetValueAccessor(
    walkableGraph: WalkableGraph,
    targetLookup: TargetLookup,
    configuredTargetKeyExtractor: KeyExtractor<ConfiguredTargetValue?, ActionLookupKey>
) : TargetAccessor<ConfiguredTargetValue?> {
    private val walkableGraph: WalkableGraph
    private val targetLookup: TargetLookup
    private val configuredTargetKeyExtractor: KeyExtractor<ConfiguredTargetValue?, ActionLookupKey>

    init {
        this.walkableGraph = walkableGraph
        this.targetLookup = targetLookup
        this.configuredTargetKeyExtractor = configuredTargetKeyExtractor
    }

    override fun getTargetKind(configuredTargetValue: ConfiguredTargetValue): String {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return actualTarget.getTargetKind()
    }

    override fun getLabel(configuredTargetValue: ConfiguredTargetValue): String {
        return configuredTargetValue.getConfiguredTarget().getLabel().toString()
    }

    override fun getPackage(configuredTargetValue: ConfiguredTargetValue): String {
        return configuredTargetValue
            .getConfiguredTarget()
            .getLabel()
            .getPackageIdentifier()
            .getPackageFragment()
            .toString()
    }

    override fun isRule(configuredTargetValue: ConfiguredTargetValue): Boolean {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return actualTarget is Rule
    }

    override fun isExecutableNonTestRule(configuredTargetValue: ConfiguredTargetValue): Boolean {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.isExecutableNonTestRule(actualTarget)
    }

    override fun isTestRule(configuredTargetValue: ConfiguredTargetValue): Boolean {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.isTestRule(actualTarget)
    }

    override fun isTestSuite(configuredTargetValue: ConfiguredTargetValue): Boolean {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.isTestSuiteRule(actualTarget)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getPrerequisites(
        caller: QueryExpression?,
        configuredTargetValue: ConfiguredTargetValue?,
        attrName: String?,
        errorMsgPrefix: String?
    ): MutableList<ConfiguredTargetValue?>? {
        // TODO(bazel-team): implement this if needed.
        throw com.google.devtools.build.lib.query2.engine.QueryException(
            "labels() is not supported for aquery", ActionQuery.Code.LABELS_FUNCTION_NOT_SUPPORTED
        )
    }

    override fun getStringListAttr(
        configuredTargetValue: ConfiguredTargetValue, attrName: String?
    ): MutableList<String?> {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.getStringListAttr(actualTarget, attrName)
    }

    override fun getStringAttr(configuredTargetValue: ConfiguredTargetValue, attrName: String?): String {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.getStringAttr(actualTarget, attrName)
    }

    override fun getAttrAsString(
        configuredTargetValue: ConfiguredTargetValue, attrName: String?
    ): Iterable<String?> {
        val actualTarget = getTargetFromConfiguredTargetValue(configuredTargetValue)
        return TargetUtils.getAttrAsString(actualTarget, attrName)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun getVisibility(
        caller: QueryExpression?, from: ConfiguredTargetValue?
    ): com.google.common.collect.ImmutableSet<QueryVisibility<ConfiguredTargetValue?>?>? {
        // TODO(bazel-team): implement this if needed.
        throw com.google.devtools.build.lib.query2.engine.QueryException(
            "visible() is not supported on configured targets",
            ConfigurableQuery.Code.VISIBLE_FUNCTION_NOT_SUPPORTED
        )
    }

    private fun getTargetFromConfiguredTargetValue(configuredTargetValue: ConfiguredTargetValue): Target {
        // Dereference any aliases that might be present.
        val label: Label? = configuredTargetValue.getConfiguredTarget().getOriginalLabel()
        try {
            return targetLookup.getTarget(label)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("Thread interrupted in the middle of getting a Target.", e)
        } catch (e: TargetNotFoundException) {
            throw java.lang.IllegalStateException("Unable to get target from package in accessor.", e)
        }
    }

    /** Returns the AspectValues that are attached to the given configuredTarget.  */
    @Throws(java.lang.InterruptedException::class)
    fun getAspectValues(configuredTargetValue: ConfiguredTargetValue): MutableSet<AspectValue?> {
        val result: MutableSet<AspectValue?> = HashSet<AspectValue?>()
        val skyKey: SkyKey = configuredTargetKeyExtractor.extractKey(configuredTargetValue)
        val revDeps: Iterable<SkyKey> =
            com.google.common.collect.Iterables.concat<SkyKey?>(
                walkableGraph.getReverseDeps(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        skyKey
                    )
                ).values
            )
        val label: Label? = configuredTargetValue.getConfiguredTarget().getLabel()
        for (revDep in revDeps) {
            val skyFunctionName: SkyFunctionName? = revDep.functionName()
            if (SkyFunctions.ASPECT == skyFunctionName) {
                val aspectValue: AspectValue? = walkableGraph.getValue(revDep) as AspectValue?
                if (aspectValue != null && (revDep as AspectKey).getLabel().equals(label)) {
                    result.add(aspectValue)
                }
            }
        }
        return result
    }
}
