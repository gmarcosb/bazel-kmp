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
package com.google.devtools.build.lib.packages.metrics

import com.google.devtools.build.lib.runtime.CommandEnvironment

/** Tests for [PackageMetricsModule].  */
@RunWith(JUnit4::class)
class PackageMetricsModuleTest {
    private val listener: PackageMetricsPackageLoadingListener? = PackageMetricsPackageLoadingListener.instance

    private var underTest: PackageMetricsModule? = null

    @Before
    fun setUp() {
        underTest = PackageMetricsModule()
        listener.packageMetricsRecorder = null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeCommandConfiguresNumberOfPackagesToTrack() {
        underTest.beforeCommand(commandEnv("--log_top_n_packages=100"))
        assertThat(listener.packageMetricsRecorder)
            .isInstanceOf(ExtremaPackageMetricsRecorder::class.java)
        val ext: ExtremaPackageMetricsRecorder =
            listener.packageMetricsRecorder as ExtremaPackageMetricsRecorder
        assertThat(ext.numPackagesToTrack).isEqualTo(100)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeCommandConfiguresNumberOfPackagesToTrackTreatsNegativeAsZero() {
        underTest.beforeCommand(commandEnv("--log_top_n_packages=-100"))
        assertThat(listener.packageMetricsRecorder)
            .isInstanceOf(ExtremaPackageMetricsRecorder::class.java)
        val ext: ExtremaPackageMetricsRecorder =
            listener.packageMetricsRecorder as ExtremaPackageMetricsRecorder
        assertThat(ext.numPackagesToTrack).isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBeforeCommandConfiguresNumberOfPackagesToTrackButRequestsComplete() {
        underTest.beforeCommand(
            commandEnv("--log_top_n_packages=100", "--record_metrics_for_all_packages=1")
        )
        assertThat(listener.packageMetricsRecorder)
            .isInstanceOf(CompletePackageMetricsRecorder::class.java)
    }

    @org.junit.Test
    fun testAfterCommandGetsAndResetsMetrics() {
        // Mocking here is lazy, but it helps verify we actually did something with all of the results.
        val mockRecorder: PackageMetricsRecorder? =
            Mockito.mock<PackageMetricsRecorder?>(PackageMetricsRecorder::class.java)
        listener.packageMetricsRecorder = mockRecorder

        underTest.afterCommand()
        Mockito.verify<PackageMetricsRecorder?>(mockRecorder).loadingFinished()
    }

    companion object {
        @Throws(java.lang.Exception::class)
        private fun commandEnv(vararg options: String?): CommandEnvironment {
            val parser: OptionsParser =
                OptionsParser.builder()
                    .optionsClasses(com.google.devtools.build.lib.packages.metrics.PackageMetricsModule.Options::class.java)
                    .build()
            parser.parse(*options)

            val mockEnv: CommandEnvironment = Mockito.mock<CommandEnvironment>(CommandEnvironment::class.java)
            Mockito.`when`<T?>(mockEnv.getOptions()).thenReturn(parser)
            return mockEnv
        }
    }
}
