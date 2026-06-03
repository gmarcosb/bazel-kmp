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

import org.junit.Assert
import org.junit.Test

/** Tests to exercise the functionality of [ConverterTester].  */
@RunWith(JUnit4::class)
class ConverterTesterTest {
    @Test
    @Throws(Exception::class)
    fun construction_throwsAssertionErrorIfConverterCreationFails() {
        try {
            ConverterTester(UnconstructableConverter::class.java,  /*conversionContext=*/null)
        } catch (expected: AssertionError) {
            Truth.assertThat(expected) // AssertionError
                .hasCauseThat() // InvocationTargetException
                .hasCauseThat() // UnsupportedOperationException
                .hasMessageThat()
                .contains("YOU CAN'T MAKE ME!")
            return
        }
        Assert.fail("expected tester creation to fail")
    }

    /** Test converter for construction_throwsAssertionErrorIfConverterCreationFails.  */
    class UnconstructableConverter : Contextless<String?>() {
        init {
            throw UnsupportedOperationException("YOU CAN'T MAKE ME!")
        }

        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): String? {
            return input
        }

        val typeDescription: String
            get() = "anything, if you can get an instance"
    }

    @Test
    @Throws(Exception::class)
    fun getConverterClass_returnsConstructorArg() {
        val tester =
            ConverterTester(Converters.BooleanConverter::class.java,  /*conversionContext=*/null)
        Truth.assertThat(tester.converterClass).isEqualTo(Converters.BooleanConverter::class.java)
    }

    @Test
    @Throws(Exception::class)
    fun hasTestForInput_returnsTrueIffInputPassedToAddEqualityGroup() {
        val tester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("1.0", "1", "1.00")
                .addEqualityGroup("2")

        Truth.assertThat(tester.hasTestForInput("1.0")).isTrue()
        Truth.assertThat(tester.hasTestForInput("1")).isTrue()
        Truth.assertThat(tester.hasTestForInput("1.00")).isTrue()
        Truth.assertThat(tester.hasTestForInput("2")).isTrue()

        Truth.assertThat(tester.hasTestForInput("3")).isFalse()
        Truth.assertThat(tester.hasTestForInput("1.000")).isFalse()
        Truth.assertThat(tester.hasTestForInput("not a double")).isFalse()
    }

    @Test
    @Throws(Exception::class)
    fun addEqualityGroup_throwsIfConversionFails() {
        val tester =
            ConverterTester(ThrowingConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("okay")
                .addEqualityGroup("also okay", "pretty fine")
        try {
            tester.addEqualityGroup("wrong")
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"wrong\"")
            Truth.assertThat(expected).hasCauseThat().hasMessageThat().contains("HOW DARE YOU")
            return
        }
        Assert.fail("expected addEqualityGroup to fail")
    }

    /** Test converter for addEqualityGroup_throwsIfConversionFails.  */
    class ThrowingConverter : Contextless<String?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): String? {
            if ("wrong" == input) {
                throw OptionsParsingException("HOW DARE YOU")
            }
            return input
        }

        val typeDescription: String
            get() = "just don't give the wrong answer"
    }

    @Test
    fun testConvert_passesWhenAllInstancesObeyEqualsAndItemsOnlyEqualToOthersInSameGroup() {
        ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
            .addEqualityGroup("1.0", "1", "1.00")
            .addEqualityGroup("2", "2", "2.0000", "2.0", "+2")
            .addEqualityGroup("3")
            .addEqualityGroup("3.1415")
            .testConvert()
    }

    @Test
    fun testConvert_testsHashCodeConsistencyForConvertedInstance() {
        val tester =
            ConverterTester(InconsistentHashCodeConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("input doesn't matter")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"input doesn't matter\"")
            Truth.assertThat(expected).hasMessageThat().contains("hashCode")
            Truth.assertThat(expected).hasMessageThat().contains("must be consistent")
            return
        }
        Assert.fail("expected the tester to notice the bad hash code implementation")
    }

    /** A class with a badly implemented hashCode which is not consistent across calls.  */
    class InconsistentHashCode {
        override fun equals(other: Any?): Boolean {
            return other is InconsistentHashCode
        }

        private var howManyTimesHaveIBeenHashedAlready = 0

        override fun hashCode(): Int {
            howManyTimesHaveIBeenHashedAlready += 1
            return howManyTimesHaveIBeenHashedAlready
        }
    }

    /** Test converter for testConvert_testsHashCodeConsistencyForConvertedInstance.  */
    class InconsistentHashCodeConverter

        : Contextless<InconsistentHashCode?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): InconsistentHashCode {
            return InconsistentHashCode()
        }

        val typeDescription: String
            get() = "anything, I don't even look at it"
    }

    @Test
    fun testConvert_testsHashCodeConsistencyForSameConverter() {
        val tester =
            ConverterTester(IncrementingHashCodeConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("meaningless input")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"meaningless input\"")
            Truth.assertThat(expected).hasMessageThat().contains("consistent hashCode")
            Truth.assertThat(expected).hasMessageThat().contains("same Converter")
            return
        }
        Assert.fail("expected the tester to notice the mismatched hash codes")
    }

    /** A class with a configurable hashCode set in the constructor.  */
    class SettableHashCode(private val hashCode: Int) {
        override fun equals(other: Any?): Boolean {
            return other is SettableHashCode
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }

    /** Test converter for testConvert_testsHashCodeConsistencyForSameConverter.  */
    class IncrementingHashCodeConverter

        : Contextless<SettableHashCode?>() {
        private var howManyInstancesHaveIMadeAlready = 0

        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): SettableHashCode {
            howManyInstancesHaveIMadeAlready += 1
            return SettableHashCode(howManyInstancesHaveIMadeAlready)
        }

        val typeDescription: String
            get() = "whatever, I'm pretty much just going to ignore it"
    }

    @Test
    fun testConvert_testsHashCodeConsistencyForDifferentConverters() {
        val tester =
            ConverterTester(StaticIncrementingHashCodeConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("some kind of input")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"some kind of input\"")
            Truth.assertThat(expected).hasMessageThat().contains("consistent hashCode")
            Truth.assertThat(expected).hasMessageThat().contains("different Converter")
            return
        }
        Assert.fail("expected the tester to notice the mismatched hash codes")
    }

    /** Test converter for testConvert_testsHashCodeConsistencyForDifferentConverters.  */
    class StaticIncrementingHashCodeConverter

        : Contextless<SettableHashCode?>() {
        private val hashCode: Int

        init {
            howManyInstancesHaveIMadeAlready += 1
            this.hashCode = howManyInstancesHaveIMadeAlready
        }

        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): SettableHashCode {
            return SettableHashCode(hashCode)
        }

        val typeDescription: String
            get() = "a string or null, I'm easy"

        companion object {
            private var howManyInstancesHaveIMadeAlready = 0
        }
    }


    @Test
    fun testConvert_testsSelfEqualityForConvertedInstance() {
        val tester =
            ConverterTester(SelfLoathingConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("self-loathing")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"self-loathing\"")
            Truth.assertThat(expected).hasMessageThat().contains("must be Object#equals to itself")
            return
        }
        Assert.fail("expected the tester to notice the bad equals implementation")
    }

    /** A class which is equal to every instance of its class except itself.  */
    class SelfLoathingObject {
        override fun equals(other: Any?): Boolean {
            return other is SelfLoathingObject && other !== this
        }

        override fun hashCode(): Int {
            return 4 // chosen by fair hashing algorithm
        }
    }

    /** Test converter for testConvert_testsSelfEqualityForConvertedInstance.  */
    class SelfLoathingConverter

        : Contextless<SelfLoathingObject?>() {
        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): SelfLoathingObject {
            return SelfLoathingObject()
        }

        val typeDescription: String
            get() = "whatever... why even ask me to convert anything.............."
    }

    @Test
    fun testConvert_testsEqualityForSameConverter() {
        val tester =
            ConverterTester(CountingConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("countables")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"countables\"")
            Truth.assertThat(expected).hasMessageThat().contains("equal to itself")
            Truth.assertThat(expected).hasMessageThat().contains("same Converter")
            return
        }
        Assert.fail("expected the tester to notice the converter giving unequal objects")
    }

    /** Test converter for testConvert_testsEqualityForSameConverter.  */
    class CountingConverter : Contextless<Int?>() {
        private var howManyInstancesHaveIMadeAlready = 0

        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): Int {
            howManyInstancesHaveIMadeAlready += 1
            return howManyInstancesHaveIMadeAlready
        }

        val typeDescription: String
            get() = "I can count anything!"
    }

    @Test
    fun testConvert_testsEqualityForDifferentConverters() {
        val tester =
            ConverterTester(StaticCountingConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("words I like")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"words I like\"")
            Truth.assertThat(expected).hasMessageThat().contains("equal to itself")
            Truth.assertThat(expected).hasMessageThat().contains("different Converter")
            return
        }
        Assert.fail("expected the tester to notice the converters giving unequal objects")
    }

    /** Test converter for testConvert_testsEqualityForDifferentConverters.  */
    class StaticCountingConverter : Contextless<Int?>() {
        private val output: Int

        init {
            howManyInstancesHaveIMadeAlready += 1
            this.output = howManyInstancesHaveIMadeAlready
        }

        @Throws(OptionsParsingException::class)
        public override fun convert(input: String?): Int {
            return output
        }

        val typeDescription: String
            get() = "your favorite text"

        companion object {
            private var howManyInstancesHaveIMadeAlready = 0
        }
    }

    @Test
    fun testConvert_testsEqualityForItemsInSameGroup() {
        val tester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("+1.000", "2.30")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"+1.000\"")
            Truth.assertThat(expected).hasMessageThat().contains("\"2.30\"")
            return
        }
        Assert.fail("expected the tester to notice the two non-equal conversion results in the same group")
    }

    @Test
    fun testConvert_testsNonEqualityForItemsInDifferentGroups() {
        val tester =
            ConverterTester(Converters.DoubleConverter::class.java,  /*conversionContext=*/null)
                .addEqualityGroup("+1.000")
                .addEqualityGroup("1.0")
        try {
            tester.testConvert()
        } catch (expected: AssertionError) {
            Truth.assertThat(expected).hasMessageThat().contains("\"+1.000\"")
            Truth.assertThat(expected).hasMessageThat().contains("\"1.0\"")
            return
        }
        Assert.fail("expected the tester to notice the two equal conversion results in different groups")
    }
}
