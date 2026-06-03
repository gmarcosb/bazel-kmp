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
package com.google.devtools.coverageoutputgenerator

import com.google.auto.value.AutoValue


/**
 * Stores branch coverage information.
 * 
 * 
 * Corresponds to either a BRDA or BA (Google only) line in an lcov report.
 * 
 * 
 * BA lines correspond to instances where blockNumber and branchNumber are set to empty Strings
 * and have the form:
 * 
 * <pre>BA:[line_number],[taken]</pre>
 * 
 * In this case, nrOfExecutions() actually refers to the "taken" value where:
 * 
 * 
 *  * 0 = Branch was never evaluated (evaluated() == false)
 *  * 1 = Branch was evaluated but never taken
 *  * 2 = Branch was taken
 * 
 * 
 * BRDA lines set have the form
 * 
 * <pre>BRDA:[line_number],[block_number],[branch_number],[taken]</pre>
 * 
 * where the block and branch numbers are internal identifiers, and taken is either "-" if the
 * branch condition was never evaluated or a number indicating how often the branch was taken (which
 * may be 0).
 */
@AutoValue
internal abstract class BranchCoverageItem {
    abstract fun lineNumber(): Int

    /**
     * Internal GCC ID for the branch.
     * 
     * 
     * Empty for BA lines.
     */
    abstract fun blockNumber(): String?

    /** Either the internal GCC ID for the branch or an increasing counter for BA lines.  */
    abstract fun branchNumber(): String?

    abstract fun evaluated(): Boolean

    abstract fun nrOfExecutions(): Long

    fun wasExecuted(): Boolean {
        // if there's no block number then this is a BA branch so only taken if the "nrOfExecutions"
        // value == 2 (since it refers to the BA taken value)
        // otherwise it really is an execution count, so a count > 0 means the branch was executed
        return if (blockNumber().isEmpty()) nrOfExecutions() == 2L else nrOfExecutions() > 0
    }

    companion object {
        /**
         * Creates an instance of a BranchCoverage.
         * 
         * @param lineNumber The line number the branch is on
         * @param blockNumber GCC internal ID - often "0"
         * @param branchNumber ID for the specific branch at this line
         * @param evaluated Whether the branches were ever evaluated
         * @param nrOfExecutions How many times this particular branch was taken
         */
        fun create(
            lineNumber: Int,
            blockNumber: String?,
            branchNumber: String?,
            evaluated: Boolean,
            nrOfExecutions: Long
        ): BranchCoverageItem {
            return AutoValue_BranchCoverageItem(
                lineNumber, blockNumber, branchNumber, evaluated, nrOfExecutions
            )
        }
    }
}
