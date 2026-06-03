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

import java.util.TreeMap
import java.util.TreeSet

/** Details of branch coverage information.  */
class BranchCoverageDetail {
    private val branchTaken: MutableMap<Int?, com.google.testing.coverage.BitField?>
    private val branches: MutableMap<Int?, Int?>

    init {
        branchTaken = TreeMap<Int?, com.google.testing.coverage.BitField?>()
        branches = TreeMap<Int?, Int?>()
    }

    private fun getBranchForLine(line: Int): com.google.testing.coverage.BitField {
        var value: com.google.testing.coverage.BitField? = branchTaken.get(line)
        if (value != null) {
            return value
        }
        value = com.google.testing.coverage.BitField()
        branchTaken.put(line, value)
        return value
    }

    /** Returns true if the line has branches.  */
    fun hasBranches(line: Int): Boolean {
        return branches.containsKey(line)
    }

    /** Sets the number of branches entry.  */
    fun setBranches(line: Int, n: Int) {
        branches.put(line, n)
    }

    /** Gets the number of branches in the line, returns 0 if there is no branch.  */
    fun getBranches(line: Int): Int {
        val value = branches.get(line)
        if (value == null) {
            return 0
        }
        return value
    }

    /** Sets the taken bit of the given line for the given branch index.  */
    fun setTakenBit(line: Int, branchIdx: Int) {
        getBranchForLine(line).setBit(branchIdx)
    }

    fun getTakenBit(line: Int, branchIdx: Int): Boolean {
        return getBranchForLine(line).isBitSet(branchIdx)
    }

    /** Calculate executed bit using heuristics.  */
    fun getExecutedBit(line: Int): Boolean {
        // If any of the branch is taken, the branch must have executed. Otherwise assume it is not.
        return getBranchForLine(line).any()
    }

    /** Returns line numbers where more than one branch is present.  */
    fun linesWithBranches(): MutableSet<Int?> {
        val result: MutableSet<Int?> = TreeSet<Int?>()
        for (i in branches.keys) {
            if (branches.get(i)!! > 1) {
                result.add(i)
            }
        }
        return result
    }
}
