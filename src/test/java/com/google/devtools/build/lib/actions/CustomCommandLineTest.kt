// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.analysis.actions.CustomCommandLine.builder

/** Tests for [CustomCommandLine].  */
@RunWith(TestParameterInjector::class)
class CustomCommandLineTest {
    private var rootDir: ArtifactRoot? = null
    private var artifact1: Artifact? = null
    private var artifact2: Artifact? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun createArtifacts() {
        val scratch: Scratch = Scratch()
        rootDir = ArtifactRoot.asDerivedRoot(scratch.dir("/exec/root"), RootType.OUTPUT, "dir")
        artifact1 = ActionsTestUtil.Companion.createArtifact(rootDir, scratch.file("/exec/root/dir/file1.txt"))
        artifact2 = ActionsTestUtil.Companion.createArtifact(rootDir, scratch.file("/exec/root/dir/file2.txt"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addScalar_addsSingleArgument() {
        assertThat(builder().add("--arg").build().arguments()).containsExactly("--arg")
        assertThat(builder().addDynamicString("--arg").build().arguments()).containsExactly("--arg")
        assertThat(builder().addLabel(Label.parseCanonical("//a:b")).build().arguments())
            .containsExactly("//a:b")
        assertThat(builder().addPath(PathFragment.create("path")).build().arguments())
            .containsExactly("path")
        assertThat(builder().addExecPath(artifact1).build().arguments())
            .containsExactly("dir/file1.txt")
        assertThat(
            builder()
                .addLazyString(
                    object : OnDemandString() {
                        public override fun toString(): String {
                            return "foo"
                        }
                    })
                .build()
                .arguments()
        )
            .containsExactly("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addScalar_withConstantArg_addsStringPrependedByArg() {
        assertThat(builder().add("--arg", "val").build().arguments())
            .containsExactly("--arg", "val")
            .inOrder()
        assertThat(builder().addLabel("--arg", Label.parseCanonical("//a:b")).build().arguments())
            .containsExactly("--arg", "//a:b")
            .inOrder()
        assertThat(builder().addPath("--arg", PathFragment.create("path")).build().arguments())
            .containsExactly("--arg", "path")
            .inOrder()
        assertThat(builder().addExecPath("--arg", artifact1).build().arguments())
            .containsExactly("--arg", "dir/file1.txt")
            .inOrder()
        assertThat(
            builder()
                .addLazyString(
                    "--arg",
                    object : OnDemandString() {
                        public override fun toString(): String {
                            return "foo"
                        }
                    })
                .build()
                .arguments()
        )
            .containsExactly("--arg", "foo")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFormatted_addsCorrectlyFormattedArgument() {
        assertThat(builder().addFormatted("%s%s", "hello", "world").build().arguments())
            .containsExactly("helloworld")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addPrefixed_addsPrefixForArguments() {
        assertThat(builder().addPrefixed("prefix-", "foo").build().arguments())
            .containsExactly("prefix-foo")
        assertThat(
            builder()
                .addPrefixedLabel(
                    "prefix-", Label.parseCanonical("//a:b"),  /* mainRepoMapping= */null
                )
                .build()
                .arguments()
        )
            .containsExactly("prefix-//a:b")
        assertThat(
            builder().addPrefixedPath("prefix-", PathFragment.create("path")).build().arguments()
        )
            .containsExactly("prefix-path")
        assertThat(builder().addPrefixedExecPath("prefix-", artifact1).build().arguments())
            .containsExactly("prefix-dir/file1.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addPrefixedLabel_emitsExternalLabelInDisplayForm() {
        assertThat(
            builder()
                .addPrefixedLabel(
                    "prefix-",
                    Label.parseCanonical("@@canonical_name//a:b"),
                    RepositoryMapping.create(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "apparent_name", RepositoryName.createUnvalidated("canonical_name")
                        ),
                        RepositoryName.MAIN
                    )
                )
                .build()
                .arguments()
        )
            .containsExactly("prefix-@apparent_name//a:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addAll_addsAllArguments() {
        assertThat(builder().addAll(Companion.list<T?>("val1", "val2")).build().arguments())
            .containsExactly("val1", "val2")
            .inOrder()
        assertThat(builder().addAll(nestedSet<String?>("val1", "val2")).build().arguments())
            .containsExactly("val1", "val2")
            .inOrder()
        assertThat(
            builder()
                .addPaths(Companion.list<T?>(PathFragment.create("path1"), PathFragment.create("path2")))
                .build()
                .arguments()
        )
            .containsExactly("path1", "path2")
            .inOrder()
        assertThat(
            builder()
                .addPaths(Companion.nestedSet<T?>(PathFragment.create("path1"), PathFragment.create("path2")))
                .build()
                .arguments()
        )
            .containsExactly("path1", "path2")
            .inOrder()
        assertThat(builder().addExecPaths(Companion.list<T?>(artifact1, artifact2)).build().arguments())
            .containsExactly("dir/file1.txt", "dir/file2.txt")
            .inOrder()
        assertThat(builder().addExecPaths(Companion.nestedSet<Any?>(artifact1, artifact2)).build().arguments())
            .containsExactly("dir/file1.txt", "dir/file2.txt")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun vectorAdds_withCompileTimeArg_addsAllValuesPrependedByArg() {
        assertThat(builder().addAll("--arg", Companion.list<T?>("val1", "val2")).build().arguments())
            .containsExactly("--arg", "val1", "val2")
            .inOrder()
        assertThat(builder().addAll("--arg", nestedSet<String?>("val1", "val2")).build().arguments())
            .containsExactly("--arg", "val1", "val2")
            .inOrder()
        assertThat(
            builder()
                .addPaths("--arg", Companion.list<T?>(PathFragment.create("path1"), PathFragment.create("path2")))
                .build()
                .arguments()
        )
            .containsExactly("--arg", "path1", "path2")
            .inOrder()
        assertThat(
            builder()
                .addPaths(
                    "--arg", Companion.nestedSet<T?>(PathFragment.create("path1"), PathFragment.create("path2"))
                )
                .build()
                .arguments()
        )
            .containsExactly("--arg", "path1", "path2")
            .inOrder()
        assertThat(builder().addExecPaths("--arg", Companion.list<T?>(artifact1, artifact2)).build().arguments())
            .containsExactly("--arg", "dir/file1.txt", "dir/file2.txt")
            .inOrder()
        assertThat(builder().addExecPaths("--arg", Companion.nestedSet<Any?>(artifact1, artifact2)).build().arguments())
            .containsExactly("--arg", "dir/file1.txt", "dir/file2.txt")
            .inOrder()
    }

    private enum class CustomCommandLineMode {
        REGULAR {
            override fun addAll(vectorArg: VectorArg<String?>?): CustomCommandLine {
                return builder().addAll(vectorArg).build()
            }

            override fun addPaths(vectorArg: VectorArg<PathFragment?>?): CustomCommandLine {
                return builder().addPaths(vectorArg).build()
            }

            override fun addExecPaths(vectorArg: VectorArg<Artifact?>?): CustomCommandLine {
                return builder().addExecPaths(vectorArg).build()
            }

            override fun expected(vararg values: String?): com.google.common.collect.ImmutableList<String?> {
                return com.google.common.collect.ImmutableList.copyOf<String?>(values)
            }
        },
        WITH_CONSTANT_ARG {
            override fun addAll(vectorArg: VectorArg<String?>?): CustomCommandLine {
                return builder().addAll("--arg", vectorArg).build()
            }

            override fun addPaths(vectorArg: VectorArg<PathFragment?>?): CustomCommandLine {
                return builder().addPaths("--arg", vectorArg).build()
            }

            override fun addExecPaths(vectorArg: VectorArg<Artifact?>?): CustomCommandLine {
                return builder().addExecPaths("--arg", vectorArg).build()
            }

            override fun expected(vararg values: String?): com.google.common.collect.ImmutableList<String?> {
                return com.google.common.collect.ImmutableList.builderWithExpectedSize<String?>(values.size + 1)
                    .add("--arg")
                    .add(*values)
                    .build()
            }
        };

        abstract fun addAll(vectorArg: VectorArg<String?>?): CustomCommandLine?

        abstract fun addPaths(vectorArg: VectorArg<PathFragment?>?): CustomCommandLine?

        abstract fun addExecPaths(vectorArg: VectorArg<Artifact?>?): CustomCommandLine?

        abstract fun expected(vararg values: String?): com.google.common.collect.ImmutableList<String?>?
    }

    private enum class VectorArgMode {
        LIST {
            override fun <T> of(vararg objects: T?): SimpleVectorArg<T?> {
                return VectorArg.of(list<T?>(*objects))
            }

            override fun <T> each(vectorArg: VectorArg.Builder, vararg objects: T?): SimpleVectorArg<T?> {
                return vectorArg.each(list<T?>(*objects))
            }
        },
        NESTED_SET {
            override fun <T> of(vararg objects: T?): SimpleVectorArg<T?> {
                return VectorArg.of(nestedSet<T?>(*objects))
            }

            override fun <T> each(vectorArg: VectorArg.Builder, vararg objects: T?): SimpleVectorArg<T?> {
                return vectorArg.each(nestedSet<T?>(*objects))
            }
        };

        abstract fun <T> of(vararg objects: T?): SimpleVectorArg<T?>?

        abstract fun <T> each(vectorArg: VectorArg.Builder?, vararg objects: T?): SimpleVectorArg<T?>?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addAllVector_addsAllArguments(
        @TestParameter customCommandLine: CustomCommandLineMode,
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(customCommandLine.addAll(vectorArg.of<String?>("1", "2")).arguments())
            .containsExactlyElementsIn(customCommandLine.expected("1", "2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addAll(
                    vectorArg.of<Foo?>(foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("1", "2"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addJoinedVector_addsJoinedArguments(
        @TestParameter customCommandLine: CustomCommandLineMode,
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(
            customCommandLine
                .addAll(vectorArg.each<T?>(VectorArg.join(":"), "val1", "val2"))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("val1:val2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addPaths(
                    vectorArg.each<T?>(
                        VectorArg.join(":"),
                        PathFragment.create("path1"),
                        PathFragment.create("path2")
                    )
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("path1:path2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addExecPaths(vectorArg.each<T?>(VectorArg.join(":"), artifact1, artifact2))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("dir/file1.txt:dir/file2.txt"))
            .inOrder()
        assertThat(
            customCommandLine
                .addAll(
                    vectorArg
                        .each<T?>(VectorArg.join(":"), foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("1:2"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFormatEachVector_addsFormattedStrings(
        @TestParameter customCommandLine: CustomCommandLineMode,
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(
            customCommandLine
                .addAll(vectorArg.each<T?>(VectorArg.format("-D%s"), "val1", "val2"))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Dval1", "-Dval2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addPaths(
                    vectorArg.each<T?>(
                        VectorArg.format("-D%s"),
                        PathFragment.create("path1"),
                        PathFragment.create("path2")
                    )
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Dpath1", "-Dpath2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addExecPaths(vectorArg.each<T?>(VectorArg.format("-D%s"), artifact1, artifact2))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Ddir/file1.txt", "-Ddir/file2.txt"))
            .inOrder()
        assertThat(
            customCommandLine
                .addAll(
                    vectorArg
                        .each<T?>(VectorArg.format("-D%s"), foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-D1", "-D2"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addFormatEachJoinedVector_addsJoinedFormattedStrings(
        @TestParameter customCommandLine: CustomCommandLineMode,
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(
            customCommandLine
                .addAll(vectorArg.each<T?>(VectorArg.format("-D%s").join(":"), "val1", "val2"))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Dval1:-Dval2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addPaths(
                    vectorArg.each<T?>(
                        VectorArg.format("-D%s").join(":"),
                        PathFragment.create("path1"),
                        PathFragment.create("path2")
                    )
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Dpath1:-Dpath2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addExecPaths(
                    vectorArg.each<T?>(VectorArg.format("-D%s").join(":"), artifact1, artifact2)
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-Ddir/file1.txt:-Ddir/file2.txt"))
            .inOrder()
        assertThat(
            customCommandLine
                .addAll(
                    vectorArg
                        .each<T?>(VectorArg.format("-D%s").join(":"), foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-D1:-D2"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addBeforeEachVector_addsArgumentsEachPrependedWithArg(
        @TestParameter customCommandLine: CustomCommandLineMode,
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(
            customCommandLine
                .addAll(vectorArg.each<T?>(VectorArg.addBefore("-D"), "val1", "val2"))
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-D", "val1", "-D", "val2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addPaths(
                    vectorArg.each<T?>(
                        VectorArg.addBefore("-D"),
                        PathFragment.create("path1"),
                        PathFragment.create("path2")
                    )
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-D", "path1", "-D", "path2"))
            .inOrder()
        assertThat(
            customCommandLine
                .addExecPaths(vectorArg.each<T?>(VectorArg.addBefore("-D"), artifact1, artifact2))
                .arguments()
        )
            .containsExactlyElementsIn(
                customCommandLine.expected("-D", "dir/file1.txt", "-D", "dir/file2.txt")
            )
            .inOrder()
        assertThat(
            customCommandLine
                .addAll(
                    vectorArg
                        .each<T?>(VectorArg.addBefore("-D"), foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .arguments()
        )
            .containsExactlyElementsIn(customCommandLine.expected("-D", "1", "-D", "2"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addBeforeEachFormattedVector_addsFormattedStringsPrependedWithArg(
        @TestParameter vectorArg: VectorArgMode
    ) {
        assertThat(
            builder()
                .addAll(vectorArg.each<T?>(VectorArg.addBefore("-D").format("D%s"), "val1", "val2"))
                .build()
                .arguments()
        )
            .containsExactly("-D", "Dval1", "-D", "Dval2")
            .inOrder()
        assertThat(
            builder()
                .addPaths(
                    vectorArg.each<T?>(
                        VectorArg.addBefore("-D").format("D%s"),
                        PathFragment.create("path1"),
                        PathFragment.create("path2")
                    )
                )
                .build()
                .arguments()
        )
            .containsExactly("-D", "Dpath1", "-D", "Dpath2")
            .inOrder()
        assertThat(
            builder()
                .addExecPaths(
                    vectorArg.each<T?>(VectorArg.addBefore("-D").format("D%s"), artifact1, artifact2)
                )
                .build()
                .arguments()
        )
            .containsExactly("-D", "Ddir/file1.txt", "-D", "Ddir/file2.txt")
            .inOrder()
        assertThat(
            builder()
                .addAll(
                    vectorArg
                        .each<T?>(VectorArg.addBefore("-D").format("D%s"), foo("1"), foo("2"))
                        .mapped({ foo: Foo, args: java.util.function.Consumer<kotlin.String?> ->
                            com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo.Companion.expandToStr(
                                foo,
                                args
                            )
                        })
                )
                .build()
                .arguments()
        )
            .containsExactly("-D", "D1", "-D", "D2")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addCombinedArgs_addsAllArguments() {
        val cl: CustomCommandLine =
            builder()
                .add("--arg")
                .addAll("--args", com.google.common.collect.ImmutableList.of<E?>("abc"))
                .addExecPaths("--path1", com.google.common.collect.ImmutableList.of<E?>(artifact1))
                .addExecPath("--path2", artifact2)
                .build()
        assertThat(cl.arguments())
            .containsExactly(
                "--arg", "--args", "abc", "--path1", "dir/file1.txt", "--path2", "dir/file2.txt"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addNulls_addsNothing() {
        val treeArtifact: Artifact? = createTreeArtifact("myTreeArtifact")
        assertThat(treeArtifact).isNotNull()

        val cl: CustomCommandLine =
            builder()
                .addDynamicString(null)
                .addLabel(null)
                .addPath(null)
                .addExecPath(null)
                .addLazyString(null)
                .add("foo", null)
                .addLabel("foo", null)
                .addPath("foo", null)
                .addExecPath("foo", null)
                .addLazyString("foo", null)
                .addPrefixed("prefix", null)
                .addPrefixedLabel("prefix", null,  /* mainRepoMapping= */null)
                .addPrefixedPath("prefix", null)
                .addPrefixedExecPath("prefix", null)
                .addAll(null as com.google.common.collect.ImmutableList<String?>?)
                .addAll(com.google.common.collect.ImmutableList.of<E?>())
                .addPaths(null as com.google.common.collect.ImmutableList<PathFragment?>?)
                .addPaths(com.google.common.collect.ImmutableList.of<E?>())
                .addExecPaths(null as com.google.common.collect.ImmutableList<Artifact?>?)
                .addExecPaths(com.google.common.collect.ImmutableList.of<E?>())
                .addAll(null as NestedSet<String?>?)
                .addAll(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addPaths(null as NestedSet<PathFragment?>?)
                .addPaths(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addExecPaths(null as NestedSet<Artifact?>?)
                .addExecPaths(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addAll(VectorArg.of(null as NestedSet<String?>?))
                .addAll(VectorArg.of(NestedSetBuilder.< String > emptySet < kotlin . String ? > (Order.STABLE_ORDER)))
                .addAll("foo", null as com.google.common.collect.ImmutableList<String?>?)
                .addAll("foo", com.google.common.collect.ImmutableList.of<E?>())
                .addPaths("foo", null as com.google.common.collect.ImmutableList<PathFragment?>?)
                .addPaths("foo", com.google.common.collect.ImmutableList.of<E?>())
                .addExecPaths("foo", null as com.google.common.collect.ImmutableList<Artifact?>?)
                .addExecPaths("foo", com.google.common.collect.ImmutableList.of<E?>())
                .addAll("foo", null as NestedSet<String?>?)
                .addAll("foo", NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addPaths("foo", null as NestedSet<PathFragment?>?)
                .addPaths("foo", NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addExecPaths("foo", null as NestedSet<Artifact?>?)
                .addExecPaths("foo", NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addAll("foo", VectorArg.of(null as NestedSet<String?>?))
                .addAll(
                    "foo",
                    VectorArg.of(NestedSetBuilder.< String > emptySet < kotlin . String ? > (Order.STABLE_ORDER))
                )
                .addPlaceholderTreeArtifactExecPath("foo", null)
                .build()

        assertThat(cl.arguments()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun evaluateTreeFileArtifacts_replacesTreeArtifactsWithChildrenExecPaths() {
        val treeArtifactOne: SpecialArtifact? = createTreeArtifact("myArtifact/treeArtifact1")
        val treeArtifactTwo: SpecialArtifact? = createTreeArtifact("myArtifact/treeArtifact2")

        val commandLineTemplate: CustomCommandLine =
            builder()
                .addPlaceholderTreeArtifactExecPath("--argOne", treeArtifactOne)
                .addPlaceholderTreeArtifactExecPath("--argTwo", treeArtifactTwo)
                .build()

        val treeFileArtifactOne: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifactOne, "children/child1")
        val treeFileArtifactTwo: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifactTwo, "children/child2")

        val commandLine: CustomCommandLine =
            commandLineTemplate.evaluateTreeFileArtifacts(
                com.google.common.collect.ImmutableList.of<E?>(treeFileArtifactOne, treeFileArtifactTwo)
            )

        assertThat(commandLine.arguments())
            .containsExactly(
                "--argOne",
                "dir/myArtifact/treeArtifact1/children/child1",
                "--argTwo",
                "dir/myArtifact/treeArtifact2/children/child2"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addAllMappedTreeFileArtifacts_mapToRelativePath_addsTreeFileRelativePaths() {
        val treeArtifact: SpecialArtifact? = createTreeArtifact("myArtifact/treeArtifact")

        val treeFileArtifactOne: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifact, "children/child1")
        val treeFileArtifactTwo: TreeFileArtifact? =
            TreeFileArtifact.createTreeOutput(treeArtifact, "children/child2")

        val expandParentRelativePath: CommandLineItem.MapFn<Artifact?> =
            CommandLineItem.MapFn { src, args ->
                try {
                    args.accept(src.getTreeRelativePathString())
                } catch (e: net.starlark.java.eval.EvalException) {
                    throw java.lang.IllegalStateException("Unexpected EvalException thown.", e)
                }
            }

        val commandLineTemplate: CustomCommandLine =
            builder()
                .addAll(
                    VectorArg.SimpleVectorArg.of(
                        com.google.common.collect.ImmutableList.of<E?>(treeFileArtifactOne, treeFileArtifactTwo)
                    )
                        .mapped(expandParentRelativePath)
                )
                .build()

        assertThat(commandLineTemplate.arguments())
            .containsExactly("children/child1", "children/child2")
            .inOrder()
    }

    @org.junit.Test
    fun arguments_unsubstitutedTreeArtifactPlaceholder_fails() {
        val treeArtifactOne: Artifact? = createTreeArtifact("myArtifact/treeArtifact1")
        val treeArtifactTwo: Artifact? = createTreeArtifact("myArtifact/treeArtifact2")

        val commandLineTemplate: CustomCommandLine =
            builder()
                .addPlaceholderTreeArtifactExecPath("--argOne", treeArtifactOne)
                .addPlaceholderTreeArtifactExecPath("--argTwo", treeArtifactTwo)
                .build()

        org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            commandLineTemplate::arguments
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addToFingerPrint_computesUniqueKeyForDifferentCommandLines() {
        val values: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("a").add("b").build()
        val commandLines: com.google.common.collect.ImmutableList<CustomCommandLine> =
            com.google.common.collect.ImmutableList.builder<CustomCommandLine>()
                .add(builder().add("arg").build())
                .add(builder().addFormatted("--foo=%s", "arg").build())
                .add(builder().addPrefixed("--foo=%s", "arg").build())
                .add(builder().addAll(values).build())
                .add(builder().addAll(VectorArg.addBefore("--foo=%s").each(values)).build())
                .add(builder().addAll(VectorArg.join("--foo=%s").each(values)).build())
                .add(builder().addAll(VectorArg.format("--foo=%s").each(values)).build())
                .add(
                    builder()
                        .addAll(VectorArg.of(values).mapped({ s, args -> args.accept(s.toString() + "_mapped") }))
                        .build()
                )
                .build()

        // Ensure all these command lines have distinct keys
        val actionKeyContext: ActionKeyContext = ActionKeyContext()
        val digests: MutableMap<String?, CustomCommandLine?> = HashMap<String?, CustomCommandLine?>()
        for (commandLine in commandLines) {
            val fingerprint: Fingerprint = Fingerprint()
            commandLine.addToFingerprint(
                actionKeyContext,  /* inputMetadataProvider= */
                null,
                CoreOptions.OutputPathsMode.OFF,
                fingerprint
            )
            val digest: String? = fingerprint.hexDigestAndReset()
            val previous: CustomCommandLine? = digests.putIfAbsent(digest, commandLine)
            if (previous != null) {
                org.junit.Assert.fail(
                    java.lang.String.format(
                        "Found two command lines with identical digest %s: '%s' and '%s'",
                        digest,
                        com.google.common.base.Joiner.on(' ').join(previous.arguments()),
                        com.google.common.base.Joiner.on(' ').join(commandLine.arguments())
                    )
                )
            }
        }
    }

    private fun createTreeArtifact(rootRelativePath: String?): SpecialArtifact? {
        return createTreeArtifactWithGeneratingAction(
            rootDir, rootDir.getExecPath().getRelative(rootRelativePath)
        )
    }

    private class Foo(str: String?) {
        private val str: String?

        init {
            this.str = str
        }

        companion object {
            fun expandToStr(foo: Foo, args: java.util.function.Consumer<String?>) {
                args.accept(foo.str)
            }
        }
    }

    companion object {
        private fun <T> list(vararg objects: T?): com.google.common.collect.ImmutableList<T?> {
            return com.google.common.collect.ImmutableList.copyOf<T?>(objects)
        }

        private fun <T> nestedSet(vararg objects: T?): NestedSet<T?> {
            return NestedSetBuilder.create(Order.STABLE_ORDER, objects)
        }

        private fun foo(str: String?): Foo {
            return com.google.devtools.build.lib.actions.CustomCommandLineTest.Foo(str)
        }
    }
}
