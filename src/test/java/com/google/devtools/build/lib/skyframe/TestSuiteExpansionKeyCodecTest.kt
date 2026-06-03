// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/** Serialization test for test_suite.  */
@RunWith(JUnit4::class)
class TestSuiteExpansionKeyCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            TestsForTargetPatternKey(
                com.google.common.collect.ImmutableSortedSet.of(
                    Label.parseCanonical("//foo/bar:baz"), Label.parseCanonical("//a/b:c")
                )
            ),
            TestsForTargetPatternKey(com.google.common.collect.ImmutableSortedSet.of<Label?>())
        )
            .runTests()
    }
}
