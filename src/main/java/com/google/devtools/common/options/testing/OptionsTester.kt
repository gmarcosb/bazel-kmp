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
import com.google.common.collect.ImmutableListMultimap
import com.google.common.truth.Truth
import com.google.devtools.common.options.Converter
import com.google.devtools.common.options.Option
import com.google.devtools.common.options.OptionsBase
import com.google.errorprone.annotations.CanIgnoreReturnValue
import java.lang.reflect.AccessibleObject
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * A tester to validate certain useful properties of OptionsBase subclasses. These are not required
 * for parsing options in these classes, but can be helpful for e.g. ensuring that equality is not
 * violated.
 */
class OptionsTester(private val optionsClass: Class<out OptionsBase?>) {
    /**
     * Tests that there are no non-Option instance fields. Fields not annotated with @Option will not
     * be considered for equality.
     */
    @CanIgnoreReturnValue
    fun testAllOptions(): OptionsTester {
        for (field in getAllFields(optionsClass)) {
            if (!Modifier.isStatic(field.getModifiers())) {
                Truth.assertWithMessage(
                    "%s is missing an @Option annotation; it will not be considered for equality.",
                    field
                )
                    .that(field.getAnnotation<Option?>(Option::class.java))
                    .isNotNull()
            }
        }
        for (method in getAllMethods(optionsClass)) {
            if (Modifier.isAbstract(method.getModifiers()) && method.getName().startsWith("get")) {
                Truth.assertWithMessage(
                    "%s is missing an @Option annotation; it will not be considered for equality.",
                    method
                )
                    .that(method.getAnnotation<Option?>(Option::class.java))
                    .isNotNull()
            }
        }
        return this
    }

    /**
     * Tests that the default values of this class were part of the test data for the appropriate
     * ConverterTester, ensuring that the defaults at least obey proper equality semantics.
     * 
     * 
     * The default converters are not tested in this way.
     * 
     * 
     * Note that testConvert is not actually run on the ConverterTesters; it is expected that they
     * are run elsewhere.
     */
    @CanIgnoreReturnValue
    fun testAllDefaultValuesTestedBy(testers: ConverterTesterMap): OptionsTester {
        val converterClassesBuilder = ImmutableListMultimap.builder<Class<out Converter<*>?>?, AccessibleObject?>()
        for (field in getAllFields(optionsClass)) {
            val option = field.getAnnotation<Option?>(Option::class.java)
            if (option != null && Converter::class.java != option.converter) {
                val converter// converter is rawtyped; see comment on Option.converter()
                        =
                    option.converter as Class<out Converter<*>?>
                converterClassesBuilder.put(converter, field)
            }
        }
        for (method in getAllMethods(optionsClass)) {
            val option = method.getAnnotation<Option?>(Option::class.java)
            if (option != null && Converter::class.java != option.converter) {
                val converter// converter is rawtyped; see comment on Option.converter()
                        =
                    option.converter as Class<out Converter<*>?>
                converterClassesBuilder.put(converter, method)
            }
        }
        val converterClasses =
            converterClassesBuilder.build()
        for (converter in converterClasses.keySet()) {
            Truth.assertWithMessage(
                "Converter %s has no corresponding ConverterTester", converter!!.getCanonicalName()
            )
                .that(testers)
                .containsKey(converter)
            for (member in converterClasses.get(converter)) {
                val option = member!!.getAnnotation<Option?>(Option::class.java)
                if (option != null && !option.allowMultiple && (option.defaultValue != "null")) {
                    Truth.assertWithMessage(
                        "Default value \"%s\" on %s is not tested in the corresponding ConverterTester"
                                + " for %s",
                        option.defaultValue, member, converter.getCanonicalName()
                    )
                        .that(testers.get(converter)!!.hasTestForInput(option.defaultValue))
                        .isTrue()
                }
            }
        }
        return this
    }

    companion object {
        private fun getAllFields(optionsClass: Class<out OptionsBase?>): ImmutableList<Field> {
            val builder = ImmutableList.builder<Field?>()
            var current = optionsClass
            while (OptionsBase::class.java != current) {
                builder.add(*current.getDeclaredFields())
                // the input extends OptionsBase and we haven't seen OptionsBase yet, so this must also extend
                // (or be) OptionsBase
                val superclass =
                    current.getSuperclass() as Class<out OptionsBase?>
                current = superclass
            }
            return builder.build()
        }

        private fun getAllMethods(optionsClass: Class<out OptionsBase?>): ImmutableList<Method> {
            val builder = ImmutableList.builder<Method?>()
            var current = optionsClass
            while (OptionsBase::class.java != current) {
                builder.add(*current.getDeclaredMethods())
                val superclass =
                    current.getSuperclass().asSubclass<OptionsBase?>(OptionsBase::class.java)
                current = superclass
            }
            return builder.build()
        }
    }
}
