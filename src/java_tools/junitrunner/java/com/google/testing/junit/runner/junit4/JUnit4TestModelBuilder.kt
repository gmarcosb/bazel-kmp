// Copyright 2010 The Bazel Authors. All Rights Reserved.
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
package com.google.testing.junit.runner.junit4

import com.google.testing.junit.runner.model.TestSuiteModel
import org.junit.runner.Request
import java.util.function.Supplier

/**
 * Builds a [TestSuiteModel] for JUnit4 tests.
 */
internal class JUnit4TestModelBuilder(
    private val request: Request,
    private val suiteName: String?,
    private val builder: TestSuiteModel.Builder
) : Supplier<TestSuiteModel?> {
    /**
     * Creates a model for a JUnit4 suite. This can be expensive; callers should
     * consider memoizing the result.
     * 
     * @return model.
     */
    override fun get(): TestSuiteModel? {
        val root = request.getRunner().getDescription()
        // A test class annotated with @Ignore effectively has no test methods,
        // which is what isSuite() tests for.
        if (!root.isSuite()) {
            return builder.build(suiteName)
        } else {
            return builder.build(suiteName, root)
        }
    }
}
