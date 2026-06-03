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

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [PackageMetricsPackageLoadingListener].  */
@RunWith(JUnit4::class)
class PackageMetricsPackageLoadingListenerTest {
    private val underTest: PackageMetricsPackageLoadingListener? = PackageMetricsPackageLoadingListener.instance

    @org.junit.Test
    fun testRecordsTopSlowestPackagesPerBuild_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordSlowPackages()

        assertThat(underTest.packageMetricsRecorder.getLoadTimes())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg3"),
                    Durations.fromMillis(44),
                    PackageIdentifier.createInMainRepo("my/pkg2"),
                    Durations.fromMillis(43)
                )
            )
            .inOrder()

        recorder.loadingFinished()
        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsTopSlowestPackagesPerBuild_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordSlowPackages()

        assertThat(underTest.packageMetricsRecorder.getLoadTimes())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg1"),
                Durations.fromMillis(42),
                PackageIdentifier.createInMainRepo("my/pkg2"),
                Durations.fromMillis(43),
                PackageIdentifier.createInMainRepo("my/pkg3"),
                Durations.fromMillis(44)
            )
        recorder.clear()
        assertAllMapsEmpty(recorder)
    }

    private fun recordSlowPackages() {
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */42000000,  /* globFilesystemOperationCost= */0)
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */43000000,  /* globFilesystemOperationCost= */0)
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */44000000,  /* globFilesystemOperationCost= */0)
        )
    }

    @org.junit.Test
    fun testRecordsTopLargestPackagesPerBuild_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordLargePackages()

        assertThat(underTest.packageMetricsRecorder.getNumTargets())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg3"),
                    3L,
                    PackageIdentifier.createInMainRepo("my/pkg2"),
                    2L
                )
            )
            .inOrder()
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsTopLargestPackagesPerBuild_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordLargePackages()

        assertThat(underTest.packageMetricsRecorder.getNumTargets())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg3"),
                3L,
                PackageIdentifier.createInMainRepo("my/pkg2"),
                2L,
                PackageIdentifier.createInMainRepo("my/pkg1"),
                1L
            )
    }

    private fun recordLargePackages() {
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg1",
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg2",
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java),
                    "target2",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg3",
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java),
                    "target2",
                    Mockito.mock<Target?>(Target::class.java),
                    "target3",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )
    }

    @org.junit.Test
    fun testRecordsTransitiveLoadsPerBuild_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordTransitiveLoads()

        assertThat(underTest.packageMetricsRecorder.getNumTransitiveLoads())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg3"),
                    3L,
                    PackageIdentifier.createInMainRepo("my/pkg2"),
                    2L
                )
            )
            .inOrder()
        recorder.loadingFinished()
        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsTransitiveLoadsPerBuild_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordTransitiveLoads()

        assertThat(underTest.packageMetricsRecorder.getNumTransitiveLoads())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg3"),
                3L,
                PackageIdentifier.createInMainRepo("my/pkg2"),
                2L,
                PackageIdentifier.createInMainRepo("my/pkg1"),
                1L
            )
    }

    private fun recordTransitiveLoads() {
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                1
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                2
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                3
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )
    }

    @org.junit.Test
    fun testRecordsMostComputationStepsPerBuild_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordComputationSteps()

        assertThat(underTest.packageMetricsRecorder.getComputationSteps())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg1"),
                    1000L,
                    PackageIdentifier.createInMainRepo("my/pkg2"),
                    100L
                )
            )
            .inOrder()
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsMostComputationStepsPerBuild_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordComputationSteps()

        assertThat(underTest.packageMetricsRecorder.getComputationSteps())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg1"),
                1000L,
                PackageIdentifier.createInMainRepo("my/pkg2"),
                100L,
                PackageIdentifier.createInMainRepo("my/pkg3"),
                10L
            )
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    private fun recordComputationSteps() {
        val mockPackage1: Package =
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        Mockito.`when`<Any?>(mockPackage1.computationSteps).thenReturn(1000L)
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage1,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        val mockPackage2: Package =
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        Mockito.`when`<Any?>(mockPackage2.computationSteps).thenReturn(100L)
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage2,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        val mockPackage3: Package =
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        Mockito.`when`<Any?>(mockPackage3.computationSteps).thenReturn(10L)
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage3,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )
    }

    @org.junit.Test
    fun testRecordsMostPackageOverheadPerBuild_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordPackageOverhead()

        assertThat(underTest.packageMetricsRecorder.getPackageOverhead())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg1"),
                100L,
                PackageIdentifier.createInMainRepo("my/pkg3"),
                300L
            )
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsTopPackageOverheadPackagesPerBuild_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordPackageOverhead()

        assertThat(underTest.packageMetricsRecorder.getPackageOverhead())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg3"),
                    300L,
                    PackageIdentifier.createInMainRepo("my/pkg1"),
                    100L
                )
            )
            .inOrder()
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    private fun recordPackageOverhead() {
        val mockPackage1: Package =
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        Mockito.`when`<T?>(mockPackage1.getPackageOverhead()).thenReturn(OptionalLong.of(100))
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage1,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        // Record nothing for pkg2, will be missing from metrics.
        val mockPackage2: Package =
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage2,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )

        val mockPackage3: Package =
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            )
        Mockito.`when`<Any?>(mockPackage3.computationSteps).thenReturn(10L)
        Mockito.`when`<T?>(mockPackage3.getPackageOverhead()).thenReturn(OptionalLong.of(300))

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage3,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            PLACEHOLDER_METRICS
        )
    }

    @org.junit.Test
    fun metricMap_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordEverything()

        val pkg1: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg1")
                .setLoadDuration(Durations.fromMillis(42))
                .setGlobFilesystemOperationCost(100)
                .setComputationSteps(1000)
                .setNumTargets(1)
                .setNumTransitiveLoads(1)
                .setPackageOverhead(100000)
                .build()

        val pkg2: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg2")
                .setLoadDuration(Durations.fromMillis(43))
                .setGlobFilesystemOperationCost(200)
                .setComputationSteps(100)
                .setNumTargets(2)
                .setNumTransitiveLoads(2)
                .setPackageOverhead(200000)
                .build()

        val pkg3: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg3")
                .setLoadDuration(Durations.fromMillis(44))
                .setGlobFilesystemOperationCost(300)
                .setComputationSteps(10)
                .setNumTargets(3)
                .setNumTransitiveLoads(3)
                .setPackageOverhead(300000)
                .build()

        assertThat(underTest.packageMetricsRecorder.getPackageLoadMetrics())
            .containsExactly(pkg1, pkg2, pkg3)
        recorder.loadingFinished()
        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun metricMap_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordEverything()

        val pkg1: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg1")
                .setLoadDuration(Durations.fromMillis(42))
                .setGlobFilesystemOperationCost(100)
                .setComputationSteps(1000)
                .setNumTargets(1)
                .setNumTransitiveLoads(1)
                .setPackageOverhead(100000)
                .build()

        val pkg2: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg2")
                .setLoadDuration(Durations.fromMillis(43))
                .setGlobFilesystemOperationCost(200)
                .setComputationSteps(100)
                .setNumTargets(2)
                .setNumTransitiveLoads(2)
                .setPackageOverhead(200000)
                .build()

        val pkg3: PackageLoadMetrics? =
            PackageLoadMetrics.newBuilder()
                .setName("my/pkg3")
                .setLoadDuration(Durations.fromMillis(44))
                .setGlobFilesystemOperationCost(300)
                .setComputationSteps(10)
                .setNumTargets(3)
                .setNumTransitiveLoads(3)
                .setPackageOverhead(300000)
                .build()

        assertThat(underTest.packageMetricsRecorder.getPackageLoadMetrics())
            .containsExactly(pkg1, pkg2, pkg3)
        recorder.loadingFinished()
        assertAllMapsEmpty(recorder)
    }

    private fun recordEverything() {
        val mockPackage1: Package =
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                1
            )
        Mockito.`when`<Any?>(mockPackage1.computationSteps).thenReturn(1000L)
        Mockito.`when`<T?>(mockPackage1.getPackageOverhead()).thenReturn(OptionalLong.of(100000))

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage1,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */42000000,  /* globFilesystemOperationCost= */100)
        )

        val mockPackage2: Package =
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java),
                    "target2",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                2
            )
        Mockito.`when`<Any?>(mockPackage2.computationSteps).thenReturn(100L)
        Mockito.`when`<T?>(mockPackage2.getPackageOverhead()).thenReturn(OptionalLong.of(200000))
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage2,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */43000000,  /* globFilesystemOperationCost= */200)
        )

        val mockPackage3: Package =
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(
                    "target1",
                    Mockito.mock<Target?>(Target::class.java),
                    "target2",
                    Mockito.mock<Target?>(Target::class.java),
                    "target3",
                    Mockito.mock<Target?>(Target::class.java)
                ),  /* transitivelyLoadedStarlarkFiles= */
                3
            )
        Mockito.`when`<Any?>(mockPackage3.computationSteps).thenReturn(10L)
        Mockito.`when`<T?>(mockPackage3.getPackageOverhead()).thenReturn(OptionalLong.of(300000))
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage3,
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */44000000,  /* globFilesystemOperationCost= */300)
        )
    }

    private fun recordPackagesWithGlobCost() {
        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */0,  /* globFilesystemOperationCost= */111)
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg2",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */0,  /* globFilesystemOperationCost= */222)
        )

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg3",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */0,  /* globFilesystemOperationCost= */333)
        )
    }

    @org.junit.Test
    fun testRecordsTopGlobFilesystemOperationCost_extrema() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(2)
        underTest.packageMetricsRecorder = recorder

        recordPackagesWithGlobCost()

        assertThat(underTest.packageMetricsRecorder.getGlobFilesystemOperationCost())
            .containsExactlyEntriesIn(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    PackageIdentifier.createInMainRepo("my/pkg3"),
                    333L,
                    PackageIdentifier.createInMainRepo("my/pkg2"),
                    222L
                )
            )
            .inOrder()
        recorder.loadingFinished()

        assertAllMapsEmpty(recorder)
    }

    @org.junit.Test
    fun testRecordsTopGlobFilesystemOperationCost_complete() {
        val recorder: PackageMetricsRecorder = CompletePackageMetricsRecorder()
        underTest.packageMetricsRecorder = recorder

        recordPackagesWithGlobCost()

        assertThat(underTest.packageMetricsRecorder.getGlobFilesystemOperationCost())
            .containsExactly(
                PackageIdentifier.createInMainRepo("my/pkg3"),
                333L,
                PackageIdentifier.createInMainRepo("my/pkg2"),
                222L,
                PackageIdentifier.createInMainRepo("my/pkg1"),
                111L
            )
    }

    @org.junit.Test
    fun testDoesntRecordAnythingWhenNumPackagesToTrackIsZero() {
        val recorder: PackageMetricsRecorder = ExtremaPackageMetricsRecorder(0)
        underTest.packageMetricsRecorder = recorder

        underTest.onLoadingCompleteAndSuccessful(
            mockPackage(
                "my/pkg1",  /* targets= */
                com.google.common.collect.ImmutableMap.of<String?, Target?>(),  /* transitivelyLoadedStarlarkFiles= */
                0
            ),
            StarlarkSemantics.DEFAULT,
            LazyMacroExpansionPackages.NONE,
            Metrics( /* loadTimeNanos= */42000000,  /* globFilesystemOperationCost= */0)
        )

        assertAllMapsEmpty(underTest.packageMetricsRecorder)
    }

    companion object {
        private val PLACEHOLDER_METRICS: Metrics =
            Metrics( /* loadTimeNanos= */123,  /* globFilesystemOperationCost= */456)

        private fun assertAllMapsEmpty(recorder: PackageMetricsRecorder) {
            Truth.assertThat(recorder.getLoadTimes()).isEmpty()
            Truth.assertThat(recorder.getGlobFilesystemOperationCost()).isEmpty()
            Truth.assertThat(recorder.getComputationSteps()).isEmpty()
            Truth.assertThat(recorder.getNumTargets()).isEmpty()
            Truth.assertThat(recorder.getNumTransitiveLoads()).isEmpty()
        }

        private fun mockPackage(
            pkgIdString: String?, targets: MutableMap<String?, Target?>?, transitivelyLoadedStarlarkFiles: Int
        ): Package {
            val fakeLoads: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builder<Label?>()
            for (i in 0..<transitivelyLoadedStarlarkFiles) {
                fakeLoads.add(Label.parseCanonicalUnchecked(String.format("//:%d.bzl", i)))
            }
            val fakeDeclarations: Package.Declarations? =
                Builder().setTransitiveLoads(fakeLoads.build()).build()
            val mockPackage: Package = Mockito.mock<Package>(Package::class.java)
            Mockito.`when`<T?>(mockPackage.getPackageIdentifier())
                .thenReturn(PackageIdentifier.createInMainRepo(pkgIdString))
            Mockito.`when`<T?>(mockPackage.getTargets())
                .thenReturn(com.google.common.collect.ImmutableSortedMap.< K, V > copyOf<K?, V?>(targets))
            Mockito.`when`<T?>(mockPackage.getDeclarations()).thenReturn(fakeDeclarations)
            return mockPackage
        }
    }
}
