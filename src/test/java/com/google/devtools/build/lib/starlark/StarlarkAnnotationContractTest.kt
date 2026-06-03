// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.util.Classpath

/**
 * Tests that bazel usages of [StarlarkMethod] and [StarlarkBuiltin] abide by the
 * contracts specified in their documentation.
 * 
 * 
 * Tests in this class use the java reflection API.
 * 
 * 
 * This verification *would* be done via annotation processor, but annotation processors in java
 * don't have access to the full set of information that the java reflection API has.
 */
@RunWith(JUnit4::class)
class StarlarkAnnotationContractTest {
    /**
     * Verifies that every class in bazel that implements or extends a Starlark type has a clearly
     * resolvable type.
     * 
     * 
     * If this test fails, it indicates the following error scenario:
     * 
     * 
     * Suppose class A is a subclass of both B and C, where B and C are annotated with [ ] annotations (and are thus considered "Starlark types"). If B is not a subclass
     * of C (nor visa versa), then it's impossible to resolve whether A is of type B or if A is of
     * type C. It's both! The way to resolve this is usually to have A be its own type (annotated with
     * [StarlarkBuiltin]), and thus have the explicit type of A be semantically "B and C".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolvableStarlarkBuiltins() {
        for (candidateClass in Classpath.findClasses(MODULES_PACKAGE_PREFIX)) {
            StarlarkAnnotations.getStarlarkBuiltin(candidateClass)
        }
    }

    companion object {
        // Common prefix of packages in bazel that may have classes that implement or extend a
        // Starlark type.
        private const val MODULES_PACKAGE_PREFIX = "com/google/devtools/build"
    }
}
