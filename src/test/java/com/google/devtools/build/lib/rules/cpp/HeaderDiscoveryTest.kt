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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.ActionExecutionException

/** Test.  */
@RunWith(JUnit4::class)
class HeaderDiscoveryTest {
    private val fs: FileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)
    private val execRoot: Path = fs.getPath("/execroot")
    private val derivedRoot: Path = execRoot.getChild(DERIVED_SEGMENT)
    private val derivedArtifactRoot: ArtifactRoot =
        ArtifactRoot.asDerivedRoot(execRoot, RootType.OUTPUT, DERIVED_SEGMENT)
    private val sourceRoot: ArtifactRoot? = ArtifactRoot.asSourceRoot(Root.fromPath(execRoot))

    @org.junit.Test
    fun errorsWhenMissingHeaders() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>())

        org.junit.Assert.assertThrows<T?>(
            ActionExecutionException::class.java,
            org.junit.function.ThrowingRunnable {
                checkHeaderInclusion(
                    artifactResolver,
                    com.google.common.collect.ImmutableList.of<E?>(
                        derivedRoot.getRelative("tree_artifact1/foo.h"),
                        derivedRoot.getRelative("tree_artifact1/subdir/foo.h")
                    ),
                    NestedSetBuilder.create(
                        Order.STABLE_ORDER, treeArtifact(derivedRoot.getRelative("tree_artifact2"))
                    )
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_usesAsciiCaseInsensitiveResolution() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val resolvedArtifact: SourceArtifact = sourceArtifact("pkg/Include/Header.h")
        val depPath: PathFragment? = PathFragment.create("pkg/include/header.h")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                < T > eq < T ? > (depPath), < T > eq < T ? > (RepositoryName.MAIN)))
        .thenReturn(com.google.common.collect.ImmutableList.of<E?>(resolvedArtifact))

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/include/header.h")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        assertThat(result.toList()).containsExactly(resolvedArtifact)
        Mockito.verify<Any?>(artifactResolver, Mockito.never())
            .resolveSourceArtifact(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonWindowsPlatform_usesExactCaseResolution() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val resolvedArtifact: SourceArtifact = sourceArtifact("pkg/header.h")
        val depPath: PathFragment? = PathFragment.create("pkg/header.h")
        Mockito.`when`<T?>(artifactResolver.resolveSourceArtifact(< T > eq < T ? > (depPath), < T > eq < T ? > (RepositoryName.MAIN)))
        .thenReturn(resolvedArtifact)

        val result: NestedSet<Artifact?> =
            discoverInputs(
                nonWindowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/header.h")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        assertThat(result.toList()).containsExactly(resolvedArtifact)
        Mockito.verify<Any?>(artifactResolver, Mockito.never())
            .resolveSourceArtifactsAsciiCaseInsensitively(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_singleCaseInsensitiveMatch_addsToInputs() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val resolvedArtifact: SourceArtifact = sourceArtifact("pkg/Include/BaseTsd.h")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(resolvedArtifact))

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/include/basetsd.h")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        assertThat(result.toList()).containsExactly(resolvedArtifact)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_multipleCaseInsensitiveMatches_prefersActionInputs() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val declaredInput: SourceArtifact = sourceArtifact("pkg/Include/Header.h")
        val otherVariant: SourceArtifact = sourceArtifact("pkg/include/header.h")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(declaredInput, otherVariant))

        // The action has declaredInput in its inputs.
        val actionInputs: NestedSet<Artifact?> =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(declaredInput).build()

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(actionInputs),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/include/header.h")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        // Only the variant that matches the declared input should be used.
        assertThat(result.toList()).containsExactly(declaredInput)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_multipleCaseInsensitiveMatches_noActionInputMatch_addsAll() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val variant1: SourceArtifact = sourceArtifact("pkg/Include/Header.h")
        val variant2: SourceArtifact = sourceArtifact("pkg/include/header.h")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(variant1, variant2))

        // No matching source artifacts in the action inputs.
        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/INCLUDE/HEADER.H")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        // When none of the matches are in the action inputs, all variants are added.
        assertThat(result.toList()).containsExactly(variant1, variant2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_absoluteSystemInclude_matchesCaseInsensitively() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>())

        val systemIncludeDir: Path = fs.getPath("/C/Program Files/MSVC/include")
        // Compiler reports the path with different casing than the toolchain lists it.
        val dep: Path = fs.getPath("/c/program files/msvc/include/windows.h")

        // Should not throw — the absolute path should be filtered out as a system include.
        val result: NestedSet<Artifact?> =
            HeaderDiscovery.discoverInputsFromDependencies(
                windowsAction(),
                ActionsTestUtil.createArtifact(
                    derivedArtifactRoot,
                    derivedRoot.getRelative("foo.cc")
                ),  /* shouldValidateInclusions= */
                true,
                com.google.common.collect.ImmutableList.of<E?>(dep),  /* permittedSystemIncludePrefixes= */
                com.google.common.collect.ImmutableList.of<E?>(systemIncludeDir),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                execRoot,
                artifactResolver,  /* siblingRepositoryLayout= */
                false,
                PathMapper.NOOP
            )

        assertThat(result.toList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_absoluteSystemInclude_exactCaseAlsoMatches() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>())

        val systemIncludeDir: Path = fs.getPath("/C/Program Files/MSVC/include")
        val dep: Path = fs.getPath("/C/Program Files/MSVC/include/windows.h")

        val result: NestedSet<Artifact?> =
            HeaderDiscovery.discoverInputsFromDependencies(
                windowsAction(),
                ActionsTestUtil.createArtifact(
                    derivedArtifactRoot,
                    derivedRoot.getRelative("foo.cc")
                ),  /* shouldValidateInclusions= */
                true,
                com.google.common.collect.ImmutableList.of<E?>(dep),  /* permittedSystemIncludePrefixes= */
                com.google.common.collect.ImmutableList.of<E?>(systemIncludeDir),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                execRoot,
                artifactResolver,  /* siblingRepositoryLayout= */
                false,
                PathMapper.NOOP
            )

        assertThat(result.toList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_sourceFileFilteredOut() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val sourceFile: SourceArtifact = sourceArtifact("pkg/foo.cc")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(sourceFile))

        val result: NestedSet<Artifact?> =
            HeaderDiscovery.discoverInputsFromDependencies(
                windowsAction(),
                sourceFile,  /* shouldValidateInclusions= */
                true,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/foo.cc")),  /* permittedSystemIncludePrefixes= */
                com.google.common.collect.ImmutableList.of<E?>(),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER),
                execRoot,
                artifactResolver,  /* siblingRepositoryLayout= */
                false,
                PathMapper.NOOP
            )

        // The source file itself should be filtered out as it's a mandatory input.
        assertThat(result.toList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_derivedArtifactMatchedExactly() {
        val artifactResolver: ArtifactResolver? = Mockito.mock<ArtifactResolver?>(ArtifactResolver::class.java)
        val derivedArtifact: Artifact? =
            ActionsTestUtil.createArtifact(derivedArtifactRoot, derivedRoot.getRelative("gen/foo.h"))

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(derivedRoot.getRelative("gen/foo.h")),
                NestedSetBuilder.create(Order.STABLE_ORDER, derivedArtifact)
            )

        // Derived artifacts should be matched by exact path, not going through
        // case-insensitive resolution.
        assertThat(result.toList()).containsExactly(derivedArtifact)
        Mockito.verify<Any?>(artifactResolver, Mockito.never())
            .resolveSourceArtifactsAsciiCaseInsensitively(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        Mockito.verify<Any?>(artifactResolver, Mockito.never())
            .resolveSourceArtifact(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
    }

    @org.junit.Test
    fun windowsPlatform_unresolvedSourcePath_errors() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>())

        org.junit.Assert.assertThrows<T?>(
            ActionExecutionException::class.java,
            org.junit.function.ThrowingRunnable {
                discoverInputs(
                    windowsAction(),
                    artifactResolver,
                    com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/missing.h")),
                    NestedSetBuilder.emptySet(Order.STABLE_ORDER)
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_multipleDeps_mixedResolution() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val sourceHeader: SourceArtifact = sourceArtifact("pkg/source.h")
        val derivedHeader: Artifact? =
            ActionsTestUtil.createArtifact(derivedArtifactRoot, derivedRoot.getRelative("gen/gen.h"))

        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                eq(PathFragment.create("pkg/source.h")), ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(sourceHeader))

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(
                    execRoot.getRelative("pkg/source.h"), derivedRoot.getRelative("gen/gen.h")
                ),
                NestedSetBuilder.create(Order.STABLE_ORDER, derivedHeader)
            )

        assertThat(result.toList()).containsExactly(sourceHeader, derivedHeader)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun windowsPlatform_multipleCaseInsensitiveMatches_onlyOneInActionInputs() {
        val artifactResolver: ArtifactResolver = Mockito.mock<ArtifactResolver>(ArtifactResolver::class.java)
        val variant1: SourceArtifact = sourceArtifact("pkg/Header.h")
        val variant2: SourceArtifact = sourceArtifact("pkg/header.h")
        val variant3: SourceArtifact = sourceArtifact("pkg/HEADER.h")
        Mockito.`when`<T?>(
            artifactResolver.resolveSourceArtifactsAsciiCaseInsensitively(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenReturn(com.google.common.collect.ImmutableList.of<E?>(variant1, variant2, variant3))

        val actionInputs: NestedSet<Artifact?> =
            NestedSetBuilder.< Artifact > stableOrder < Artifact ? > ().add(variant2).build()

        val result: NestedSet<Artifact?> =
            discoverInputs(
                windowsAction(actionInputs),
                artifactResolver,
                com.google.common.collect.ImmutableList.of<E?>(execRoot.getRelative("pkg/HEADER.h")),
                NestedSetBuilder.emptySet(Order.STABLE_ORDER)
            )

        // Only variant2 is in the action inputs, so only it should be selected.
        assertThat(result.toList()).containsExactly(variant2)
    }

    // Helpers
    @Throws(ActionExecutionException::class)
    private fun checkHeaderInclusion(
        artifactResolver: ArtifactResolver?,
        dependencies: com.google.common.collect.ImmutableList<Path?>?,
        includedHeaders: NestedSet<Artifact?>?
    ) {
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            HeaderDiscovery.discoverInputsFromDependencies(
                NullAction(),
                ActionsTestUtil.createArtifact(
                    derivedArtifactRoot,
                    derivedRoot.getRelative("foo.cc")
                ),  /* shouldValidateInclusions= */
                true,
                dependencies,  /* permittedSystemIncludePrefixes= */
                com.google.common.collect.ImmutableList.of<E?>(),
                includedHeaders,
                execRoot,
                artifactResolver,  /* siblingRepositoryLayout= */
                false,
                PathMapper.NOOP
            )
    }

    @Throws(ActionExecutionException::class)
    private fun discoverInputs(
        action: NullAction?,
        artifactResolver: ArtifactResolver?,
        dependencies: MutableList<Path?>?,
        allowedDerivedInputs: NestedSet<Artifact?>?
    ): NestedSet<Artifact?> {
        return HeaderDiscovery.discoverInputsFromDependencies(
            action,
            ActionsTestUtil.createArtifact(
                derivedArtifactRoot,
                derivedRoot.getRelative("foo.cc")
            ),  /* shouldValidateInclusions= */
            true,
            dependencies,  /* permittedSystemIncludePrefixes= */
            com.google.common.collect.ImmutableList.of<E?>(),
            allowedDerivedInputs,
            execRoot,
            artifactResolver,  /* siblingRepositoryLayout= */
            false,
            PathMapper.NOOP
        )
    }

    private fun treeArtifact(path: Path?): SpecialArtifact {
        return SpecialArtifact.create(
            derivedArtifactRoot,
            derivedArtifactRoot
                .getExecPath()
                .getRelative(derivedArtifactRoot.getRoot().relativize(path)),
            ActionsTestUtil.NULL_ARTIFACT_OWNER,
            Artifact.SpecialArtifactType.TREE
        )
    }

    private fun sourceArtifact(execPath: String?): SourceArtifact {
        return SourceArtifact(sourceRoot, PathFragment.create(execPath), ArtifactOwner.NULL_OWNER)
    }

    private fun windowsAction(): NullAction {
        return NullAction(
            actionOwnerWithPlatform(windowsPlatform()), ActionsTestUtil.DUMMY_ARTIFACT
        )
    }

    private fun windowsAction(inputs: NestedSet<Artifact?>): NullAction {
        return NullAction(actionOwnerWithPlatform(windowsPlatform()), inputs)
    }

    private fun nonWindowsAction(): NullAction {
        return NullAction(
            actionOwnerWithPlatform(linuxPlatform()), ActionsTestUtil.DUMMY_ARTIFACT
        )
    }

    companion object {
        private const val DERIVED_SEGMENT = "derived"

        private fun windowsPlatform(): PlatformInfo {
            try {
                return PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//test:windows_platform"))
                    .addConstraint(ConstraintConstants.OS_TO_DEFAULT_CONSTRAINT_VALUE.get(com.google.devtools.build.lib.util.OS.WINDOWS))
                    .build()
            } catch (e: java.lang.Exception) {
                throw java.lang.RuntimeException(e)
            }
        }

        private fun linuxPlatform(): PlatformInfo {
            try {
                return PlatformInfo.builder()
                    .setLabel(Label.parseCanonicalUnchecked("//test:linux_platform"))
                    .addConstraint(ConstraintConstants.OS_TO_DEFAULT_CONSTRAINT_VALUE.get(com.google.devtools.build.lib.util.OS.LINUX))
                    .build()
            } catch (e: java.lang.Exception) {
                throw java.lang.RuntimeException(e)
            }
        }

        private fun actionOwnerWithPlatform(platform: PlatformInfo?): ActionOwner {
            return ActionOwner.createDummy(
                Label.parseCanonicalUnchecked("//pkg:target"),
                net.starlark.java.syntax.Location("dummy-file", 0, 0),  /* targetKind= */
                "cc_library rule",  /* buildConfigurationMnemonic= */
                "k8-fastbuild",  /* configurationChecksum= */
                "checksum",  /* buildConfigurationEvent= */
                null,  /* isToolConfiguration= */
                false,
                platform,  /* aspectDescriptors= */
                com.google.common.collect.ImmutableList.of<E?>(),  /* execProperties= */
                com.google.common.collect.ImmutableMap.of<K?, V?>()
            )
        }
    }
}
