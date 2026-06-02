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
package com.google.devtools.build.lib.analysis.platform


import com.google.devtools.build.lib.cmdline.Label
import org.junit.Test

/** Tests of [ConstraintValueInfo].  */
@RunWith(JUnit4::class)
class ConstraintValueInfoTest : BuildViewTestCase() {
    @Test
    fun constraintValue_equalsTester() {
        val setting1: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:basic"))
        val setting2: ConstraintSettingInfo? =
            ConstraintSettingInfo.create(Label.parseCanonicalUnchecked("//constraint:other"))
        EqualsTester()
            .addEqualityGroup( // Base case.
                ConstraintValueInfo.create(
                    setting1, Label.parseCanonicalUnchecked("//constraint:value")
                ),
                ConstraintValueInfo.create(
                    setting1, Label.parseCanonicalUnchecked("//constraint:value")
                )
            )
            .addEqualityGroup( // Different label.
                ConstraintValueInfo.create(
                    setting1, Label.parseCanonicalUnchecked("//constraint:otherValue")
                )
            )
            .addEqualityGroup( // Different setting.
                ConstraintValueInfo.create(
                    setting2, Label.parseCanonicalUnchecked("//constraint:otherValue")
                )
            )
            .testEquals()
    }
}
