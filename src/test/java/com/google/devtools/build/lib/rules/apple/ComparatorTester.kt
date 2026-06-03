// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.apple

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth
import com.google.devtools.build.lib.rules.apple.ComparatorTester.ICanNotBeCompared
import com.google.errorprone.annotations.CanIgnoreReturnValue
import org.junit.Assert
import org.junit.function.ThrowingRunnable
import kotlin.Any
import kotlin.Array
import kotlin.ClassCastException
import kotlin.Comparable
import kotlin.Comparator
import kotlin.Int
import kotlin.NullPointerException
import kotlin.collections.ArrayList
import kotlin.collections.MutableList

/**
 * Tests that a given comparator (or the implementation of [Comparable]) is correct. To use,
 * repeatedly call [.addEqualityGroup] with sets of objects that should be equal.
 * The calls to [.addEqualityGroup] must be made in sorted order. Then call [ ][.testCompare] to test the comparison. For example:
 * 
 * <pre>`new ComparatorTester()     .addEqualityGroup(1)     .addEqualityGroup(2)     .addEqualityGroup(3)     .testCompare(); `</pre>
 */
class ComparatorTester @kotlin.jvm.JvmOverloads constructor(private val comparator: Comparator<*>? = null) {
    /** The items that we are checking, stored as a sorted set of equivalence classes.  */
    private val equalityGroups: MutableList<MutableList<Any>?>

    /**
     * Creates a new instance that tests the order of objects using the given comparator. Or, if the
     * comparator is `null`, the natural ordering (as defined by [Comparable])
     */
    /**
     * Creates a new instance that tests the order of objects using the natural order (as defined by
     * [Comparable]).
     */
    init {
        this.equalityGroups = ArrayList<MutableList<Any>?>()
    }

    /**
     * Adds a set of objects to the test which should all compare as equal. All of the elements in
     * `objects` must be greater than any element of `objects` in a previous call to
     * [.addEqualityGroup].
     * 
     * @return `this` (to allow chaining of calls)
     */
    @CanIgnoreReturnValue
    fun addEqualityGroup(vararg objects: Any?): ComparatorTester {
        Preconditions.checkNotNull<Array<Any?>?>(objects)
        Preconditions.checkArgument(objects.size > 0, "Array must not be empty")
        equalityGroups.add(ImmutableList.copyOf<Any?>(objects))
        return this
    }

    private fun compare(a: Any, b: Any?): Int {
        val compareValue: Int
        if (comparator == null) {
            compareValue = (a as Comparable<*>).compareTo(b)
        } else {
            compareValue = comparator.compare(a, b)
        }
        return compareValue
    }

    fun testCompare() {
        for (referenceIndex in equalityGroups.indices) {
            for (reference in equalityGroups.get(referenceIndex)!!) {
                testNullCompare(reference)
                testClassCast(reference)
                for (otherIndex in equalityGroups.indices) {
                    for (other in equalityGroups.get(otherIndex)!!) {
                        Truth.assertThat(compare(reference, other))
                            .isEqualTo(Integer.compare(referenceIndex, otherIndex))
                    }
                }
            }
        }
    }

    private fun testNullCompare(obj: Any) {
        // Comparator does not require any specific behavior for null.
        if (comparator == null) {
            Assert.assertThrows<NullPointerException?>(
                "Expected NullPointerException in " + obj + ".compare(null)",
                NullPointerException::class.java,
                ThrowingRunnable { compare(obj, null) })
        }
    }

    private fun testClassCast(obj: Any) {
        if (comparator == null) {
            Assert.assertThrows<ClassCastException?>(
                "Expected ClassCastException in " + obj + ".compareTo(otherObject)",
                ClassCastException::class.java,
                ThrowingRunnable { compare(obj, ICanNotBeCompared.INSTANCE) })
        }
    }

    private object ICanNotBeCompared {
        val INSTANCE: ICanNotBeCompared = ICanNotBeCompared()
    }
}
