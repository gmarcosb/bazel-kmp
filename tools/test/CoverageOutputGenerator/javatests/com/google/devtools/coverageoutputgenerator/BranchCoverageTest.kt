// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.coverageoutputgenerator.BranchCoverage
import com.google.devtools.coverageoutputgenerator.BranchCoverageItem
import com.google.devtools.coverageoutputgenerator.BranchCoverageKey
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.util.HashMap

@RunWith(JUnit4::class)
class BranchCoverageTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleRetrieval() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "1", false, 0)
        branchCoverage.addBranch(4, "", "0", true, 0)
        branchCoverage.addBranch(4, "", "1", true, 2)
        branchCoverage.addBranch(4, "", "2", true, 1)

        Truth.assertThat(branchCoverage.get(1, "", "0"))
            .isEqualTo(BranchCoverageItem.Companion.create(1, "", "0", false, 0))
        Truth.assertThat(branchCoverage.get(1, "", "1"))
            .isEqualTo(BranchCoverageItem.Companion.create(1, "", "1", false, 0))
        Truth.assertThat(branchCoverage.get(4, "", "0"))
            .isEqualTo(BranchCoverageItem.Companion.create(4, "", "0", true, 0))
        Truth.assertThat(branchCoverage.get(4, "", "1"))
            .isEqualTo(BranchCoverageItem.Companion.create(4, "", "1", true, 2))
        Truth.assertThat(branchCoverage.get(4, "", "2"))
            .isEqualTo(BranchCoverageItem.Companion.create(4, "", "2", true, 1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonExistentBranchReturnsNull() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "1", false, 0)

        Truth.assertThat(branchCoverage.get(1, "", "2")).isNull()
        Truth.assertThat(branchCoverage.get(1, "1", "0")).isNull()
        Truth.assertThat(branchCoverage.get(2, "", "0")).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIterator() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "1", false, 0)
        branchCoverage.addBranch(4, "", "0", true, 0)
        branchCoverage.addBranch(4, "", "1", true, 2)
        branchCoverage.addBranch(4, "", "2", true, 1)
        branchCoverage.addBranch(7, "id", "0", false, 0)
        branchCoverage.addBranch(7, "id", "1", false, 0)
        branchCoverage.addBranch(7, "id", "2", false, 0)

        val it: MutableIterator<MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?>> =
            branchCoverage.iterator()
        val result: HashMap<BranchCoverageKey?, BranchCoverageItem?> =
            HashMap<BranchCoverageKey?, BranchCoverageItem?>()
        while (it.hasNext()) {
            val entry: MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?> = it.next()
            result.put(entry.getKey(), entry.getValue())
        }

        Truth.assertThat(result)
            .containsExactly(
                BranchCoverageKey.Companion.create(1, "", "0"),
                BranchCoverageItem.Companion.create(1, "", "0", false, 0),
                BranchCoverageKey.Companion.create(1, "", "1"),
                BranchCoverageItem.Companion.create(1, "", "1", false, 0),
                BranchCoverageKey.Companion.create(4, "", "0"),
                BranchCoverageItem.Companion.create(4, "", "0", true, 0),
                BranchCoverageKey.Companion.create(4, "", "1"),
                BranchCoverageItem.Companion.create(4, "", "1", true, 2),
                BranchCoverageKey.Companion.create(4, "", "2"),
                BranchCoverageItem.Companion.create(4, "", "2", true, 1),
                BranchCoverageKey.Companion.create(7, "id", "0"),
                BranchCoverageItem.Companion.create(7, "id", "0", false, 0),
                BranchCoverageKey.Companion.create(7, "id", "1"),
                BranchCoverageItem.Companion.create(7, "id", "1", false, 0),
                BranchCoverageKey.Companion.create(7, "id", "2"),
                BranchCoverageItem.Companion.create(7, "id", "2", false, 0)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExhaustedIteratorThrows() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)

        val it: MutableIterator<MutableMap.MutableEntry<BranchCoverageKey?, BranchCoverageItem?>> =
            branchCoverage.iterator()

        Truth.assertThat(it.hasNext()).isTrue()
        it.next()
        Truth.assertThat(it.hasNext()).isFalse()
        org.junit.Assert.assertThrows<java.util.NoSuchElementException?>(
            java.util.NoSuchElementException::class.java,
            org.junit.function.ThrowingRunnable { it.next() })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopy() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "1", false, 0)
        branchCoverage.addBranch(4, "", "0", true, 0)
        branchCoverage.addBranch(4, "", "1", true, 2)

        val copy: BranchCoverage = BranchCoverage.Companion.copy(branchCoverage)
        val result: HashMap<BranchCoverageKey?, BranchCoverageItem?> =
            HashMap<BranchCoverageKey?, BranchCoverageItem?>()
        for (entry in copy) {
            result.put(entry.getKey(), entry.getValue())
        }

        Truth.assertThat(result)
            .containsExactly(
                BranchCoverageKey.Companion.create(1, "", "0"),
                BranchCoverageItem.Companion.create(1, "", "0", false, 0),
                BranchCoverageKey.Companion.create(1, "", "1"),
                BranchCoverageItem.Companion.create(1, "", "1", false, 0),
                BranchCoverageKey.Companion.create(4, "", "0"),
                BranchCoverageItem.Companion.create(4, "", "0", true, 0),
                BranchCoverageKey.Companion.create(4, "", "1"),
                BranchCoverageItem.Companion.create(4, "", "1", true, 2)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepeatedBranchesAreMerged() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "0", true, 1)
        branchCoverage.addBranch(1, "", "0", true, 2)
        branchCoverage.addBranch(2, "", "0", false, 0)
        branchCoverage.addBranch(2, "", "0", false, 0)

        Truth.assertThat(branchCoverage.get(1, "", "0"))
            .isEqualTo(BranchCoverageItem.Companion.create(1, "", "0", true, 3))
        Truth.assertThat(branchCoverage.get(2, "", "0"))
            .isEqualTo(BranchCoverageItem.Companion.create(2, "", "0", false, 0))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMerge() {
        val branchCoverage1: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage1.addBranch(1, "", "0", false, 0)
        branchCoverage1.addBranch(1, "", "1", false, 0)
        branchCoverage1.addBranch(4, "", "0", true, 0)
        branchCoverage1.addBranch(4, "", "1", true, 2)
        branchCoverage1.addBranch(6, "", "0", true, 1)
        branchCoverage1.addBranch(6, "id", "1", true, 0)
        val branchCoverage2: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage2.addBranch(1, "", "0", true, 1)
        branchCoverage2.addBranch(1, "", "1", true, 2)
        branchCoverage2.addBranch(4, "", "0", true, 3)
        branchCoverage2.addBranch(4, "", "1", true, 4)
        branchCoverage2.addBranch(7, "id", "0", true, 5)
        branchCoverage2.addBranch(7, "id", "1", true, 6)

        val merged: BranchCoverage = BranchCoverage.Companion.merge(branchCoverage1, branchCoverage2)
        val result: HashMap<BranchCoverageKey?, BranchCoverageItem?> =
            HashMap<BranchCoverageKey?, BranchCoverageItem?>()
        for (entry in merged) {
            result.put(entry.getKey(), entry.getValue())
        }

        Truth.assertThat(result)
            .containsExactly(
                BranchCoverageKey.Companion.create(1, "", "0"),
                BranchCoverageItem.Companion.create(1, "", "0", true, 1),
                BranchCoverageKey.Companion.create(1, "", "1"),
                BranchCoverageItem.Companion.create(1, "", "1", true, 2),
                BranchCoverageKey.Companion.create(4, "", "0"),
                BranchCoverageItem.Companion.create(4, "", "0", true, 3),
                BranchCoverageKey.Companion.create(4, "", "1"),
                BranchCoverageItem.Companion.create(4, "", "1", true, 6),
                BranchCoverageKey.Companion.create(6, "", "0"),
                BranchCoverageItem.Companion.create(6, "", "0", true, 1),
                BranchCoverageKey.Companion.create(6, "id", "1"),
                BranchCoverageItem.Companion.create(6, "id", "1", true, 0),
                BranchCoverageKey.Companion.create(7, "id", "0"),
                BranchCoverageItem.Companion.create(7, "id", "0", true, 5),
                BranchCoverageKey.Companion.create(7, "id", "1"),
                BranchCoverageItem.Companion.create(7, "id", "1", true, 6)
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContainsKey() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "", "0", false, 0)
        branchCoverage.addBranch(1, "", "1", false, 0)

        Truth.assertThat(branchCoverage.containsKey(1, "", "0")).isTrue()
        Truth.assertThat(branchCoverage.containsKey(1, "", "1")).isTrue()
        Truth.assertThat(branchCoverage.containsKey(1, "", "2")).isFalse()
        Truth.assertThat(branchCoverage.containsKey(1, "1", "0")).isFalse()
        Truth.assertThat(branchCoverage.containsKey(2, "", "0")).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetKeys() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        branchCoverage.addBranch(1, "1", "0", false, 0)
        branchCoverage.addBranch(1, "1", "1", false, 0)
        branchCoverage.addBranch(4, "", "0", true, 0)
        branchCoverage.addBranch(4, "", "1", true, 2)
        branchCoverage.addBranch(4, "", "2", true, 1)
        branchCoverage.addBranch(7, "id", "0", false, 0)
        branchCoverage.addBranch(7, "id", "1", false, 0)
        branchCoverage.addBranch(7, "id", "2", false, 0)

        Truth.assertThat(branchCoverage.getKeys())
            .containsExactly(
                BranchCoverageKey.Companion.create(1, "1", "0"),
                BranchCoverageKey.Companion.create(1, "1", "1"),
                BranchCoverageKey.Companion.create(4, "", "0"),
                BranchCoverageKey.Companion.create(4, "", "1"),
                BranchCoverageKey.Companion.create(4, "", "2"),
                BranchCoverageKey.Companion.create(7, "id", "0"),
                BranchCoverageKey.Companion.create(7, "id", "1"),
                BranchCoverageKey.Companion.create(7, "id", "2")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtremeLineNumbers() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        val blockId = "abcdefghijklmnopqrstuvwxyz"
        branchCoverage.addBranch(java.lang.Integer.MAX_VALUE, blockId, "1234567890", false, 0)
        branchCoverage.addBranch(java.lang.Integer.MAX_VALUE, blockId, "12345678901", false, 0)

        Truth.assertThat(branchCoverage.get(java.lang.Integer.MAX_VALUE, blockId, "1234567890"))
            .isEqualTo(
                BranchCoverageItem.Companion.create(
                    java.lang.Integer.MAX_VALUE,
                    blockId,
                    "1234567890",
                    false,
                    0
                )
            )
        Truth.assertThat(branchCoverage.get(java.lang.Integer.MAX_VALUE, blockId, "12345678901"))
            .isEqualTo(
                BranchCoverageItem.Companion.create(
                    java.lang.Integer.MAX_VALUE,
                    blockId,
                    "12345678901",
                    false,
                    0
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLargeNumberOfBranches() {
        val branchCoverage: BranchCoverage = BranchCoverage.Companion.create()
        for (i in 0..99999) {
            val lineNumber = (i / 100) + 1
            branchCoverage.addBranch(lineNumber, "", java.lang.String.valueOf(i), false, 0)
        }

        Truth.assertThat(branchCoverage.size()).isEqualTo(100000)
        for (i in 0..99999) {
            val lineNumber = (i / 100) + 1
            Truth.assertThat(branchCoverage.get(lineNumber, "", java.lang.String.valueOf(i)))
                .isEqualTo(BranchCoverageItem.Companion.create(lineNumber, "", java.lang.String.valueOf(i), false, 0))
        }
    }
}
