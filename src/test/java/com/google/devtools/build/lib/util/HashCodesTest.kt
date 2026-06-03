// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.vfs.bazel.Blake3Hasher.hash
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class HashCodesTest {
    @org.junit.Test
    fun hashObject_returnsHashCode() {
        assertThat(HashCodes.hashObject(O_1)).isEqualTo(O_1.hashCode())
    }

    @org.junit.Test
    fun hashNull_returnsZero() {
        assertThat(HashCodes.hashObject(null)).isEqualTo(0)
    }

    @org.junit.Test
    fun hashTwoObjects_sameAsObjectsHash(
        @TestParameter isFirstNull: Boolean, @TestParameter isSecondNull: Boolean
    ) {
        val s1: Any? = if (isFirstNull) null else O_1
        val s2: Any? = if (isSecondNull) null else O_2
        assertThat(HashCodes.hashObjects(s1, s2)).isEqualTo(java.util.Objects.hash(s1, s2))
    }

    @org.junit.Test
    fun hashThreeObjects_sameAsObjectsHash(
        @TestParameter isFirstNull: Boolean,
        @TestParameter isSecondNull: Boolean,
        @TestParameter isThirdNull: Boolean
    ) {
        val s1: Any? = if (isFirstNull) null else O_1
        val s2: Any? = if (isSecondNull) null else O_2
        val s3: Any? = if (isThirdNull) null else O_3
        assertThat(HashCodes.hashObjects(s1, s2, s3)).isEqualTo(java.util.Objects.hash(s1, s2, s3))
    }

    @org.junit.Test
    fun hashFourObjects_sameAsObjectsHash(
        @TestParameter isFirstNull: Boolean,
        @TestParameter isSecondNull: Boolean,
        @TestParameter isThirdNull: Boolean,
        @TestParameter isFourthNull: Boolean
    ) {
        val s1: Any? = if (isFirstNull) null else O_1
        val s2: Any? = if (isSecondNull) null else O_2
        val s3: Any? = if (isThirdNull) null else O_3
        val s4: Any? = if (isFourthNull) null else O_4
        assertThat(HashCodes.hashObjects(s1, s2, s3, s4)).isEqualTo(java.util.Objects.hash(s1, s2, s3, s4))
    }

    @org.junit.Test
    fun hashFiveObjects_sameAsObjectsHash(
        @TestParameter isFirstNull: Boolean,
        @TestParameter isSecondNull: Boolean,
        @TestParameter isThirdNull: Boolean,
        @TestParameter isFourthNull: Boolean,
        @TestParameter isFifthNull: Boolean
    ) {
        val s1: Any? = if (isFirstNull) null else O_1
        val s2: Any? = if (isSecondNull) null else O_2
        val s3: Any? = if (isThirdNull) null else O_3
        val s4: Any? = if (isFourthNull) null else O_4
        val s5: Any? = if (isFifthNull) null else O_5
        assertThat(HashCodes.hashObjects(s1, s2, s3, s4, s5))
            .isEqualTo(java.util.Objects.hash(s1, s2, s3, s4, s5))
    }

    companion object {
        private const val O_1 = "one"
        private const val O_2 = "two"
        private const val O_3 = "three"
        private const val O_4 = "four"
        private const val O_5 = "five"
    }
}
