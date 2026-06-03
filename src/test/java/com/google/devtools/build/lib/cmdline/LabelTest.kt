// Copyright 2015 The Bazel Authors. All Rights Reserved.
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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.Label.PackageContext

/** Tests for [Label].  */
@RunWith(JUnit4::class)
class LabelTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsolute() {
        run {
            val l: Label = Label.parseCanonical("//foo/bar:baz")
            assertThat(l.getPackageName()).isEqualTo("foo/bar")
            assertThat(l.name).isEqualTo("baz")
        }
        run {
            val l: Label = Label.parseCanonical("//foo/bar")
            assertThat(l.getPackageName()).isEqualTo("foo/bar")
            assertThat(l.name).isEqualTo("bar")
        }
        run {
            val l: Label = Label.parseCanonical("//:bar")
            assertThat(l.getPackageName()).isEmpty()
            assertThat(l.name).isEqualTo("bar")
        }
        run {
            val l: Label = Label.parseCanonical("@foo")
            assertThat(l.getRepository().name).isEqualTo("foo")
            assertThat(l.getPackageName()).isEmpty()
            assertThat(l.name).isEqualTo("foo")
        }
        run {
            val l: Label = Label.parseCanonical("@foo//bar")
            assertThat(l.getRepository().name).isEqualTo("foo")
            assertThat(l.getPackageName()).isEqualTo("bar")
            assertThat(l.name).isEqualTo("bar")
        }
        run {
            val l: Label = Label.parseCanonical("@@foo//bar")
            assertThat(l.getRepository().name).isEqualTo("foo")
            assertThat(l.getPackageName()).isEqualTo("bar")
            assertThat(l.name).isEqualTo("bar")
        }
        run {
            val l: Label = Label.parseCanonical("//@foo")
            assertThat(l.getRepository()).isEqualTo(RepositoryName.MAIN)
            assertThat(l.getPackageName()).isEqualTo("@foo")
            assertThat(l.name).isEqualTo("@foo")
        }
        run {
            val l: Label = Label.parseCanonical("//xyz/@foo:abc")
            assertThat(l.getRepository()).isEqualTo(RepositoryName.MAIN)
            assertThat(l.getPackageName()).isEqualTo("xyz/@foo")
            assertThat(l.name).isEqualTo("abc")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseWithRepoContext() {
        val foo: RepositoryName? = RepositoryName.createUnvalidated("foo")
        val bar: RepositoryName? = RepositoryName.createUnvalidated("bar")
        val quux: RepositoryName = RepositoryName.createUnvalidated("quux")
        val repoContext: RepoContext? =
            RepoContext.of(
                foo,
                RepositoryMapping.create(com.google.common.collect.ImmutableMap.of<K?, V?>("bar", quux), foo)
            )
        run {
            val l: Label = Label.parseWithRepoContext("//lol:kek", repoContext)
            assertThat(l.getRepository()).isEqualTo(foo)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithRepoContext("@bar//lol:kek", repoContext)
            assertThat(l.getRepository()).isEqualTo(quux)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithRepoContext("@@bar//lol:kek", repoContext)
            assertThat(l.getRepository()).isEqualTo(bar)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithRepoContext("@quux//lol:kek", repoContext)
            assertThat(l.getRepository()).isEqualTo(quux.toNonVisible(foo))
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseWithPackageContext() {
        val foo: RepositoryName? = RepositoryName.createUnvalidated("foo")
        val bar: RepositoryName? = RepositoryName.createUnvalidated("bar")
        val quux: RepositoryName = RepositoryName.createUnvalidated("quux")
        val packageContext: PackageContext? =
            PackageContext.of(
                PackageIdentifier.create(foo, PathFragment.create("hah")),
                RepositoryMapping.create(com.google.common.collect.ImmutableMap.of<K?, V?>("bar", quux), foo)
            )
        run {
            val l: Label = Label.parseWithPackageContext(":kek", packageContext)
            assertThat(l.getRepository()).isEqualTo(foo)
            assertThat(l.getPackageName()).isEqualTo("hah")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithPackageContext("//lol:kek", packageContext)
            assertThat(l.getRepository()).isEqualTo(foo)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithPackageContext("@bar//lol:kek", packageContext)
            assertThat(l.getRepository()).isEqualTo(quux)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithPackageContext("@@bar//lol:kek", packageContext)
            assertThat(l.getRepository()).isEqualTo(bar)
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
        run {
            val l: Label = Label.parseWithPackageContext("@quux//lol:kek", packageContext)
            assertThat(l.getRepository()).isEqualTo(quux.toNonVisible(foo))
            assertThat(l.getPackageName()).isEqualTo("lol")
            assertThat(l.name).isEqualTo("kek")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFactory() {
        val l: Label = Label.create("foo/bar", "quux")
        assertThat(l.getPackageName()).isEqualTo("foo/bar")
        assertThat(l.name).isEqualTo("quux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIdentities() {
        val l1: Label? = Label.parseCanonical("//foo/bar:baz")
        val l2: Label? = Label.parseCanonical("//foo/bar:baz")
        val l3: Label? = Label.parseCanonical("//foo/bar:quux")

        EqualsTester().addEqualityGroup(l1, l2).addEqualityGroup(l3).testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToString() {
        run {
            val s = "@//foo/bar:baz"
            val l: Label = Label.parseCanonical(s)
            assertThat(l.toString()).isEqualTo("//foo/bar:baz")
        }
        run {
            val l: Label = Label.parseCanonical("//foo/bar")
            assertThat(l.toString()).isEqualTo("//foo/bar:bar")
        }
        run {
            val l: Label = Label.parseCanonical("@foo")
            assertThat(l.toString()).isEqualTo("@@foo//:foo")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotDot() {
        Label.parseCanonical("//foo/bar:baz..gif")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadCharacters() {
        assertSyntaxError("target names may not contain ':'", "//foo:bar:baz")
        assertSyntaxError("target names may not contain ':'", "//foo:bar:")
        assertSyntaxError("target names may not contain ':'", "//foo/bar::")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUplevelReferences() {
        assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/bar/..:baz")
        assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/../baz:baz")
        assertSyntaxError(INVALID_PACKAGE_NAME, "//../bar/baz:baz")
        assertSyntaxError(INVALID_PACKAGE_NAME, "//..:foo")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/../baz")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:../bar/baz")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/baz/..")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:..")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotAsAPathSegment() {
        assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/bar/.:baz")
        assertSyntaxError(INVALID_PACKAGE_NAME, "//foo/./baz:baz")
        assertSyntaxError(INVALID_PACKAGE_NAME, "//./bar/baz:baz")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:bar/./baz")
        assertSyntaxError(INVALID_TARGET_NAME, "//foo:./bar/baz")
        // TODO(bazel-team): enable when we have removed the "Workaround" in Label
        // that rewrites broken Labels by removing the trailing '.'
        // assertSyntaxError(INVALID_PACKAGE_NAME,
        //                  "//foo:bar/baz/.");
        // assertSyntaxError(INVALID_PACKAGE_NAME,
        //                  "//foo:.");
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTrailingDotSegment() {
        assertThat(Label.parseCanonical("//foo:dir")).isEqualTo(Label.parseCanonical("//foo:dir/."))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSomeOtherBadLabels() {
        assertSyntaxError("package names may not end with '/'", "//foo/:bar")
        assertSyntaxError("package names may not start with '/'", "///p:foo")
        assertSyntaxError("package names may not contain '//' path separators", "//a//b:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSomeGoodLabels() {
        Label.parseCanonical("//foo:..bar")
        Label.parseCanonical("//Foo:..bar")
        Label.parseCanonical("//-Foo:..bar")
        Label.parseCanonical("//00:..bar")
        Label.parseCanonical("//package:foo+bar")
        Label.parseCanonical("//package:foo_bar")
        Label.parseCanonical("//package:foo=bar")
        Label.parseCanonical("//package:foo-bar")
        Label.parseCanonical("//package:foo.bar")
        Label.parseCanonical("//package:foo@bar")
        Label.parseCanonical("//package:foo~bar")
        Label.parseCanonical("//$( ):$( )")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoubleSlashPathSeparator() {
        assertSyntaxError("package names may not contain '//' path separators", "//foo//bar:baz")
        assertSyntaxError("target names may not contain '//' path separator", "//foo:bar//baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonPrintableCharacters() {
        assertSyntaxError(
            "target names may not contain non-printable characters: '\\x02'", "//foo:..\u0002bar"
        )
    }

    /** Make sure that control characters - such as CR - are escaped on output.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidLineEndings() {
        assertSyntaxError(
            "invalid target name '..bar\\r': " + "target names may not end with carriage returns",
            "//foo:..bar\r"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyName() {
        assertSyntaxError("invalid target name '': empty target name", "//foo/bar:")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRepoLabel() {
        val label: Label = Label.parseCanonical("@foo//bar/baz:bat/boo")
        assertThat(label.toString()).isEqualTo("@@foo//bar/baz:bat/boo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoRepo() {
        val label: Label = Label.parseCanonical("//bar/baz:bat/boo")
        assertThat(label.toString()).isEqualTo("//bar/baz:bat/boo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidRepo() {
        val e: LabelSyntaxException? =
            org.junit.Assert.assertThrows<T?>(
                LabelSyntaxException::class.java,
                org.junit.function.ThrowingRunnable { Label.parseCanonical("foo//bar/baz:bat/boo") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "invalid package name 'foo//bar/baz': package names may not contain '//' path"
                        + " separators"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidRepoWithColon() {
        val e: LabelSyntaxException? =
            org.junit.Assert.assertThrows<T?>(
                LabelSyntaxException::class.java,
                org.junit.function.ThrowingRunnable { Label.parseCanonical("@foo:xyz") })
        assertThat(e)
            .hasMessageThat()
            .containsMatch("invalid repository name 'foo:xyz': repo names may contain only")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetWorkspaceRoot() {
        var label: Label = Label.parseCanonical("//bar/baz")
        assertThat(label.getWorkspaceRootForStarlarkOnly(StarlarkSemantics.DEFAULT)).isEmpty()
        label = Label.parseCanonical("@repo//bar/baz")
        assertThat(label.getWorkspaceRootForStarlarkOnly(StarlarkSemantics.DEFAULT))
            .isEqualTo("external/repo")
    }

    @org.junit.Test
    fun testGetContainingDirectory() {
        assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a:b")))
            .isEqualTo(PathFragment.create("a"))
        assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a/b:c")))
            .isEqualTo(PathFragment.create("a/b"))
        assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a:b/c")))
            .isEqualTo(PathFragment.create("a/b"))
        assertThat(Label.getContainingDirectory(Label.parseCanonicalUnchecked("//a/b/c")))
            .isEqualTo(PathFragment.create("a/b/c"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkspaceName() {
        assertThat(Label.parseCanonical("@foo//bar:baz").getWorkspaceName()).isEqualTo("foo")
        assertThat(Label.parseCanonical("//bar:baz").getWorkspaceName()).isEmpty()
        assertThat(Label.parseCanonical("@//bar:baz").getWorkspaceName()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUnambiguousCanonicalForm() {
        assertThat(Label.parseCanonical("//foo/bar:baz").getUnambiguousCanonicalForm())
            .isEqualTo("@@//foo/bar:baz")
        assertThat(Label.parseCanonical("@foo//bar:baz").getUnambiguousCanonicalForm())
            .isEqualTo("@@foo//bar:baz")
        assertThat(
            Label.create(
                PackageIdentifier.create(
                    RepositoryName.create("foo").toNonVisible(RepositoryName.create("bar")),
                    PathFragment.create("baz")
                ),
                "quux"
            )
                .getUnambiguousCanonicalForm()
        )
            .isEqualTo("@@[unknown repo 'foo' requested from @@bar]//baz:quux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisplayForm() {
        val canonicalName: RepositoryName = RepositoryName.create("canonical")
        val repositoryMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("", RepositoryName.MAIN, "local", canonicalName),
                RepositoryName.MAIN
            )

        Truth.assertThat(displayFormFor("//foo/bar:bar", repositoryMapping)).isEqualTo("//foo/bar:bar")
        Truth.assertThat(displayFormFor("//foo/bar:baz", repositoryMapping)).isEqualTo("//foo/bar:baz")

        Truth.assertThat(displayFormFor("@canonical//bar:bar", repositoryMapping))
            .isEqualTo("@local//bar:bar")
        Truth.assertThat(displayFormFor("@canonical//bar:baz", repositoryMapping))
            .isEqualTo("@local//bar:baz")
        Truth.assertThat(displayFormFor("@canonical//:canonical", repositoryMapping))
            .isEqualTo("@local//:canonical")
        Truth.assertThat(displayFormFor("@canonical//:local", repositoryMapping)).isEqualTo("@local//:local")

        Truth.assertThat(displayFormFor("@other//bar:bar", repositoryMapping)).isEqualTo("@@other//bar:bar")
        Truth.assertThat(displayFormFor("@other//bar:baz", repositoryMapping)).isEqualTo("@@other//bar:baz")
        Truth.assertThat(displayFormFor("@other//:other", repositoryMapping)).isEqualTo("@@other//:other")
        Truth.assertThat(displayFormFor("@@other", repositoryMapping)).isEqualTo("@@other//:other")

        assertThat(
            Label.parseWithRepoContext(
                "@bad//abc", RepoContext.of(RepositoryName.MAIN, repositoryMapping)
            )
                .getDisplayForm(repositoryMapping)
        )
            .isEqualTo("@@[unknown repo 'bad' requested from @@]//abc:abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisplayFormNullMapping() {
        Truth.assertThat(displayFormFor("//foo/bar:bar", null)).isEqualTo("//foo/bar:bar")
        Truth.assertThat(displayFormFor("@@foo//bar:bar", null)).isEqualTo("@@foo//bar:bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShorthandDisplayForm() {
        val canonicalName: RepositoryName = RepositoryName.create("canonical")
        val repositoryMapping: RepositoryMapping? =
            RepositoryMapping.create(
                com.google.common.collect.ImmutableMap.of<K?, V?>("", RepositoryName.MAIN, "local", canonicalName),
                RepositoryName.MAIN
            )

        Truth.assertThat(shorthandDisplayFormFor("//foo/bar:bar", repositoryMapping)).isEqualTo("//foo/bar")
        Truth.assertThat(shorthandDisplayFormFor("//foo/bar:baz", repositoryMapping))
            .isEqualTo("//foo/bar:baz")

        Truth.assertThat(shorthandDisplayFormFor("@canonical//bar:bar", repositoryMapping))
            .isEqualTo("@local//bar")
        Truth.assertThat(shorthandDisplayFormFor("@canonical//bar:baz", repositoryMapping))
            .isEqualTo("@local//bar:baz")
        Truth.assertThat(shorthandDisplayFormFor("@canonical//:canonical", repositoryMapping))
            .isEqualTo("@local//:canonical")
        Truth.assertThat(shorthandDisplayFormFor("@canonical//:local", repositoryMapping))
            .isEqualTo("@local")

        Truth.assertThat(shorthandDisplayFormFor("@other//bar:bar", repositoryMapping))
            .isEqualTo("@@other//bar")
        Truth.assertThat(shorthandDisplayFormFor("@other//bar:baz", repositoryMapping))
            .isEqualTo("@@other//bar:baz")
        Truth.assertThat(shorthandDisplayFormFor("@other//:other", repositoryMapping)).isEqualTo("@@other")
        Truth.assertThat(shorthandDisplayFormFor("@@other", repositoryMapping)).isEqualTo("@@other")

        assertThat(
            Label.parseWithRepoContext(
                "@bad//abc", RepoContext.of(RepositoryName.MAIN, repositoryMapping)
            )
                .getShorthandDisplayForm(repositoryMapping)
        )
            .isEqualTo("@@[unknown repo 'bad' requested from @@]//abc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkStrAndRepr_unambiguous() {
        var label: Label? = Label.parseCanonical("//x")
        assertThat(Starlark.str(label, StarlarkSemantics.DEFAULT)).isEqualTo("@@//x:x")
        assertThat(Starlark.repr(label, StarlarkSemantics.DEFAULT)).isEqualTo("Label(\"@@//x:x\")")

        label = Label.parseCanonical("@hello//x")
        assertThat(Starlark.str(label, StarlarkSemantics.DEFAULT)).isEqualTo("@@hello//x:x")
        assertThat(Starlark.repr(label, StarlarkSemantics.DEFAULT))
            .isEqualTo("Label(\"@@hello//x:x\")")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkStrAndRepr_ambiguous() {
        val semantics: StarlarkSemantics? =
            StarlarkSemantics.builder()
                .setBool(BuildLanguageOptions.INCOMPATIBLE_UNAMBIGUOUS_LABEL_STRINGIFICATION, false)
                .build()
        var label: Label? = Label.parseCanonical("//x")
        assertThat(Starlark.str(label, semantics)).isEqualTo("//x:x")
        assertThat(Starlark.repr(label, semantics)).isEqualTo("Label(\"//x:x\")")

        label = Label.parseCanonical("@hello//x")
        assertThat(Starlark.str(label, semantics)).isEqualTo("@@hello//x:x")
        assertThat(Starlark.repr(label, semantics)).isEqualTo("Label(\"@@hello//x:x\")")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodec() {
        SerializationTester(
            Label.parseCanonical("//foo/bar:baz"),
            Label.parseCanonical("@foo"),
            Label.parseCanonical("@@foo//bar"),
            Label.parseCanonical("//xyz/@foo:abc")
        )
            .setVerificationFunction(
                { original, deserialized -> assertThat(original).isSameInstanceAs(deserialized) })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun parseWithPackageContext_recordingRepoMapping() {
        val foo: RepositoryName = RepositoryName.createUnvalidated("foo")
        val bar: RepositoryName = RepositoryName.createUnvalidated("bar")
        val quux: RepositoryName = RepositoryName.createUnvalidated("quux")
        val fooPackageContext: PackageContext? =
            PackageContext.of(
                PackageIdentifier.create(foo, PathFragment.create("hah")),
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("bar", quux, "quux", bar),
                    foo
                )
            )
        val barPackageContext: PackageContext? =
            PackageContext.of(
                PackageIdentifier.create(bar, PathFragment.EMPTY_FRAGMENT),
                RepositoryMapping.create(
                    com.google.common.collect.ImmutableMap.of<K?, V?>("foo", quux, "quux", foo),
                    bar
                )
            )

        val recorder: Label.SimpleRepoMappingRecorder = SimpleRepoMappingRecorder()
        // Not recorded: no repo part
        var unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            Label.parseWithPackageContext("//foo/bar", fooPackageContext, recorder)
        // Recorded: <foo, bar, quux>
        unused = Label.parseWithPackageContext("@bar//foo/bar", fooPackageContext, recorder)
        // Recorded: <bar, quux, foo>
        unused = Label.parseWithPackageContext("@quux//foo/bar", barPackageContext, recorder)
        // Recorded: <bar, foo, quux>
        unused = Label.parseWithPackageContext("@foo//foo/bar", barPackageContext, recorder)
        // Not recorded: canonical repo name
        unused = Label.parseWithPackageContext("@@quux//foo/bar", fooPackageContext, recorder)

        // Recorded entries are sorted by row and then column
        assertThat(recorder.recordedEntries().cellSet())
            .containsExactly(
                com.google.common.collect.Tables.immutableCell<R?, C?, V?>(bar, "foo", quux),
                com.google.common.collect.Tables.immutableCell<R?, C?, V?>(bar, "quux", foo),
                com.google.common.collect.Tables.immutableCell<R?, C?, V?>(foo, "bar", quux)
            )
            .inOrder()
    }

    companion object {
        private const val INVALID_TARGET_NAME = "invalid target name"
        private const val INVALID_PACKAGE_NAME = "invalid package name"

        /**
         * Asserts that creating a label throws a SyntaxException.
         * 
         * @param label the label to create.
         */
        private fun assertSyntaxError(expectedError: String, label: String?) {
            val e: LabelSyntaxException? =
                org.junit.Assert.assertThrows<T?>(
                    "Label '" + label + "' did not contain a syntax error, but was expected to",
                    LabelSyntaxException::class.java,
                    org.junit.function.ThrowingRunnable { Label.parseCanonical(label) })
            assertThat(e).hasMessageThat().containsMatch(java.util.regex.Pattern.quote(expectedError))
        }

        @Throws(java.lang.Exception::class)
        private fun displayFormFor(rawLabel: String?, repositoryMapping: RepositoryMapping?): String {
            return Label.parseCanonical(rawLabel).getDisplayForm(repositoryMapping)
        }

        @Throws(java.lang.Exception::class)
        private fun shorthandDisplayFormFor(
            rawLabel: String?, repositoryMapping: RepositoryMapping?
        ): String {
            return Label.parseCanonical(rawLabel).getShorthandDisplayForm(repositoryMapping)
        }
    }
}
