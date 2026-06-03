// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.serialization

@RunWith(JUnit4::class)
class CoreOptionsSerializationTest {
    /**
     * Tests serialization (with [BuildOptions] dependency).
     * 
     * 
     * `checkVisibility` is not serialized, but restored from [BuildOptions] during
     * deserialization.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun coreOptionsRoundTrip() {
        val buildOptionsToSerialize: BuildOptions =
            BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java))
        val optionsToSerialize: CoreOptions = buildOptionsToSerialize.get(CoreOptions::class.java)
        optionsToSerialize.setCheckVisibility(false)

        val buildOptions: BuildOptions =
            BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java))
        buildOptions.get(CoreOptions::class.java).setCheckVisibility(true) // This will be source of truth.

        val tester: SerializationTester = SerializationTester(optionsToSerialize)
        for (codec in SerializationRegistrySetupHelpers.analysisCachingCodecs()) {
            tester.addCodec(codec)
        }
        tester
            .setVerificationFunction(
                { original, deserialized ->
                    // Deserialized value comes from BuildOptions dependency, not original value.
                    assertThat((deserialized as CoreOptions).getCheckVisibility()).isTrue()
                })
            .addDependency(BuildOptions::class.java, buildOptions)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun emptyOptionsRoundTrip_toSameInstance_withCustomCoreOptionsCodec() {
        val original: BuildOptions? = CommonOptions.EMPTY_OPTIONS

        // Simulates the reader build passing --check_visibility=false.
        val readerOptions: BuildOptions =
            BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>(CoreOptions::class.java))
        readerOptions.get(CoreOptions::class.java).setCheckVisibility(false)

        val registryBuilder: ObjectCodecRegistry.Builder = AutoRegistry.get().getBuilder()
        for (codec in SerializationRegistrySetupHelpers.analysisCachingCodecs()) {
            registryBuilder.add(codec)
        }

        registryBuilder.addReferenceConstants(
            SerializationRegistrySetupHelpers.makeReferenceConstants(
                FakeDirectories.BLAZE_DIRECTORIES,
                Builder()
                    .setToolsRepository(RepositoryName.createUnvalidated("bazel_tools"))
                    .build(),
                "root"
            )
        )
        val registry: ObjectCodecRegistry? = registryBuilder.build()

        // Inject the reader options.
        val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.of<Any?, BuildOptions?>(
                BuildOptions::class.java,
                readerOptions
            )

        val codecs: ObjectCodecs = ObjectCodecs(registry, dependencies)

        val tester: SerializationTester = SerializationTester(original)
        tester.setObjectCodecs(codecs)

        tester
            .makeMemoizingAndAllowFutureBlocking(true)
            .setVerificationFunction(
                { orig, deserialized ->
                    // Check that EMPTY_OPTIONS remain untainted by the custom CoreOptions
                    // check_visibility trimming.
                    assertThat(deserialized).isSameInstanceAs(orig)
                })
            .runTests()
    }
}
