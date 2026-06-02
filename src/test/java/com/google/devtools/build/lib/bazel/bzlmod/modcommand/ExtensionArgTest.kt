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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.common.collect.ImmutableBiMap
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import org.junit.Assert
import org.junit.Test
import org.junit.function.ThrowingRunnable
import java.util.*
import java.util.function.Function

@RunWith(JUnit4::class)
class ExtensionArgTest {
    @Test
    @Throws(Exception::class)
    fun converter() {
        Truth.assertThat(ExtensionArgConverter.INSTANCE.convert("<root>//abc:haha.bzl%ext"))
            .isEqualTo(
                ExtensionArg.create(
                    SpecificVersionOfModule.create(ModuleKey.ROOT), "//abc:haha.bzl", "ext"
                )
            )
        Truth.assertThat(ExtensionArgConverter.INSTANCE.convert("@@abc//:def.bzl%ghi"))
            .isEqualTo(
                ExtensionArg.create(
                    CanonicalRepoName.create(RepositoryName.createUnvalidated("abc")),
                    "//:def.bzl",
                    "ghi"
                )
            )
        Truth.assertThat(ExtensionArgConverter.INSTANCE.convert("@abc//:def.bzl%ghi"))
            .isEqualTo(ExtensionArg.create(ApparentRepoName.create("abc"), "//:def.bzl", "ghi"))
        Truth.assertThat(ExtensionArgConverter.INSTANCE.convert("abc//:def.bzl%ghi"))
            .isEqualTo(ExtensionArg.create(AllVersionsOfModule.create("abc"), "//:def.bzl", "ghi"))
        Truth.assertThat(ExtensionArgConverter.INSTANCE.convert("a@b//:def.bzl%ghi"))
            .isEqualTo(
                ExtensionArg.create(
                    SpecificVersionOfModule.create(BzlmodTestUtil.createModuleKey("a", "b")), "//:def.bzl", "ghi"
                )
            )

        Assert.assertThrows<T?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { ExtensionArgConverter.INSTANCE.convert("abc@//:def.bzl%ghi") })
        Assert.assertThrows<T?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { ExtensionArgConverter.INSTANCE.convert("@_abc//:def.bzl%ghi") })
        Assert.assertThrows<T?>(
            OptionsParsingException::class.java, ThrowingRunnable { ExtensionArgConverter.INSTANCE.convert("abc") })
        Assert.assertThrows<T?>(
            OptionsParsingException::class.java, ThrowingRunnable { ExtensionArgConverter.INSTANCE.convert("abc%def") })
        Assert.assertThrows<T?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { ExtensionArgConverter.INSTANCE.convert("@_abc%ghi//def") })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_good() {
        val key: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
        val modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?> =
            ImmutableMap.of<String?, ImmutableSet<ModuleKey?>?>(
                "",
                ImmutableSet.of<ModuleKey?>(ModuleKey.ROOT),
                "foo",
                ImmutableSet.of<ModuleKey?>(key)
            )
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("", "").addDep("fred", "foo", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "1.0")
                        .addStillDependant(ModuleKey.ROOT).buildEntry()
                )
                .buildOrThrow()
        val moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?> =
            depGraph.keys.stream()
                .collect(
                    ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        Function { k: Any? -> k },
                        ModuleKey::getCanonicalRepoNameWithVersion
                    )
                )
        val baseModuleDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>("fred", key)
        val baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>()

        assertThat(
            ExtensionArg.create(SpecificVersionOfModule.create(key), "//:abc.bzl", "def")
                .resolveToExtensionId(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps
                )
        )
            .isEqualTo(
                ModuleExtensionId.create(
                    Label.parseCanonical("@@foo+1.0//:abc.bzl"), "def", Optional.empty<T?>()
                )
            )
    }

    @Test
    @Throws(Exception::class)
    fun resolve_badLabel() {
        val key: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
        val modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?> =
            ImmutableMap.of<String?, ImmutableSet<ModuleKey?>?>(
                "",
                ImmutableSet.of<ModuleKey?>(ModuleKey.ROOT),
                "foo",
                ImmutableSet.of<ModuleKey?>(key)
            )
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("", "").addDep("fred", "foo", "1.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "1.0")
                        .addStillDependant(ModuleKey.ROOT).buildEntry()
                )
                .buildOrThrow()
        val moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?> =
            depGraph.keys.stream()
                .collect(
                    ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        Function { k: Any? -> k },
                        ModuleKey::getCanonicalRepoNameWithVersion
                    )
                )
        val baseModuleDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>("fred", key)
        val baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>()

        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                ExtensionArg.create(SpecificVersionOfModule.create(key), "/:def.bzl", "ext")
                    .resolveToExtensionId(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps
                    )
            })
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                ExtensionArg.create(SpecificVersionOfModule.create(key), "///////", "ext")
                    .resolveToExtensionId(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps
                    )
            })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_noneOrtooManyModules() {
        val foo1: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
        val foo2: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "2.0")
        val modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?> =
            ImmutableMap.of<String?, ImmutableSet<ModuleKey?>?>(
                "",
                ImmutableSet.of<ModuleKey?>(ModuleKey.ROOT),
                "foo",
                ImmutableSet.of<ModuleKey?>(foo1, foo2)
            )
        val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> =
            ImmutableMap.builder<ModuleKey?, AugmentedModule?>()
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("", "")
                        .addDep("foo1", "foo", "1.0")
                        .addDep("foo2", "foo", "2.0")
                        .buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "1.0")
                        .addStillDependant(ModuleKey.ROOT).buildEntry()
                )
                .put(
                    AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "2.0")
                        .addStillDependant(ModuleKey.ROOT).buildEntry()
                )
                .buildOrThrow()
        val moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?> =
            depGraph.keys.stream()
                .collect(
                    ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        Function { k: Any? -> k },
                        ModuleKey::getCanonicalRepoNameWithVersion
                    )
                )
        val baseModuleDeps: ImmutableBiMap<String?, ModuleKey?> =
            ImmutableBiMap.of<String?, ModuleKey?>("foo1", foo1, "foo2", foo2)
        val baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>()

        // Found too many, bad!
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                ExtensionArg.create(AllVersionsOfModule.create("foo"), "//:def.bzl", "ext")
                    .resolveToExtensionId(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps
                    )
            })
        // Found none, bad!
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                ExtensionArg.create(AllVersionsOfModule.create("bar"), "//:def.bzl", "ext")
                    .resolveToExtensionId(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps
                    )
            })
    }
}
