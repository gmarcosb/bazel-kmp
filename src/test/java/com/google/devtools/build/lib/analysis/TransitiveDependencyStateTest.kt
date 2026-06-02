// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.BuildOptions

@RunWith(JUnit4::class)
class TransitiveDependencyStateTest {
    @org.junit.Test
    fun singlyAddedPackages_areSorted() {
        val orderedPackages: com.google.common.collect.ImmutableList<Package.Metadata?> =
            com.google.common.collect.ImmutableList.of<Package.Metadata?>(
                createFakePackageMetadata(PackageIdentifier.createInMainRepo("package1")),
                createFakePackageMetadata(PackageIdentifier.createInMainRepo("package2")),
                createFakePackageMetadata(PackageIdentifier.createInMainRepo("package3"))
            )
        val workingCopy: java.util.ArrayList<Package.Metadata?> =
            java.util.ArrayList<Package.Metadata?>(orderedPackages)

        for (i in 0..2) {
            val state: TransitiveDependencyState = newTransitiveState()

            Collections.shuffle(workingCopy, rng)
            workingCopy.forEach(state::updateTransitivePackages)

            assertThat(state.transitivePackages().toList())
                .containsExactlyElementsIn(orderedPackages)
                .inOrder()
        }
    }

    @org.junit.Test
    fun configuredTargetPackages_areSorted() {
        val orderedKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?> =
            orderedConfiguredTargetKeys

        val orderedPackageMetadataList: com.google.common.collect.ImmutableList<Package.Metadata?> =
            createFakePackageMetadataList(orderedKeys.size)
        val orderedPackageMetadataNestedSets: com.google.common.collect.ImmutableList<NestedSet<Package.Metadata?>?> =
            asSingletonNestedSets(orderedPackageMetadataList)

        val shuffledIndices: java.util.ArrayList<Int?> = java.util.ArrayList<Int?>()
        for (i in orderedKeys.indices) {
            shuffledIndices.add(i)
        }

        for (i in 0..2) {
            val state: TransitiveDependencyState = newTransitiveState()

            // Adds the entries to `state` in random order.
            Collections.shuffle(shuffledIndices, rng)
            for (index in shuffledIndices) {
                state.updateTransitivePackages(
                    orderedKeys.get(index), orderedPackageMetadataNestedSets.get(index)
                )
            }

            // The result is always ordered.
            assertThat(state.transitivePackages().toList())
                .containsExactlyElementsIn(orderedPackageMetadataList)
                .inOrder()
        }
    }

    @org.junit.Test
    fun aspectPackages_areSorted() {
        val orderedKeys: com.google.common.collect.ImmutableList<AspectKey?> =
            orderedAspectKeys

        val orderedPackageMetadataList: com.google.common.collect.ImmutableList<Package.Metadata?> =
            createFakePackageMetadataList(orderedKeys.size)
        val orderedPackagMetadataNestedSets: com.google.common.collect.ImmutableList<NestedSet<Package.Metadata?>?> =
            asSingletonNestedSets(orderedPackageMetadataList)

        val shuffledIndices: java.util.ArrayList<Int?> = java.util.ArrayList<Int?>()
        for (i in orderedKeys.indices) {
            shuffledIndices.add(i)
        }

        for (i in 0..2) {
            val state: TransitiveDependencyState = newTransitiveState()

            // Adds the entries to `state` in random order.
            Collections.shuffle(shuffledIndices, rng)
            for (index in shuffledIndices) {
                state.updateTransitivePackages(
                    orderedKeys.get(index), orderedPackagMetadataNestedSets.get(index)
                )
            }

            // The result is always ordered.
            assertThat(state.transitivePackages().toList())
                .containsExactlyElementsIn(orderedPackageMetadataList)
                .inOrder()
        }
    }

