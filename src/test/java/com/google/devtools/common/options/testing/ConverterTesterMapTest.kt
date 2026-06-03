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
package com.google.devtools.common.options.testing

import com.google.common.collect.ImmutableList
import com.google.devtools.common.options.Converters
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable

/** Tests for the ConverterTesterMap map builder.  */
@RunWith(JUnit4::class)
class ConverterTesterMapTest {
    @Test
    @Throws(Exception::class)
    fun add_mapsTestedConverterClassToTester() {
        val stringTester =
            ConverterTester(Converters.StringConverter::class.java,  /*conversionContext=*/null)
        val intTester =
            ConverterTester(Converters.IntegerConverter::class.java,  /*conversionContext=*/null)
        val doubleTester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
        val booleanTester =
            ConverterTester(Converters.BooleanConverter::class.java,  /*conversionContext=*/null)
        val map =
            ConverterTesterMap.Builder()
                .add(stringTester)
                .add(intTester)
                .add(doubleTester)
                .add(booleanTester)
                .build()
        Truth.assertThat(map)
            .containsExactly(
                Converters.StringConverter::class.java,
                stringTester,
                Converters.IntegerConverter::class.java,
                intTester,
                Converters.DoubleConverter::class.java,
                doubleTester,
                Converters.BooleanConverter::class.java,
                booleanTester
            )
    }

    @Test
    @Throws(Exception::class)
    fun addAll_mapsTestedConverterClassesToTester() {
        val stringTester =
            ConverterTester(Converters.StringConverter::class.java,  /*conversionContext=*/null)
        val intTester =
            ConverterTester(Converters.IntegerConverter::class.java,  /*conversionContext=*/null)
        val doubleTester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
        val booleanTester =
            ConverterTester(Converters.BooleanConverter::class.java,  /*conversionContext=*/null)
        val map =
            ConverterTesterMap.Builder()
                .addAll(ImmutableList.of<ConverterTester?>(stringTester, intTester, doubleTester, booleanTester))
                .build()
        Truth.assertThat(map)
            .containsExactly(
                Converters.StringConverter::class.java,
                stringTester,
                Converters.IntegerConverter::class.java,
                intTester,
                Converters.DoubleConverter::class.java,
                doubleTester,
                Converters.BooleanConverter::class.java,
                booleanTester
            )
    }

    @Test
    @Throws(Exception::class)
    fun addAll_dumpsConverterTesterMapIntoNewMap() {
        val stringTester =
            ConverterTester(Converters.StringConverter::class.java,  /*conversionContext=*/null)
        val intTester =
            ConverterTester(Converters.IntegerConverter::class.java,  /*conversionContext=*/null)
        val doubleTester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
        val booleanTester =
            ConverterTester(Converters.BooleanConverter::class.java,  /*conversionContext=*/null)
        val baseMap =
            ConverterTesterMap.Builder()
                .addAll(ImmutableList.of<ConverterTester?>(stringTester, intTester, doubleTester))
                .build()
        val map =
            ConverterTesterMap.Builder().addAll(baseMap).add(booleanTester).build()
        Truth.assertThat(map)
            .containsExactly(
                Converters.StringConverter::class.java,
                stringTester,
                Converters.IntegerConverter::class.java,
                intTester,
                Converters.DoubleConverter::class.java,
                doubleTester,
                Converters.BooleanConverter::class.java,
                booleanTester
            )
    }

    @Test
    @Throws(Exception::class)
    fun build_forbidsDuplicates() {
        val builder =
            ConverterTesterMap.Builder()
                .add(ConverterTester(Converters.StringConverter::class.java,  /*conversionContext=*/null))
                .add(
                    ConverterTester(Converters.IntegerConverter::class.java,  /*conversionContext=*/null)
                )
                .add(ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null))
                .add(
                    ConverterTester(Converters.BooleanConverter::class.java,  /*conversionContext=*/null)
                )
                .add(
                    ConverterTester(
                        Converters.BooleanConverter::class.java,  /*conversionContext=*/null
                    )
                )

        val expected =
            Assert.assertThrows<IllegalArgumentException?>(
                IllegalArgumentException::class.java,
                ThrowingRunnable { builder.build() })
        Truth.assertThat(expected)
            .hasMessageThat()
            .contains(Converters.BooleanConverter::class.java.getSimpleName())
    }
}
