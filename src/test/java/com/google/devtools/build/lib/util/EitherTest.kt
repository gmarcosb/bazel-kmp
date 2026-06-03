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
package com.google.devtools.build.lib.util

import com.google.common.testing.EqualsTester
import com.google.devtools.common.options.testing.ConverterTester.addEqualityGroup
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito

/**
 * Tests for [Either].
 */
@RunWith(JUnit4::class)
class EitherTest {
    @org.junit.Test
    fun leftConsume() {
        val underTest: Either<Int?, String?> = Either.ofLeft(42)

        val mockIntegerConsumer: java.util.function.Consumer<Int?>? =
            Mockito.mock<java.util.function.Consumer<*>?>(java.util.function.Consumer::class.java)
        val mockStringConsumer: java.util.function.Consumer<String?>? =
            Mockito.mock<java.util.function.Consumer<*>?>(java.util.function.Consumer::class.java)
        underTest.consume(mockIntegerConsumer, mockStringConsumer)

        Mockito.verify<java.util.function.Consumer<Int?>?>(mockIntegerConsumer, Mockito.times(1))
            .accept(ArgumentMatchers.eq(42))
        Mockito.verify<java.util.function.Consumer<String?>?>(mockStringConsumer, Mockito.never())
            .accept(ArgumentMatchers.any<String?>())
    }

    @org.junit.Test
    fun leftMap() {
        val underTest: Either<Int?, String?> = Either.ofLeft(42)

        val mockIntegerFunction: java.util.function.Function<Int?, Boolean?>
        Function > Mockito.mock<java.util.function.Function<*, *>?>(java.util.function.Function::class.java)
        val mockStringFunction: java.util.function.Function<String?, Boolean?>?
        Function > Mockito.mock<java.util.function.Function<*, *>?>(java.util.function.Function::class.java)
        Boolean > Mockito.`when`<Boolean?>(mockIntegerFunction.apply(ArgumentMatchers.eq(42))).thenReturn(true)
        assertThat(underTest.map(mockIntegerFunction, mockStringFunction)).isTrue()

        Mockito.verify<java.util.function.Function<Int?, Boolean?>?>(mockIntegerFunction, Mockito.times(1))
            .apply(ArgumentMatchers.eq(42))
        Mockito.verify<java.util.function.Function<String?, Boolean?>?>(mockStringFunction, Mockito.never())
            .apply(TODO("Cannot convert element"))<String> ArgumentMatchers . any < kotlin . Any ? > ()
    }

    @org.junit.Test
    fun rightConsume() {
        val underTest: Either<Int?, String?> = Either.ofRight("cat")

        val mockIntegerConsumer: java.util.function.Consumer<Int?>? =
            Mockito.mock<java.util.function.Consumer<*>?>(java.util.function.Consumer::class.java)
        val mockStringConsumer: java.util.function.Consumer<String?>? =
            Mockito.mock<java.util.function.Consumer<*>?>(java.util.function.Consumer::class.java)
        underTest.consume(mockIntegerConsumer, mockStringConsumer)

        Mockito.verify<java.util.function.Consumer<Int?>?>(mockIntegerConsumer, Mockito.never())
            .accept(ArgumentMatchers.any<Int?>())
        Mockito.verify<java.util.function.Consumer<String?>?>(mockStringConsumer, Mockito.times(1))
            .accept(ArgumentMatchers.eq<String?>("cat"))
    }

    @org.junit.Test
    fun rightMap() {
        val underTest: Either<Int?, String?> = Either.ofRight("cat")

        val mockIntegerFunction: java.util.function.Function<Int?, Boolean?>?
        Function > Mockito.mock<java.util.function.Function<*, *>?>(java.util.function.Function::class.java)
        val mockStringFunction: java.util.function.Function<String?, Boolean?>
        Function > Mockito.mock<java.util.function.Function<*, *>?>(java.util.function.Function::class.java)
        Boolean > Mockito.`when`<Boolean?>(mockStringFunction.apply(TODO("Cannot convert element"))<String> ArgumentMatchers . eq < kotlin . String ? > ("cat"))
        thenReturn(true)
        assertThat(underTest.map(mockIntegerFunction, mockStringFunction)).isTrue()

        Mockito.verify<java.util.function.Function<Int?, Boolean?>?>(mockIntegerFunction, Mockito.never())
            .apply(TODO("Cannot convert element"))<Integer> ArgumentMatchers . any < kotlin . Any ? > ()

        Mockito.verify<java.util.function.Function<String?, Boolean?>?>(mockStringFunction, Mockito.times(1))
            .apply(TODO("Cannot convert element"))<String> ArgumentMatchers . eq < kotlin . String ? > ("cat")
    }

    @org.junit.Test
    fun equalsAndHashCode() {
        EqualsTester()
            .addEqualityGroup(Either.ofLeft(null), Either.ofLeft(null))
            .addEqualityGroup(Either.ofLeft(1), Either.ofLeft(1))
            .addEqualityGroup(Either.ofLeft(2), Either.ofLeft(2))
            .addEqualityGroup(Either.ofRight(1), Either.ofRight(1))
            .addEqualityGroup(Either.ofRight("cat"), Either.ofRight("cat"))
            .addEqualityGroup(Either.ofRight("dog"), Either.ofRight("dog"))
            .testEquals()
    }
}
