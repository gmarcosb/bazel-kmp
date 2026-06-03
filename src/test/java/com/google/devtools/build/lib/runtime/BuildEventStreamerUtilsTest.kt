// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.TestStatus

/** Unit test for BuildEventStreamerUtils.  */
@RunWith(TestParameterInjector::class)
class BuildEventStreamerUtilsTest {
    @org.junit.Test
    fun allValuesConvertToRealStatus(@TestParameter status: BlazeTestStatus) {
        val bepStatus: TestStatus? = BuildEventStreamerUtils.bepStatus(status)
        if (status.equals(BlazeTestStatus.NO_STATUS)) {
            assertThat(bepStatus).isEqualTo(TestStatus.NO_STATUS)
        } else {
            assertThat(bepStatus).isNotEqualTo(TestStatus.NO_STATUS)
        }
    }
}
