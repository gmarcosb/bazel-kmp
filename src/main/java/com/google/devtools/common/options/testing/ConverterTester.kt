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
import com.google.common.testing.EqualsTester
import com.google.common.truth.Truth
import com.google.devtools.common.options.Converter
import com.google.devtools.common.options.OptionsParsingException
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.lang.String
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.Int
import kotlin.collections.ArrayList
import kotlin.collections.LinkedHashSet

/**
 * A tester to confirm that [Converter] instances produce equal results on multiple calls with
 * the same input.
 */
class ConverterTester(@kotlin.jvm.JvmField private val converterClass: Class<out Converter<*>>, conversionContext: Any?) {
    private val converter: Converter<*>
    private val conversionContext: Any?
    private val tester = EqualsTester()
    private val testedInputs = LinkedHashSet<String?>()
    private val inputLists = ArrayList<ImmutableList<String?>>()

    /** Creates a new ConverterTester which will test the given Converter class.  */
    init {
        this.converter = createConverter()
        this.conversionContext = conversionContext
    }

    private fun createConverter(): Converter<*> {
        try {
            return converterClass.getDeclaredConstructor().newInstance()
        } catch (ex: ReflectiveOperationException) {
            throw AssertionError("Failed to create converter", ex)
        }
    }

    /** Returns the class this ConverterTester is testing.  */
    fun getConverterClass(): Class<out Converter<*>> {
        return converterClass
    }

    /**
     * Returns whether this ConverterTester has a test for the given input, i.e., addEqualityGroup
     * was called with the given string.
     */
    fun hasTestForInput(input: String?): Boolean {
        return testedInputs.contains(input)
    }

    /**
     * Adds a set of valid inputs which are expected to convert to equal values.
     * 
     * 
     * The inputs added here will be converted to values using the Converter class passed to the
     * constructor of this instance; the resulting values must be equal (and have equal hashCodes):
     * 
     * 
     *  * to themselves
     *  * to another copy of themselves generated from the same Converter instance
     *  * to another copy of themselves generated from a different Converter instance
     *  * to the other values converted from inputs in the same addEqualityGroup call
     * 
     * 
     * 
     * They must NOT be equal:
     * 
     * 
     *  * to null
     *  * to an instance of an arbitrary class
     *  * to any values converted from inputs in a different addEqualityGroup call
     * 
     * 
     * @throws AssertionError if an [OptionsParsingException] is thrown from the [     ][Converter.convert] method when converting any of the inputs.
     * @see EqualsTester.addEqualityGroup
     */
    @CanIgnoreReturnValue
    fun addEqualityGroup(vararg inputs: String?): ConverterTester {
        val wrapped: ImmutableList.Builder<WrappedItem?> = ImmutableList.builder<WrappedItem?>()
        val inputList = ImmutableList.copyOf<String?>(inputs)
        inputLists.add(inputList)
        for (input in inputList) {
            testedInputs.add(input)
            try {
                wrapped.add(WrappedItem(input, converter.convert(input, conversionContext)))
            } catch (ex: OptionsParsingException) {
                throw AssertionError("Failed to parse input: \"" + input + "\"", ex)
            }
        }
        tester.addEqualityGroup(*wrapped.build().toArray())
        return this
    }

    /**
     * Tests the convert method of the wrapped Converter class, verifying the properties listed in the
     * Javadoc listed for [.addEqualityGroup].
     * 
     * @throws AssertionError if one of the expected properties did not hold up
     * @see EqualsTester.testEquals
     */
    @CanIgnoreReturnValue
    fun testConvert(): ConverterTester {
        tester.testEquals()
        testItems()
        return this
    }

    private fun testItems() {
        for (inputList in inputLists) {
            for (input in inputList) {
                val converter = createConverter()
                val converter2 = createConverter()

                val converted: Any
                val convertedAgain: Any
                val convertedDifferentConverterInstance: Any
                try {
                    converted = converter.convert(input, conversionContext)
                    convertedAgain = converter.convert(input, conversionContext)
                    convertedDifferentConverterInstance = converter2.convert(input, conversionContext)
                } catch (ex: OptionsParsingException) {
                    throw AssertionError("Failed to parse input: \"" + input + "\"", ex)
                }

                Truth.assertWithMessage(
                    "Input \"%s\" was not equal to itself when converted twice by the same Converter",
                    input
                )
                    .that(convertedAgain)
                    .isEqualTo(converted)
                Truth.assertWithMessage(
                    "Input \"%s\" did not have a consistent hashCode when converted twice "
                            + "by the same Converter",
                    input
                )
                    .that(convertedAgain.hashCode())
                    .isEqualTo(converted.hashCode())
                Truth.assertWithMessage(
                    "Input \"%s\" was not equal to itself when converted twice by a different"
                            + " Converter",
                    input
                )
                    .that(convertedDifferentConverterInstance)
                    .isEqualTo(converted)
                Truth.assertWithMessage(
                    "Input \"%s\" did not have a consistent hashCode when converted twice "
                            + "by a different Converter",
                    input
                )
                    .that(convertedDifferentConverterInstance.hashCode())
                    .isEqualTo(converted.hashCode())
            }
        }
    }

    /**
     * A wrapper around the objects passed to EqualsTester to give them a more useful toString() so
     * that the mapping between the input text which actually appears in the source file and the
     * object produced from parsing it is more obvious.
     */
    private class WrappedItem(private val argument: String?, private val wrapped: Any) {
        override fun toString(): String {
            return String.format("Converted input \"%s\" => [%s]", argument, wrapped)
        }

        override fun hashCode(): Int {
            return wrapped.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (other is WrappedItem) {
                return this.wrapped == other.wrapped
            }
            return this.wrapped == other
        }
    }
}
