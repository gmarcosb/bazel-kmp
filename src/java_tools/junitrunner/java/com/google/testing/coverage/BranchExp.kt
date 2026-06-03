// Copyright 2016 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.coverage

import com.google.testing.coverage.CovExp
import com.google.testing.coverage.NullExp
import java.util.Collections
import java.util.stream.Collectors

/** A branch coverage that must be evaluated as a combination of probes.  */
class BranchExp : CovExp {
    private val branches: MutableList<CovExp>

    // Cache the evaluation result to avoid reevaluating the expression with the same probes.
    private var probesUsed: BooleanArray?
    private var value = false

    constructor(branches: MutableList<CovExp>) {
        this.branches = branches
    }

    /** Create a new BranchExp using this CovExp as the only branch.  */
    constructor(exp: CovExp?) {
        branches = java.util.ArrayList<CovExp>()
        branches.add(exp)
    }

    /** Returns true if any branches been set for this BranchExp.  */
    fun hasBranches(): Boolean {
        return branches.stream().anyMatch { exp: CovExp? -> exp != NullExp.Companion.NULL_EXP }
    }

    /**
     * Returns the expressions for the logical branches.
     * 
     * 
     * Expressions that have not been set are omitted.
     */
    fun getBranches(): MutableList<CovExp?> {
        return branches.stream()
            .filter { exp: CovExp? -> exp != NullExp.Companion.NULL_EXP }
            .collect(Collectors.toList())
    }

    /** Set the expression at a given index for this branch.  */
    fun setBranchAtIndex(index: Int, exp: CovExp?) {
        extendBranches(index + 1)
        branches.set(index, exp)
        invalidateEvalCache()
    }

    /** Returns the expression at a given index for this branch.  */
    fun getBranchAtIndex(index: Int): CovExp? {
        return branches.get(index)
    }

    /** Expands the current branch set to the new size  */
    private fun extendBranches(size: Int) {
        if (branches.size < size) {
            // This preserves the cached eval value so no need to invalidate.
            branches.addAll(Collections.nCopies<NullExp?>(size - branches.size, NullExp.Companion.NULL_EXP))
        }
    }

    /**
     * Add an expression to a branch expression.
     * 
     * @return the index of the newly added branch.
     */
    fun add(exp: CovExp?): Int {
        branches.add(exp)
        invalidateEvalCache()
        return branches.size - 1
    }

    private fun invalidateEvalCache() {
        probesUsed = null
    }

    override fun eval(probes: BooleanArray?): Boolean {
        if (probes == probesUsed) {
            return value
        }
        value = false
        for (exp in branches) {
            value = exp.eval(probes)
            if (value) {
                break
            }
        }
        probesUsed = probes
        return value
    }

    companion object {
        /** Create a BranchExp for a known number of branches but with no expression data.  */
        fun initializeEmptyBranches(): BranchExp {
            return BranchExp(java.util.ArrayList<CovExp?>())
        }

        /** Make a new BranchExp representing the concatenation of branches in inputs.  */
        fun concatenate(first: BranchExp, second: BranchExp): BranchExp {
            val branches: MutableList<CovExp> = java.util.ArrayList<CovExp>(first.branches)
            branches.addAll(second.branches)
            return BranchExp(branches)
        }

        /** Make a new BranchExp representing the pairwise union of branches in inputs  */
        fun zip(left: BranchExp, right: BranchExp): BranchExp {
            val zippedBranches: MutableList<CovExp> = java.util.ArrayList<CovExp>()
            val leftSize = left.branches.size
            val rightSize = right.branches.size
            var i: Int
            i = 0
            while (i < leftSize && i < rightSize) {
                val branches: MutableList<CovExp> =
                    java.util.Arrays.asList<CovExp?>(left.branches.get(i), right.branches.get(i))
                zippedBranches.add(BranchExp(branches))
                i++
            }
            val remainder: MutableList<CovExp?> = if (leftSize < rightSize) right.branches else left.branches
            while (i < remainder.size) {
                zippedBranches.add(BranchExp(remainder.get(i)))
                i++
            }
            return BranchExp(zippedBranches)
        }

        /** Wraps a CovExp in a BranchExp if it isn't one already.  */
        fun ensureIsBranchExp(exp: CovExp): BranchExp {
            return if (exp is BranchExp) exp else BranchExp(exp)
        }
    }
}
