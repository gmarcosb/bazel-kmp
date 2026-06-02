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
// limitations under the License.
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.packages.Attribute.attr

/** Tests for [StarlarkBazelModule].  */
@RunWith(JUnit4::class)
class StarlarkBazelModuleTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basic() {
        val usage: ModuleExtensionUsage? =
            baseUsageBuilder
                .addTag(BzlmodTestUtil.buildTag("dep").addAttr("coord", "junit").build())
                .addTag(BzlmodTestUtil.buildTag("dep").addAttr("coord", "guava").build())
                .addTag(
                    BzlmodTestUtil.buildTag("pom")
                        .addAttr("pom_xmls", StarlarkList.immutableOf<String?>("//:pom.xml", "@bar//:pom.xml"))
                        .build()
                )
                .build()
        val extension: ModuleExtension? =
            baseExtensionBuilder
                .setTagClasses(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "dep", createTagClass(attr("coord", Type.STRING).build()),
                        "repos", createTagClass(attr("repos", Types.STRING_LIST).build()),
                        "pom",
                        createTagClass(
                            attr("pom_xmls", BuildType.LABEL_LIST)
                                .allowedFileTypes(FileTypeSet.ANY_FILE)
                                .build()
                        )
                    )
                )
                .build()
        val fooKey: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "")
        val barKey: ModuleKey = BzlmodTestUtil.createModuleKey("bar", "2.0")
        val module: java.lang.Module =
            BzlmodTestUtil.buildModule("foo", "1.0")
                .setKey(fooKey)
                .addDep("bar", barKey)
                .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>())
                .build()
        val abridgedModule: AbridgedModule? = AbridgedModule.from(module)

        val repoMappingRecorder: Label.SimpleRepoMappingRecorder = SimpleRepoMappingRecorder()
        val moduleProxy: StarlarkBazelModule =
            StarlarkBazelModule.create(
                abridgedModule,
                extension,
                module.getRepoMappingWithBazelDepsOnly(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        fooKey, fooKey.getCanonicalRepoNameWithoutVersion(),
                        barKey, barKey.getCanonicalRepoNameWithoutVersion()
                    )
                ),
                usage,
                repoMappingRecorder,  /* moduleIndex= */
                0
            )

        assertThat(moduleProxy.name).isEqualTo("foo")
        assertThat(moduleProxy.version).isEqualTo("1.0")
        assertThat(moduleProxy.tags.getFieldNames()).containsExactly("dep", "repos", "pom")

        // We have 2 "dep" tags...
        val depTags: StarlarkList<TypeCheckedTag?> =
            moduleProxy.tags.getValue("dep") as StarlarkList<TypeCheckedTag?>
        Truth.assertThat(depTags.size).isEqualTo(2)
        assertThat(depTags.get(0).getValue("coord")).isEqualTo("junit")
        assertThat(depTags.get(1).getValue("coord")).isEqualTo("guava")

        // ... zero "repos" tags...
        assertThat(moduleProxy.tags.getValue("repos")).isEqualTo(StarlarkList.empty<Any?>())

        // ... and 1 "pom" tag.
        val pomTags: StarlarkList<TypeCheckedTag?> =
            moduleProxy.tags.getValue("pom") as StarlarkList<TypeCheckedTag?>
        Truth.assertThat(pomTags.size).isEqualTo(1)
        assertThat(pomTags.get(0).getValue("pom_xmls"))
            .isEqualTo(
                StarlarkList.immutableOf<T?>(
                    Label.parseCanonical("@@foo+//:pom.xml"),
                    Label.parseCanonical("@@bar+//:pom.xml")
                )
            )

        assertThat(repoMappingRecorder.recordedEntries())
            .containsCell(RepositoryName.create("foo+"), "bar", RepositoryName.create("bar+"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unknownTagClass() {
        val usage: ModuleExtensionUsage? = baseUsageBuilder.addTag(BzlmodTestUtil.buildTag("blep").build()).build()
        val extension: ModuleExtension? =
            baseExtensionBuilder.setTagClasses(
                com.google.common.collect.ImmutableMap.of<K?, V?>(
                    "dep",
                    BzlmodTestUtil.createTagClass()
                )
            ).build()
        val fooKey: ModuleKey = BzlmodTestUtil.createModuleKey("foo", "")
        val module: java.lang.Module =
            BzlmodTestUtil.buildModule("foo", "1.0").setKey(fooKey)
                .setFlagAliases(com.google.common.collect.ImmutableMap.of<K?, V?>()).build()
        val abridgedModule: AbridgedModule? = AbridgedModule.from(module)

        val e: ExternalDepsException? =
            org.junit.Assert.assertThrows<T?>(
                ExternalDepsException::class.java,
                org.junit.function.ThrowingRunnable {
                    StarlarkBazelModule.create(
                        abridgedModule,
                        extension,
                        module.getRepoMappingWithBazelDepsOnly(
                            com.google.common.collect.ImmutableMap.of<K?, V?>(
                                fooKey,
                                fooKey.getCanonicalRepoNameWithoutVersion()
                            )
                        ),
                        usage,
                        SimpleRepoMappingRecorder(),  /* moduleIndex= */
                        0
                    )
                })
        assertThat(e).hasMessageThat().contains("does not have a tag class named blep")
    }

    companion object {
        private val baseUsageBuilder: ModuleExtensionUsage.Builder
            /** A builder for ModuleExtensionUsage that sets all the mandatory but irrelevant fields.  */
            get() = ModuleExtensionUsage.builder()
                .setExtensionBzlFile("//:rje.bzl")
                .setExtensionName("maven")
                .setIsolationKey(java.util.Optional.empty<T?>())
                .setRepoOverrides(com.google.common.collect.ImmutableMap.of<K?, V?>())

        private val baseExtensionBuilder: ModuleExtension.Builder
            /** A builder for ModuleExtension that sets all the mandatory but irrelevant fields.  */
            get() = ModuleExtension.builder()
                .setDoc(java.util.Optional.empty<T?>())
                .setDefiningBzlFileLabel(Label.parseCanonicalUnchecked("//:rje.bzl"))
                .setLocation(net.starlark.java.syntax.Location.BUILTIN)
                .setImplementation({ "maven" })
                .setEnvVariables(com.google.common.collect.ImmutableList.of<E?>())
                .setOsDependent(false)
                .setArchDependent(false)
                .setFactsVersion(0)
    }
}
