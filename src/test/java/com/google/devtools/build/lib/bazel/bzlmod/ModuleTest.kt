// Copyright 2021 The Bazel Authors. All rights reserved.
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
// limitations under the License
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.windows.WindowsPathOperations

/** Tests for [Module].  */
@RunWith(JUnit4::class)
class ModuleTest {
    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val repoMapping: Unit
        get() {
            val key: ModuleKey = BzlmodTestUtil.createModuleKey("test_module", "1.0")
            val fooKey: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
            val barKey: ModuleKey = BzlmodTestUtil.createModuleKey("bar", "2.0")
            val module: java.lang.Module =
                BzlmodTestUtil.buildModule("test_module", "1.0")
                    .addDep("my_foo", fooKey)
                    .addDep("my_bar", barKey)
                    .addDep("my_root", ModuleKey.ROOT)
                    .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .build()
            assertThat(
                module.getRepoMappingWithBazelDepsOnly(
                    java.util.stream.Stream.of<Any?>(key, fooKey, barKey, ModuleKey.ROOT)
                        .collect(
                            com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                                java.util.function.Function { k: T? -> k },
                                ModuleKey::getCanonicalRepoNameWithoutVersion
                            )
                        )
                )
            )
                .isEqualTo(
                    BzlmodTestUtil.createRepositoryMapping(
                        key,
                        "test_module",
                        "test_module+",
                        "my_foo",
                        "foo+",
                        "my_bar",
                        "bar+",
                        "my_root",
                        ""
                    )
                )
        }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val repoMapping_asMainModule: Unit
        get() {
            val fooKey: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "1.0")
            val barKey: ModuleKey = BzlmodTestUtil.createModuleKey("bar", "2.0")
            val module: java.lang.Module =
                BzlmodTestUtil.buildModule("test_module", "1.0")
                    .setKey(ModuleKey.ROOT)
                    .addDep("my_foo", BzlmodTestUtil.createModuleKey("foo", "1.0"))
                    .addDep("my_bar", BzlmodTestUtil.createModuleKey("bar", "2.0"))
                    .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                    .build()
            assertThat(
                module.getRepoMappingWithBazelDepsOnly(
                    java.util.stream.Stream.of<Any?>(ModuleKey.ROOT, fooKey, barKey)
                        .collect(
                            com.google.common.collect.ImmutableMap.toImmutableMap<T?, K?, V?>(
                                java.util.function.Function { k: T? -> k },
                                ModuleKey::getCanonicalRepoNameWithVersion
                            )
                        )
                )
            )
                .isEqualTo(
                    BzlmodTestUtil.createRepositoryMapping(
                        ModuleKey.ROOT,
                        "",
                        "",
                        "test_module",
                        "",
                        "my_foo",
                        "foo+1.0",
                        "my_bar",
                        "bar+2.0"
                    )
                )
        }

    @get:org.junit.Test
    val canonicalRepoName_isNotAWindowsShortPath: Unit
        get() {
            assertNotAShortPath(
                BzlmodTestUtil.createModuleKey("foo", "").getCanonicalRepoNameWithoutVersion().getName()
            )
            assertNotAShortPath(
                BzlmodTestUtil.createModuleKey("foo", "1").getCanonicalRepoNameWithVersion().getName()
            )
            assertNotAShortPath(
                BzlmodTestUtil.createModuleKey("foo", "1.2").getCanonicalRepoNameWithVersion().getName()
            )
            assertNotAShortPath(
                BzlmodTestUtil.createModuleKey("foo", "1.2.3").getCanonicalRepoNameWithVersion().getName()
            )
        }

    companion object {
        private fun assertNotAShortPath(name: String) {
            Truth.assertWithMessage("For %s", name).that(WindowsPathOperations.isShortPath(name)).isFalse()
        }
    }
}
