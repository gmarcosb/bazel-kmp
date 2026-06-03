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

import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/**
 * A JUnit4 suite implementation that delegates the class finding to a `suite()` method on the
 * annotated class. To be used in combination with [TestSuiteBuilder].
 */
class CustomSuite
/**
 * Only called reflectively. Do not use programmatically.
 */
    (klass: java.lang.Class<*>, builder: RunnerBuilder) : Suite(builder, klass, getClasses(klass)) {
    companion object {
        private fun getClasses(klass: java.lang.Class<*>): Array<java.lang.Class<*>?> {
            val result: MutableSet<java.lang.Class<*>?> = evalSuite(klass)
            return result.toTypedArray<java.lang.Class<*>?>()
        }

        // unchecked cast to a generic type
        private fun evalSuite(klass: java.lang.Class<*>): MutableSet<java.lang.Class<*>?> {
            try {
                val m: java.lang.reflect.Method = klass.getMethod("suite")
                check(java.lang.reflect.Modifier.isStatic(m.getModifiers())) { "suite() must be static" }
                return m.invoke(null) as MutableSet<java.lang.Class<*>?>
            } catch (e: java.lang.Exception) {
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
