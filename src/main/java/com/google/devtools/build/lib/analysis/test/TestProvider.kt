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
package com.google.devtools.build.lib.analysis.test

import com.google.devtools.build.lib.actions.ActionInput

/** A [TransitiveInfoProvider] for configured targets that implement test rules.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class TestProvider(private val testParams: TestParams?) : TransitiveInfoProvider {
    /**
     * Returns the [TestParams] object for the test represented by the corresponding configured
     * target.
     */
    fun getTestParams(): TestParams? {
        return testParams
    }

    /** A value class describing the properties of a test.  */ // Non-final only for mocking.
    class TestParams internal constructor(
        private val runs: Int,
        private val shards: Int,
        private val runsDetectsFlakes: Boolean,
        timeout: TestTimeout?,
        testRuleClass: String?,
        testStatusArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?>?,
        outputs: com.google.common.collect.ImmutableList<ActionInput?>?,
        coverageParams: CoverageParams?
    ) {
        /** A value class describing the coverage-related properties of a test.  */
        @AutoCodec
        internal class CoverageParams(
            coverageArtifacts: com.google.common.collect.ImmutableList<Artifact?>?,
            coverageReportGenerator: FilesToRunProvider?,
            actionOwner: ActionOwner?
        ) {
            val coverageArtifacts: com.google.common.collect.ImmutableList<Artifact?>?
            val coverageReportGenerator: FilesToRunProvider?
            val actionOwner: ActionOwner?

            init {
                this.coverageArtifacts = coverageArtifacts
                this.coverageReportGenerator = coverageReportGenerator
                this.actionOwner = actionOwner
            }
        }

        private val timeout: TestTimeout?
        private val testRuleClass: String?
        private val testStatusArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?>?
        private val outputs: com.google.common.collect.ImmutableList<ActionInput?>?
        private val coverageParams: CoverageParams?

        /**
         * Don't call this directly. Instead use [ ].
         */
        init {
            this.timeout = timeout
            this.testRuleClass = testRuleClass
            this.testStatusArtifacts = testStatusArtifacts
            this.outputs = outputs
            this.coverageParams = coverageParams
        }

        /** Returns the number of times this test should be run.  */
        fun getRuns(): Int {
            return runs
        }

        /** Returns the number of shards for this test.  */
        fun getShards(): Int {
            return shards
        }

        /** Returns true iff multiple runs per shard should be aggregated for flake detection.  */
        fun runsDetectsFlakes(): Boolean {
            return runsDetectsFlakes
        }

        /** Returns the timeout of this test.  */
        fun getTimeout(): TestTimeout? {
            return timeout
        }

        /** Returns the test rule class.  */
        fun getTestRuleClass(): String? {
            return testRuleClass
        }

        /**
         * Returns a list of test status artifacts that represent serialized test status protobuffers
         * produced by testing this target.
         */
        fun getTestStatusArtifacts(): com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?>? {
            return testStatusArtifacts
        }

        /** Returns the list of mandatory and optional test outputs.  */
        fun getOutputs(): com.google.common.collect.ImmutableList<ActionInput?>? {
            return outputs
        }

        /** Returns the coverageArtifacts.  */
        fun getCoverageArtifacts(): com.google.common.collect.ImmutableList<Artifact?>? {
            return if (coverageParams != null) coverageParams.coverageArtifacts else com.google.common.collect.ImmutableList.of<Artifact?>()
        }

        /**
         * Returns the coverage report generator tool.
         * 
         * 
         * Returns a non-null value if and only iff coverage is generally enabled.
         */
        fun getCoverageReportGenerator(): FilesToRunProvider? {
            return if (coverageParams != null) coverageParams.coverageReportGenerator else null
        }

        /**
         * Returns the test action owner.
         * 
         * 
         * Returns a non-null value if and only iff coverage is generally enabled.
         */
        fun getActionOwnerForCoverage(): ActionOwner? {
            return if (coverageParams != null) coverageParams.actionOwner else null
        }
    }

    companion object {
        /**
         * Returns the test status artifacts for a specified configured target
         * 
         * @param target the configured target. Should belong to a test rule.
         * @return the test status artifacts
         */
        fun getTestStatusArtifacts(
            target: TransitiveInfoCollection
        ): com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> {
            return target.getProvider(TestProvider::class.java).getTestParams().getTestStatusArtifacts()
        }
    }
}
