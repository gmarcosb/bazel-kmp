// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.skyframe.serialization.analysis.LongVersionGetterTestInjection.injectVersionGetterForTesting

@RunWith(JUnit4::class)
class BazelSkycacheIntegrationTest : SkycacheIntegrationTestBase() {
    private val versionGetter: LongVersionGetter? = Mockito.mock<LongVersionGetter?>(LongVersionGetter::class.java)
    private val failingStore = FailingFingerprintValueStore()

    @Before
    fun injectVersionGetter() {
        injectVersionGetterForTesting(versionGetter)
    }

    private class FailingFingerprintValueStore : FingerprintValueStore {
        private val delegate: FingerprintValueStore = FingerprintValueStore.inMemoryStore()
        private val shouldFail: AtomicBoolean = AtomicBoolean()
        private val failCounter: AtomicInteger = AtomicInteger()
        private val lastFailedKey: AtomicReference<KeyBytesProvider?> = AtomicReference<KeyBytesProvider?>()

        fun failNextPut() {
            shouldFail.set(true)
        }

        fun getFailCounter(): Int {
            return failCounter.get()
        }

        val failedKey: KeyBytesProvider?
            get() = lastFailedKey.get()

        public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
            if (shouldFail.getAndSet(false)) {
                failCounter.getAndIncrement()
                lastFailedKey.set(fingerprint)
                return WriteStatuses.immediateFailedWriteStatus(
                    IOException("Simulated write failure for " + fingerprint)
                )
            }
            return delegate.put(fingerprint, serializedBytes)
        }

        @Throws(IOException::class)
        public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
            return delegate.get(fingerprint)
        }
    }

    private inner class ModuleWithOverrides : SerializationModule() {
        protected val analysisCachingServicesSupplier: RemoteAnalysisCachingServicesSupplier
            get() = TestServicesSupplier(failingStore)
    }

    private class TestServicesSupplier(failingStore: FailingFingerprintValueStore?) :
        RemoteAnalysisCachingServicesSupplier {
        private val wrappedService: com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?>

        init {
            this.wrappedService =
                com.google.common.util.concurrent.Futures.immediateFuture<FingerprintValueService?>(
                    FingerprintValueService(
                        Executors.newSingleThreadExecutor(),
                        failingStore,
                        FingerprintValueCache(FingerprintValueCache.SyncMode.NOT_LINKED),
                        FingerprintValueService.NONPROD_FINGERPRINTER
                    )
                )
        }

        val fingerprintValueService: com.google.common.util.concurrent.ListenableFuture<FingerprintValueService?>
            get() = wrappedService

        public override fun resetCommandState() {}
    }

    @get:Throws(java.lang.Exception::class)
    val runtimeBuilder: BlazeRuntime.Builder
        get() = super.runtimeBuilder.addBlazeModule(ModuleWithOverrides())

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildCommand_uploadsFrontierBytesWithUploadMode() {
        setupScenarioWithAspects()
        assertUploadSuccess("//bar:one")

        val listener: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            commandEnvironment.getRemoteAnalysisCachingEventListener()
        assertThat(listener.getSerializedKeysCount()).isAtLeast(9) // for Bazel
        assertThat(listener.getSkyfunctionCounts().count(SkyFunctions.CONFIGURED_TARGET))
            .isAtLeast(9) // for Bazel
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buildCommand_withWriteFailure_reportsErrorAndCompletes() {
        setupScenarioWithAspects()

        failingStore.failNextPut()

        addOptions(SkycacheIntegrationTestBase.Companion.UPLOAD_MODE_OPTION)
        val thrown: T? = org.junit.Assert.assertThrows<T?>(
            AbruptExitException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//bar:one") })
        assertThat(thrown)
            .hasMessageThat()
            .contains("Simulated write failure for " + failingStore.failedKey)

        Truth.assertThat(failingStore.getFailCounter()).isEqualTo(1)
        assertContainsEvent("Simulated write failure for " + failingStore.failedKey)
    }
}
