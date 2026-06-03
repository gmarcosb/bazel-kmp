// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.util

import com.google.common.truth.Truth
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.add
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.addAll
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for [LongArrayList].
 */
@RunWith(JUnit4::class)
class LongArrayListTest {
    private var list: LongArrayList? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createList() {
        list = LongArrayList()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdds() {
        for (i in 0..49) {
            list.add(i)
        }
        for (i in 0..49) {
            Truth.assertThat(i).isEqualTo(list.get(i))
        }
        list.add(25, 42)
        assertThat(list.get(25)).isEqualTo(42)
        assertThat(list.get(26)).isEqualTo(25)
        assertThat(list.get(list.size() - 1)).isEqualTo(49)
        assertThat(list.size()).isEqualTo(51)
        assertThat(list.indexOf(23)).isEqualTo(23)
        assertThat(list.indexOf(28)).isEqualTo(29)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAddAlls() {
        list.addAll(longArrayOf(1, 2, 3, 4, 5), 1, 3)
        assertThat(list.get(0)).isEqualTo(2)
        assertThat(list.get(1)).isEqualTo(3)
        assertThat(list.get(2)).isEqualTo(4)
        assertThat(list.size()).isEqualTo(3)
        list.addAll(longArrayOf(42, 41), 0, 2, 1)
        assertThat(list.get(1)).isEqualTo(42)
        assertThat(list.get(2)).isEqualTo(41)
        assertThat(list.get(3)).isEqualTo(3)
        assertThat(list.get(4)).isEqualTo(4)
        assertThat(list.size()).isEqualTo(5)
        val other: LongArrayList = LongArrayList(longArrayOf(5, 6, 7))
        list.addAll(other, list.size())
        assertThat(list.get(1)).isEqualTo(42)
        assertThat(list.get(4)).isEqualTo(4)
        assertThat(list.get(5)).isEqualTo(5)
        assertThat(list.get(6)).isEqualTo(6)
        assertThat(list.get(7)).isEqualTo(7)
        assertThat(list.size()).isEqualTo(8)
        list.addAll(LongArrayList())
        assertThat(list.size()).isEqualTo(8)
        list.addAll(longArrayOf())
        assertThat(list.size()).isEqualTo(8)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSet() {
        list.addAll(longArrayOf(1, 2, 3))
        list.set(1, 42)
        assertThat(list.get(1)).isEqualTo(42)
        assertThat(list.size()).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSort() {
        list = LongArrayList(longArrayOf(3, 2, 1))
        list.sort()
        assertThat(list.get(0)).isEqualTo(1)
        assertThat(list.get(1)).isEqualTo(2)
        assertThat(list.get(2)).isEqualTo(3)
        list.addAll(longArrayOf(-5, -2))
        list.sort(2, 5)
        assertThat(list.get(2)).isEqualTo(-5)
        assertThat(list.get(3)).isEqualTo(-2)
        assertThat(list.get(4)).isEqualTo(3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveByIndex() {
        val last = 32
        for (i in 0..last) {
            list.add(i)
        }
        var removed: Long = list.remove(last)
        Truth.assertThat(removed).isEqualTo(last)
        assertThat(list.size()).isEqualTo(last)
        removed = list.remove(0)
        Truth.assertThat(removed).isEqualTo(0)
        assertThat(list.get(0)).isEqualTo(1)
        assertThat(list.get(last - 2)).isEqualTo(last - 1)
        assertThat(list.size()).isEqualTo(last - 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveByValue() {
        val last = 19
        for (i in 0..last) {
            list.add(i)
        }
        var removed: Boolean = list.remove(last.toLong())
        Truth.assertThat(removed).isTrue()
        assertThat(list.size()).isEqualTo(last)
        assertThat(list.get(last - 1)).isEqualTo(last - 1)
        removed = list.remove(3L)
        Truth.assertThat(removed).isTrue()
        assertThat(list.get(0)).isEqualTo(0)
        assertThat(list.get(last - 2)).isEqualTo(last - 1)
        assertThat(list.size()).isEqualTo(last - 1)
        removed = list.remove(42L)
        Truth.assertThat(removed).isFalse()
        assertThat(list.size()).isEqualTo(last - 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnsureCapacity() {
        val last = 65
        for (i in 0..last) {
            list.add(i)
        }
        list.ensureCapacity(512)
        assertThat(list.size()).isEqualTo(last + 1)
        assertThat(list.get(0)).isEqualTo(0)
        assertThat(list.get(last)).isEqualTo(last)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveExceptionEmpty() {
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { list.remove(0) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRemoveExceptionFilled() {
        for (i in 0..14) {
            list.add(i)
        }
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { list.remove(15) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetException() {
        for (i in 0..14) {
            list.add(i)
        }
        org.junit.Assert.assertThrows<java.lang.IndexOutOfBoundsException?>(
            java.lang.IndexOutOfBoundsException::class.java,
            org.junit.function.ThrowingRunnable { list.get(15) })
    }
}
