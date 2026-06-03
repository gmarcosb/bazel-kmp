// Copyright 2011 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.util

import com.google.testing.junit.runner.junit4.JUnit4Bazel.runner
import com.google.testing.junit.runner.util.TestPropertyExporter
import com.google.testing.junit.runner.util.TestPropertyRunnerIntegration
import java.util.HashMap

/**
 * Exports test properties to the test XML.
 */
class TestPropertyExporter protected constructor(private val callback: Callback) {
    /**
     * Exports a property to the test runner. This method is a no-op unless called
     * by the thread running the current test.
     * 
     * @param name The property name.
     * @param value The property value.
     * @throws IllegalArgumentException if the name is not a valid name
     */
    fun exportProperty(name: String?, value: String?) {
        callback.exportProperty(name, value)
    }

    /**
     * Exports a property to the test runner by adding the value to the list of values for the
     * given property name.
     * When the properties get written to the XML, each name will have a numeric value appended to it
     * that is guaranteed to be unique for the given test case.
     * This method is a no-op unless called by the thread running the current test.
     * 
     * @param name The property name.
     * @param value The property value.
     * @return the name of the property that was exported
     * @throws IllegalArgumentException if the name is not a valid name
     */
    fun exportRepeatedProperty(name: String?, value: String?): String? {
        return callback.exportRepeatedProperty(name, value)
    }

    /**
     * Callback that is used to store test properties.
     */
    interface Callback {
        /**
         * Export the property.
         * 
         * @param name The property name.
         * @param value The property value.
         */
        fun exportProperty(name: String?, value: String?)

        /**
         * Export the property with an incrementing numeric suffix.
         * 
         * @param name The property name.
         * @param value The property value.
         * @return the name of the property that was exported
         */
        fun exportRepeatedProperty(name: String?, value: String?): String?
    }


    /**
     * Default callback implementation.
     * Calls the test runner to write the property to the XML.
     */
    private class DefaultCallback : Callback {
        override fun exportProperty(name: String?, value: String?) {
            TestPropertyRunnerIntegration.getCallbackForThread().exportProperty(name, value)
        }

        override fun exportRepeatedProperty(name: String?, value: String?): String? {
            return TestPropertyRunnerIntegration.getCallbackForThread().exportRepeatedProperty(name, value)
        }
    }

    companion object {
        /**
         * The global `TestPropertyExporter`, which writes the properties into the test XML if the
         * test is running from the command line.
         * 
         * 
         * If you have test infrastructure that needs to export properties, consider injecting an
         * instance of `TestPropertyExporter`. Your tests can use one of the static methods in this
         * class to create a fake instance.
         */
        val INSTANCE: TestPropertyExporter =
            TestPropertyExporter(com.google.testing.junit.runner.util.TestPropertyExporter.DefaultCallback())

        // Set to 1000 so that it will play nice with code that doesn't use exportRepeatedProperty
        // yet.
        const val INITIAL_INDEX_FOR_REPEATED_PROPERTY: Int = 1000

        /**
         * Creates a fake `TestPropertyExporter` instance, storing values
         * in the passed-in map.
         * 
         * @param backingMap Map to use to store values
         * @return exporter instance
         */
        fun createFake(backingMap: MutableMap<String?, String?>): TestPropertyExporter {
            return createFake(object : Callback {
                private val repeatedPropertyNamesToRepetitions: MutableMap<String?, Int?> = HashMap<String?, Int?>()

                override fun exportProperty(name: String?, value: String?) {
                    backingMap.put(name, value)
                }

                override fun exportRepeatedProperty(name: String, value: String?): String {
                    val propertyName = getRepeatedPropertyName(name)
                    backingMap.put(propertyName, value)
                    return propertyName
                }

                fun getRepeatedPropertyName(name: String): String {
                    val index = (addNameToRepeatedPropertyNamesAndGetRepetitionsNr(name)
                            + INITIAL_INDEX_FOR_REPEATED_PROPERTY)
                    return name + index
                }

                @kotlin.jvm.Synchronized
                fun addNameToRepeatedPropertyNamesAndGetRepetitionsNr(name: String?): Int {
                    var previousRepetitionsNr = repeatedPropertyNamesToRepetitions.get(name)
                    if (previousRepetitionsNr == null) {
                        previousRepetitionsNr = 0
                    }
                    repeatedPropertyNamesToRepetitions.put(name, previousRepetitionsNr + 1)
                    return previousRepetitionsNr
                }
            })
        }

        /**
         * Creates a fake `TestPropertyExporter` instance, passing values
         * to the passed-in callback.
         * 
         * @param callback Callback to use when values are exported
         * @return exporter instance
         */
        fun createFake(callback: Callback): TestPropertyExporter {
            return TestPropertyExporter(callback)
        }
    }
}
