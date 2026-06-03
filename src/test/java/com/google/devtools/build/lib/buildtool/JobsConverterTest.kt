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
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.actions.LocalHostCapacity

/** Tests [com.google.devtools.build.lib.buildtool.BuildRequestOptions.JobsConverter].  */
@RunWith(JUnit4::class)
class JobsConverterTest {
    var jobsConverter: JobsConverter? = null

    @Before
    fun setUp() {
        jobsConverter = JobsConverter()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoJobsUsesHardwareSettings() {
        LocalHostCapacity.setLocalHostCapacity(ResourceSet.createWithRamCpu(1, 123))
        assertThat(jobsConverter.convert("auto")).isEqualTo(123)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoJobsAdjustsIfHardwareDetectionIsBogus() {
        LocalHostCapacity.setLocalHostCapacity(
            ResourceSet.createWithRamCpu(1, BuildRequestOptions.MAX_JOBS + 1)
        )
        assertThat(jobsConverter.convert("auto")).isEqualTo(BuildRequestOptions.MAX_JOBS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExplicitJobsLimited() {
        assertThat(jobsConverter.convert((BuildRequestOptions.MAX_JOBS + 1).toString()))
            .isEqualTo(BuildRequestOptions.MAX_JOBS)
    }

    @org.junit.Test
    fun testUnboundedJobsDeprecated() {
        val thrown: OptionsParsingException? =
            org.junit.Assert.assertThrows<T?>(
                OptionsParsingException::class.java,
                org.junit.function.ThrowingRunnable { jobsConverter.convert("-1") })
        assertThat(thrown).hasMessageThat().contains("must be at least 1")
    }
}
