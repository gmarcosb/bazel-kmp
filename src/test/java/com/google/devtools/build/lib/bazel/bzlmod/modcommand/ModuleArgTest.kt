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
import java.util.function.Function

@RunWith(JUnit4::class)
class ModuleArgTest {
    @Test
    @Throws(Exception::class)
    fun converter() {
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("<root>"))
            .isEqualTo(SpecificVersionOfModule.create(ModuleKey.ROOT))
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("@@abc"))
            .isEqualTo(CanonicalRepoName.create(RepositoryName.createUnvalidated("abc")))
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("@abc"))
            .isEqualTo(ApparentRepoName.create("abc"))
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("abc"))
            .isEqualTo(AllVersionsOfModule.create("abc"))
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("a@b"))
            .isEqualTo(SpecificVersionOfModule.create(BzlmodTestUtil.createModuleKey("a", "b")))
        Truth.assertThat(ModuleArgConverter.INSTANCE.convert("a@3.1.0-pre"))
            .isEqualTo(SpecificVersionOfModule.create(BzlmodTestUtil.createModuleKey("a", "3.1.0-pre")))

        Assert.assertThrows<T?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { ModuleArgConverter.INSTANCE.convert("abc@") })
        Assert.assertThrows<T?>(
            OptionsParsingException::class.java,
            ThrowingRunnable { ModuleArgConverter.INSTANCE.convert("@_abc") })
    }

    // For the resolveToX test cases, we build a very, very simple dep graph, where root originally
    // depends on foo@1.0, but it's magically upgraded to foo@2.0. The dependency has an affectionate
    // repo name of "fred".
    var foo1: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
    var foo2: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "2.0")
    var modulesIndex: ImmutableMap<String?, ImmutableSet<ModuleKey?>?> =
        ImmutableMap.of<String?, ImmutableSet<ModuleKey?>?>(
            "",
            ImmutableSet.of<ModuleKey?>(ModuleKey.ROOT),
            "foo",
            ImmutableSet.of<ModuleKey?>(foo1, foo2)
        )
    var depGraph: ImmutableMap<ModuleKey?, AugmentedModule?> = ImmutableMap.builder<ModuleKey?, AugmentedModule?>()
        .put(
            AugmentedModuleBuilder.Companion.buildAugmentedModule("", "")
                .addChangedDep(
                    "fred", "foo", "2.0", "1.0", ResolutionReason.SINGLE_VERSION_OVERRIDE
                )
                .buildEntry()
        )
        .put(
            AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "1.0").addOriginalDependant(ModuleKey.ROOT)
                .buildEntry()
        )
        .put(
            AugmentedModuleBuilder.Companion.buildAugmentedModule("foo", "2.0").addStillDependant(ModuleKey.ROOT)
                .buildEntry()
        )
        .buildOrThrow()

    var moduleKeyToCanonicalNames: ImmutableMap<ModuleKey?, RepositoryName?> = depGraph.keys.stream()
        .collect(
            ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                Function { k: Any? -> k },
                ModuleKey::getCanonicalRepoNameWithVersion
            )
        )
    var baseModuleDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>("fred", foo2)
    var baseModuleUnusedDeps: ImmutableBiMap<String?, ModuleKey?> = ImmutableBiMap.of<String?, ModuleKey?>("fred", foo1)
    var rootMapping: RepositoryMapping? = BzlmodTestUtil.createRepositoryMapping(ModuleKey.ROOT, "fred", "foo+2.0")

    @Test
    @Throws(Exception::class)
    fun resolve_specificVersion_good() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SpecificVersionOfModule.create(foo2)
        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                false,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2)

        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("foo@2.0", RepositoryName.create("foo+2.0"))
    }

    @Test
    @Throws(Exception::class)
    fun resolve_specificVersion_notFound() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SpecificVersionOfModule.create(BzlmodTestUtil.createModuleKey("foo", "3.0"))
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                arg.resolveToModuleKeys(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps,  /* includeUnused= */
                    true,  /* warnUnused= */
                    true
                )
            })
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable { arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping) })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_specificVersion_unused() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            SpecificVersionOfModule.create(foo1)
        // Without --include_unused, this doesn't resolve, as foo@1.0 has been replaced by foo@2.0.
        Truth.assertThat(
            Assert.assertThrows<InvalidArgumentException?>(
                InvalidArgumentException::class.java,
                ThrowingRunnable {
                    arg.resolveToModuleKeys(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps,  /* includeUnused= */
                        false,  /* warnUnused= */
                        true
                    )
                })
        )
            .hasMessageThat()
            .contains("--include_unused")
        // With --include_unused, this resolves to foo@1.0.
        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                true,  /* warnUnused= */
                true
            )
        )
            .containsExactly(foo1)

        // resolving to repo names doesn't care about unused deps.
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable { arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping) })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_allVersions_good() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            AllVersionsOfModule.create("foo")

        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                false,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2)
        // foo1 is unused, so --include_unused would return that too
        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                true,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2, foo1)

        // resolving to repo names doesn't care about unused deps.
        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("foo@2.0", RepositoryName.create("foo+2.0"))
    }

    @Test
    @Throws(Exception::class)
    fun resolve_allVersions_notFound() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            AllVersionsOfModule.create("bar")

        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                arg.resolveToModuleKeys(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps,  /* includeUnused= */
                    true,  /* warnUnused= */
                    true
                )
            })
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable { arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping) })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_apparentRepoName_good() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ApparentRepoName.create("fred")

        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                false,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2)
        // foo1 is unused, so --include_unused would return that too
        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                true,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2, foo1)

        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("@fred", RepositoryName.create("foo+2.0"))
    }

    @Test
    @Throws(Exception::class)
    fun resolve_apparentRepoName_notFound() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            ApparentRepoName.create("brad")

        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                arg.resolveToModuleKeys(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps,  /* includeUnused= */
                    true,  /* warnUnused= */
                    true
                )
            })
        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable { arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping) })
    }

    @Test
    @Throws(Exception::class)
    fun resolve_canonicalRepoName_good() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CanonicalRepoName.create(foo2.getCanonicalRepoNameWithVersion())

        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                false,  /* warnUnused= */
                false
            )
        )
            .containsExactly(foo2)

        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("@@foo+2.0", RepositoryName.create("foo+2.0"))
    }

    @Test
    @Throws(Exception::class)
    fun resolve_canonicalRepoName_notFound() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CanonicalRepoName.create(RepositoryName.create("bar+1.0"))

        Assert.assertThrows<InvalidArgumentException?>(
            InvalidArgumentException::class.java,
            ThrowingRunnable {
                arg.resolveToModuleKeys(
                    modulesIndex,
                    depGraph,
                    moduleKeyToCanonicalNames,
                    baseModuleDeps,
                    baseModuleUnusedDeps,  /* includeUnused= */
                    true,  /* warnUnused= */
                    true
                )
            })
        // The repo need not exist in the "repo -> repo" case.
        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("@@bar+1.0", RepositoryName.create("bar+1.0"))
    }

    @Test
    @Throws(Exception::class)
    fun resolve_canonicalRepoName_unused() {
        val arg: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            CanonicalRepoName.create(foo1.getCanonicalRepoNameWithVersion())

        // Without --include_unused, this doesn't resolve, as foo@1.0 has been replaced by foo@2.0.
        Truth.assertThat(
            Assert.assertThrows<InvalidArgumentException?>(
                InvalidArgumentException::class.java,
                ThrowingRunnable {
                    arg.resolveToModuleKeys(
                        modulesIndex,
                        depGraph,
                        moduleKeyToCanonicalNames,
                        baseModuleDeps,
                        baseModuleUnusedDeps,  /* includeUnused= */
                        false,  /* warnUnused= */
                        true
                    )
                })
        )
            .hasMessageThat()
            .contains("--include_unused")
        // With --include_unused, this resolves to foo@1.0.
        assertThat(
            arg.resolveToModuleKeys(
                modulesIndex,
                depGraph,
                moduleKeyToCanonicalNames,
                baseModuleDeps,
                baseModuleUnusedDeps,  /* includeUnused= */
                true,  /* warnUnused= */
                true
            )
        )
            .containsExactly(foo1)

        // resolving to repo names doesn't care about unused deps.
        assertThat(
            arg.resolveToRepoNames(modulesIndex, depGraph, moduleKeyToCanonicalNames, rootMapping)
        )
            .containsExactly("@@foo+1.0", RepositoryName.create("foo+1.0"))
    }
}
