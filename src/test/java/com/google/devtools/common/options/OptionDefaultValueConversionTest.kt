// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

import com.google.devtools.build.lib.util.Classpath

/**
 * Test to make sure all [Option]-annotated fields in *Prod* code have an [ ][Option.defaultValue] that a corresponding [Option.converter] can handle.<br></br>
 * [Option]-annotated field is considered to be in *Prod* code if its declaring class and
 * all its enclosing classes do not have [RunWith] annotation.
 * 
 * @see OptionDefinition.getDefaultValue
 */
@RunWith(org.junit.runners.Parameterized::class)
class OptionDefaultValueConversionTest {
    @org.junit.Rule
    var thrown: org.junit.rules.ExpectedException = org.junit.rules.ExpectedException.none()

    @org.junit.runners.Parameterized.Parameter
    var optionDefinitionUnderTest: OptionDefinition? = null

    @org.junit.Test
    fun shouldConvertDefaultValue() {
        // assert
        thrown = org.junit.rules.ExpectedException.none()

        // act
        optionDefinitionUnderTest.getDefaultValue( /*conversionContext=*/null)
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @get:org.junit.runners.Parameterized.Parameters
        val allProdOptionDefinitions: MutableList<OptionDefinition>
            get() {
                try {
                    val allClasses: MutableSet<java.lang.Class<*>?> =
                        Classpath.findClasses("com.google.devtools")

                    val optionDefinitions: MutableList<OptionDefinition?> =
                        allClasses.stream() // This package contains classes that reference other classes that aren't available
                            // without manual setup.
                            .filter { c: java.lang.Class<*>? -> c.getPackageName() != "com.google.devtools.build.lib.profiler.memory" }
                            .filter { c: java.lang.Class<*>? ->
                                !isTestClass(
                                    c
                                )
                            }
                            .flatMap<java.lang.reflect.Method?> { c: java.lang.Class<*>? ->
                                java.util.Arrays.stream<java.lang.reflect.Method?>(
                                    c.getMethods()
                                )
                            }
                            .filter { f: java.lang.reflect.Method? -> f.isAnnotationPresent(com.google.devtools.common.options.Option::class.java) }
                            .map<MethodOptionDefinition?> { method: java.lang.reflect.Method? ->
                                MethodOptionDefinition.from(
                                    method
                                )
                            }
                            .collect(Collectors.toList())
                    logger.atFine().log(
                        "Found %d Option-annotated fields in Prod code", optionDefinitions.size
                    )

                    return optionDefinitions
                } catch (ex: ClassPathException) {
                    throw java.lang.RuntimeException("Unable to scan classpath", ex)
                }
            }

        private fun isTestClass(initialClazz: java.lang.Class<*>?): Boolean {
            var clazz: java.lang.Class<*>? = initialClazz
            do {
                if (clazz.isAnnotationPresent(RunWith::class.java)) {
                    logger.atFiner().log("Filtered out %s: is a Test class", initialClazz)
                    return true
                }
                clazz = clazz.getEnclosingClass()
            } while (clazz != null)

            return false
        }
    }
}
