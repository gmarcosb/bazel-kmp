// Copyright 2022 The Bazel Authors. All rights reserved.
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


import com.google.devtools.build.lib.rules.genquery.GenQueryPackageProviderFactory

/**
 * [GraphInconsistencyReceiver] for evaluations operating on graphs when `--heuristically_drop_nodes` flag is applied, or when some form of node dropping is done in
 * combination with skymeld mode.
 * 
 * 
 * The expected inconsistency should be tolerated while all other inconsistencies should result
 * in throwing an exception.
 * 
 * 
 * `RewindableGraphInconsistencyReceiver` implements similar logic to handle heuristically
 * dropping state nodes.
 */
class NodeDroppingInconsistencyReceiver(
    private val heuristicallyDropNodes: Boolean,
    private val skymeldInconsistenciesExpected: Boolean
) : GraphInconsistencyReceiver {
    override fun noteInconsistencyAndMaybeThrow(
        key: SkyKey, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
    ) {
        if (heuristicallyDropNodes && isExpectedInconsistency(key, otherKeys, inconsistency)) {
            return
        }
        if (skymeldInconsistenciesExpected
            && isExpectedInconsistencySkymeld(key, otherKeys, inconsistency)
        ) {
            return
        }

        throw java.lang.IllegalStateException(
            String.format("Unexpected inconsistency: %s, %s, %s", key, otherKeys, inconsistency)
        )
    }

    companion object {
        private val EXPECTED_MISSING_CHILDREN: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunctionName?> =
            com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunctionName?>(
                SkyFunctions.FILE, FileStateKey.FILE_STATE,
                SkyFunctions.DIRECTORY_LISTING, SkyFunctions.DIRECTORY_LISTING_STATE,
                SkyFunctions.CONFIGURED_TARGET, GenQueryPackageProviderFactory.GENQUERY_SCOPE
            )

        // TODO: b/290998109#comment60 - After the GLOB nodes are replaced by GLOBS, the missing children
        // below might be unexpected.
        // These are only expected when Skymeld is enabled and we're dropping nodes.
        private val SKYMELD_EXPECTED_MISSING_CHILDREN: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunctionName?> =
            com.google.common.collect.ImmutableMap.of<SkyFunctionName?, SkyFunctionName?>(
                SkyFunctions.ACTION_EXECUTION,
                SkyFunctions.GLOB
            )

        /**
         * Checks whether the input inconsistency is an expected scenario caused by heuristically dropping
         * state nodes. See b/261019506 for background on this.
         */
        fun isExpectedInconsistency(
            key: SkyKey, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
        ): Boolean {
            return isExpectedInternal(key, otherKeys, inconsistency, EXPECTED_MISSING_CHILDREN)
        }

        /**
         * Checks whether the input inconsistency is an expected scenario caused by skymeld + some form of
         * node dropping.
         */
        fun isExpectedInconsistencySkymeld(
            key: SkyKey, otherKeys: MutableCollection<SkyKey?>?, inconsistency: Inconsistency?
        ): Boolean {
            return isExpectedInternal(key, otherKeys, inconsistency, SKYMELD_EXPECTED_MISSING_CHILDREN)
        }

        private fun isExpectedInternal(
            key: SkyKey,
            otherKeys: MutableCollection<SkyKey?>?,
            inconsistency: Inconsistency?,
            expectedMissingChildTypes: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunctionName?>
        ): Boolean {
            val expectedMissingChildType: SkyFunctionName? = expectedMissingChildTypes.get(key.functionName())
            if (expectedMissingChildType == null) {
                return false
            }
            if (inconsistency === Inconsistency.RESET_REQUESTED) {
                return otherKeys == null
            }
            if (inconsistency === Inconsistency.ALREADY_DECLARED_CHILD_MISSING
                || inconsistency === Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD
            ) {
                // For already declared child missing inconsistency, key is the parent while `otherKeys`
                // are the children (dependency nodes).
                return otherKeys.stream().allMatch(SkyFunctionName.functionIs(expectedMissingChildType))
            }
            return false
        }
    }
}
