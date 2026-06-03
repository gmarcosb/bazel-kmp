// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.exec.util.SpawnBuilder.build
import com.google.devtools.build.lib.testutil.TestSpec
import com.google.devtools.build.lib.testutil.TestSuiteBuilder
import com.google.devtools.common.options.testing.ConverterTesterMap.Builder.build
import net.starlark.java.syntax.FileOptions.Builder.build

/**
 * A base class for constructing test suites by searching the classpath for
 * tests, possibly restricted to a predicate.
 */
class BazelTestSuiteBuilder {
    protected val builder: TestSuiteBuilder
        /**
         * @return a TestSuiteBuilder configured for Bazel.
         */
        get() = TestSuiteBuilder()
            .addPackageRecursive("com.google.devtools.build.lib")

    companion object {
        init {
            // Avoid verbose INFO logging in tests.
            java.util.logging.Logger.getLogger(BazelTestSuiteBuilder::class.java.getName()).getParent()
                .setLevel(java.util.logging.Level.WARNING)
        }

        /** A predicate that succeeds only if the test supports the current operating system.  */
        val TEST_SUPPORTS_CURRENT_OS: com.google.common.base.Predicate<java.lang.Class<*>?> =
            object : com.google.common.base.Predicate<java.lang.Class<*>?> {
                override fun apply(testClass: java.lang.Class<*>): Boolean {
                    val supportedOs: com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.util.OS?> =
                        com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.build.lib.util.OS?>(
                            getSupportedOs(testClass)
                        )
                    return supportedOs.isEmpty() || supportedOs.contains(com.google.devtools.build.lib.util.OS.getCurrent())
                }
            }

        /** Given a class, determine the list of operating systems its tests can run under.  */
        private fun getSupportedOs(clazz: java.lang.Class<*>): Array<com.google.devtools.build.lib.util.OS?>? {
            return getAnnotationElementOrDefault<Array<com.google.devtools.build.lib.util.OS?>?>(clazz, "supportedOs")
        }

        /**
         * Returns the value of the given element in the [TestSpec] annotation of the given class,
         * or the default value of that element if the class doesn't have a [TestSpec] annotation.
         */
        private fun <T> getAnnotationElementOrDefault(clazz: java.lang.Class<*>, elementName: String): T? {
            val spec: TestSpec? = clazz.getAnnotation<TestSpec?>(TestSpec::class.java)
            try {
                val method: java.lang.reflect.Method = TestSpec::class.java.getMethod(elementName)
                return if (spec != null) method.invoke(spec) as T? else method.getDefaultValue() as T?
            } catch (e: java.lang.NoSuchMethodException) {
                throw java.lang.IllegalStateException("no such element " + elementName, e)
            } catch (e: java.lang.IllegalAccessException) {
                throw java.lang.IllegalStateException("can't invoke accessor for element " + elementName, e)
            } catch (e: java.lang.IllegalArgumentException) {
                throw java.lang.IllegalStateException("can't invoke accessor for element " + elementName, e)
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw java.lang.IllegalStateException("can't invoke accessor for element " + elementName, e)
            }
        }
    }
}
