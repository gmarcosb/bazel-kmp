// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.concurrent

import com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification
import org.junit.Test
import java.util.*

/** Tests for [ErrorClassifier].  */
@RunWith(JUnit4::class)
class ErrorClassifierTest {
    @Test
    fun testErrorClassificationNaturalOrder() {
        val values: Array<ErrorClassification?> = ErrorClassification.values()
        Arrays.sort(values)
        Truth.assertThat<ErrorClassification?>(values)
            .asList()
            .containsExactly(
                ErrorClassification.NOT_CRITICAL,
                ErrorClassification.NOT_CRITICAL_HIGHER_PRIORITY,
                ErrorClassification.CRITICAL,
                ErrorClassification.CRITICAL_AND_LOG,
                ErrorClassification.AS_CRITICAL_AS_POSSIBLE
            )
            .inOrder()
    }
}