    companion object {
        private val rng: Random = Random(0)
        private val fakeRoot: Root = Root.fromPath(InMemoryFileSystem(DigestHashFunction.SHA256).getPath("/fake"))

        private fun newTransitiveState(): TransitiveDependencyState {
            return TransitiveDependencyState( /* storeTransitivePackages= */
                true,  /* prerequisitePackages= */{ p -> null })
        }

        private fun createFakePackageMetadata(id: PackageIdentifier): Package.Metadata {
            return Package.Metadata.builder()
                .packageIdentifier(id)
                .buildFilename(
                    RootedPath.toRootedPath(
                        fakeRoot, fakeRoot.getRelative(id.getPackageFragment().getRelative("BUILD"))
                    )
                )
                .workspaceName("workspace")
                .repositoryMapping(RepositoryMapping.EMPTY)
                .succinctTargetNotFoundErrors(PackageSettings.DEFAULTS.succinctTargetNotFoundErrors())
                .build()
        }

        private fun createFakePackageMetadataList(count: Int): com.google.common.collect.ImmutableList<Package.Metadata?> {
            val orderedIds: java.util.ArrayList<PackageIdentifier?> = java.util.ArrayList<PackageIdentifier?>(count)
            for (i in 0..<count) {
                orderedIds.add(PackageIdentifier.createInMainRepo("package" + i))
            }
            // Scrambles the order so if the result is ordered it's not somehow due to package sorting.
            Collections.shuffle(orderedIds, rng)
            return orderedIds.stream()
                .map<Package.Metadata?> { id: PackageIdentifier? -> createFakePackageMetadata(id) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Package.Metadata?>())
        }

        private fun asSingletonNestedSets(
            packageMetadataList: MutableList<Package.Metadata?>
        ): com.google.common.collect.ImmutableList<NestedSet<Package.Metadata?>?> {
            return packageMetadataList.stream()
                .map<Any?> { pkgMetadata: Package.Metadata? ->
                    NestedSetBuilder.Metadata > stableOrder<Package.Metadata?>().add(
                        pkgMetadata
                    ).build()
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        private fun createTestOptions(): com.google.common.collect.ImmutableSortedSet<BuildOptions> {
            try {
                return com.google.common.collect.ImmutableSortedSet.copyOf<BuildOptions?>(
                    java.util.Comparator.comparing<Any?, Any?>(BuildOptions::checksum),
                    java.util.Arrays.asList<BuildOptions?>(
                        createTestOptions(com.google.common.collect.ImmutableList.of<String?>("--platforms=" + TestConstants.PLATFORM_LABEL)),
                        createTestOptions(com.google.common.collect.ImmutableList.of<String?>("--platforms=" + MockObjcSupport.DARWIN_X86_64))
                    )
                )
            } catch (e: OptionsParsingException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }

        @Throws(OptionsParsingException::class)
        private fun createTestOptions(args: MutableList<String?>?): BuildOptions {
            val fragments: com.google.common.collect.ImmutableList<java.lang.Class<out FragmentOptions?>?> =
                com.google.common.collect.ImmutableList.of<java.lang.Class<out FragmentOptions?>?>(PlatformOptions::class.java)
            val optionsParser: OptionsParser = OptionsParser.builder().optionsClasses(fragments).build()
            optionsParser.parse(args)
            return BuildOptions.of(fragments, optionsParser)
        }

        private val TEST_OPTIONS: com.google.common.collect.ImmutableSortedSet<BuildOptions> = createTestOptions()
        private val FIRST_OPTIONS: BuildOptions = TEST_OPTIONS.iterator().next()
        private val SECOND_OPTIONS: BuildOptions? =
            com.google.common.collect.Iterables.getLast<BuildOptions?>(TEST_OPTIONS)

        private val orderedConfiguredTargetKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>
            get() {
                val label1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    Label.parseCanonicalUnchecked("//label1")
                val label2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    Label.parseCanonicalUnchecked("//label2")
                val platformLabel: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    Label.parseCanonicalUnchecked("//platforms:a")
                return com.google.common.collect.ImmutableList.of<ConfiguredTargetKey?>(
                    ConfiguredTargetKey.builder().setLabel(label1).build(),
                    ConfiguredTargetKey.builder()
                        .setLabel(label1)
                        .setConfigurationKey(BuildConfigurationKey.create(FIRST_OPTIONS))
                        .build(),
                    ConfiguredTargetKey.builder()
                        .setLabel(label1)
                        .setConfigurationKey(BuildConfigurationKey.create(SECOND_OPTIONS))
                        .build(),
                    ConfiguredTargetKey.builder()
                        .setLabel(label1)
                        .setExecutionPlatformLabel(platformLabel)
                        .build(),
                    ConfiguredTargetKey.builder()
                        .setLabel(label1)
                        .setExecutionPlatformLabel(platformLabel)
                        .setConfigurationKey(BuildConfigurationKey.create(FIRST_OPTIONS))
                        .build(),
                    ConfiguredTargetKey.builder()
                        .setLabel(label1)
                        .setExecutionPlatformLabel(platformLabel)
                        .setConfigurationKey(BuildConfigurationKey.create(SECOND_OPTIONS))
                        .build(),
                    ConfiguredTargetKey.builder().setLabel(label2).build()
                )
            }

        private val ASPECT_CLASS1: AspectClass = AspectClass { "aspect1" }
        private val ASPECT_CLASS2: AspectClass = AspectClass { "aspect2" }
        private val ASPECT_CLASS3: AspectClass = AspectClass { "aspect3" }
        private val ASPECT_CLASS4: AspectClass = AspectClass { "aspect4" }

        private val orderedAspectDescriptors: com.google.common.collect.ImmutableList<AspectDescriptor?>
            get() = com.google.common.collect.ImmutableList.of<E?>(
                AspectDescriptor.of(ASPECT_CLASS1, AspectParameters.EMPTY),
                AspectDescriptor.of(
                    ASPECT_CLASS1, Builder().addAttribute("foo", "bar").build()
                ),
                AspectDescriptor.of(ASPECT_CLASS2, AspectParameters.EMPTY)
            )

        private val orderedAspectKeys: com.google.common.collect.ImmutableList<AspectKey?>
            get() {
                val descriptors: com.google.common.collect.ImmutableList<AspectDescriptor?> =
                    orderedAspectDescriptors
                val builder: com.google.common.collect.ImmutableList.Builder<AspectKey?> =
                    com.google.common.collect.ImmutableList.builder<AspectKey?>()

                val baseDescriptor1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    AspectDescriptor.of(ASPECT_CLASS3, AspectParameters.EMPTY)
                val baseDescriptor2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    AspectDescriptor.of(ASPECT_CLASS4, AspectParameters.EMPTY)

                for (baseConfiguredTargetKey in orderedConfiguredTargetKeys) {
                    for (descriptor in descriptors) {
                        builder.add(AspectKeyCreator.createAspectKey(descriptor, baseConfiguredTargetKey))
                    }

                    // Constructs some additional keys that differ only in graph structure.
                    val baseKey1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        AspectKeyCreator.createAspectKey(baseDescriptor1, baseConfiguredTargetKey)
                    val baseKey2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                        AspectKeyCreator.createAspectKey(baseDescriptor2, baseConfiguredTargetKey)

                    builder.add(
                        AspectKeyCreator.createAspectKey(
                            com.google.common.collect.Iterables.getLast<T?>(descriptors),
                            com.google.common.collect.ImmutableList.of<E?>(baseKey1),
                            baseConfiguredTargetKey
                        )
                    )
                    builder.add(
                        AspectKeyCreator.createAspectKey(
                            com.google.common.collect.Iterables.getLast<T?>(descriptors),
                            com.google.common.collect.ImmutableList.of<E?>(baseKey1, baseKey2),
                            baseConfiguredTargetKey
                        )
                    )
                }

                return builder.build()
            }
    }
}
