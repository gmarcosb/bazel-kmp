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

import com.google.devtools.build.lib.util.Classpath

/**
 * A collector for test classes, for both JUnit 3 and 4. To be used in combination with [ ].
 */
class TestSuiteBuilder {
    private val testClasses: MutableSet<java.lang.Class<*>?> =
        com.google.common.collect.Sets.newTreeSet<java.lang.Class<*>?>(TestClassNameComparator())
    private var matchClassPredicate: com.google.common.base.Predicate<java.lang.Class<*>?> =
        com.google.common.base.Predicates.alwaysTrue<java.lang.Class<*>?>()

    /**
     * Adds the tests found (directly) in class `c` to the set of tests this builder will
     * search.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addTestClass(c: java.lang.Class<*>?): TestSuiteBuilder {
        testClasses.add(c)
        return this
    }

    /**
     * Adds all the test classes (top-level or nested) found in package `pkgName` or its
     * subpackages to the set of tests this builder will search.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addPackageRecursive(pkgName: String?): TestSuiteBuilder {
        for (c in getClassesRecursive(pkgName)) {
            addTestClass(c)
        }
        return this
    }

    private fun getClassesRecursive(pkgName: String?): MutableSet<java.lang.Class<*>?> {
        val result: MutableSet<java.lang.Class<*>?> = LinkedHashSet<java.lang.Class<*>?>()
        try {
            for (clazz in Classpath.findClasses(pkgName)) {
                if (isTestClass(clazz)) {
                    result.add(clazz)
                }
            }
        } catch (e: ClassPathException) {
            throw java.lang.AssertionError("Cannot retrieve classes: " + e.getMessage())
        }
        return result
    }

    /** Specifies a predicate returns false for classes we want to exclude.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun matchClasses(predicate: com.google.common.base.Predicate<java.lang.Class<*>?>): TestSuiteBuilder {
        matchClassPredicate = predicate
        return this
    }

    /**
     * Creates and returns a TestSuite containing the tests from the given
     * classes and/or packages which matched the given tags.
     */
    fun create(): MutableSet<java.lang.Class<*>?> {
        val result: MutableSet<java.lang.Class<*>?> = LinkedHashSet<java.lang.Class<*>?>()
        for (testClass in com.google.common.collect.Iterables.filter<java.lang.Class<*>?>(
            testClasses,
            matchClassPredicate
        )) {
            result.add(testClass)
        }
        return result
    }

    private class TestClassNameComparator : java.util.Comparator<java.lang.Class<*>?> {
        override fun compare(o1: java.lang.Class<*>, o2: java.lang.Class<*>): Int {
            return o1.getName().compareTo(o2.getName())
        }
    }

    companion object {
        /**
         * Determines if a given class is a test class.
         * 
         * @param container class to test
         * @return `true` if the test is a test class.
         */
        private fun isTestClass(container: java.lang.Class<*>): Boolean {
            return (isJunit4Test(container) || isJunit3Test(container))
                    && !isSuite(container) && !java.lang.reflect.Modifier.isAbstract(container.getModifiers())
        }

        private fun isJunit4Test(container: java.lang.Class<*>): Boolean {
            return container.isAnnotationPresent(RunWith::class.java)
        }

        private fun isJunit3Test(container: java.lang.Class<*>?): Boolean {
            return TestCase::class.java.isAssignableFrom(container)
        }

        /**
         * Classes that have a `RunWith` annotation for [ClasspathSuite] or [ ] are automatically excluded to avoid picking up the suite class itself.
         */
        private fun isSuite(container: java.lang.Class<*>): Boolean {
            val runWith: RunWith? = container.getAnnotation<RunWith?>(RunWith::class.java)
            return (runWith != null)
                    && ((runWith.value == ClasspathSuite::class.java) || (runWith.value == CustomSuite::class.java))
        }
    }
}
