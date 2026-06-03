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

import com.google.devtools.build.lib.analysis.util.ConfigurationTestCase.create
import com.google.devtools.build.lib.packages.util.MockToolsConfig.create
import com.google.devtools.build.lib.testutil.TestSuiteBuilder
import org.junit.runners.Suite
import org.junit.runners.model.RunnerBuilder

/**
 * A suite implementation that finds all JUnit 3 and 4 classes on the current classpath in or below
 * the package of the annotated class, except classes that are annotated with `ClasspathSuite`
 * or [CustomSuite].
 * 
 * 
 * If you need to specify a custom test class filter or a different package prefix, then use
 * [CustomSuite] instead.
 */
class ClasspathSuite
/**
 * Only called reflectively. Do not use programmatically.
 */
    (klass: java.lang.Class<*>, builder: RunnerBuilder) : Suite(builder, klass, getClasses(klass)) {
    companion object {
        private fun getClasses(klass: java.lang.Class<*>): Array<java.lang.Class<*>?> {
            val result: MutableSet<java.lang.Class<*>?> =
                TestSuiteBuilder().addPackageRecursive(klass.getPackage().getName())
                    .create()
            return result.toTypedArray<java.lang.Class<*>?>()
        }
    }
}
