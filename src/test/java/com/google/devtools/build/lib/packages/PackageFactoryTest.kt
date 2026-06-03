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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Unit tests for `PackageFactory`. Note: PackageLoadingTestCase doesn't support REPOSITORY
 * skyframe function, thus these tests cannot load external packages, `@repo://pkg`.
 */
@RunWith(JUnit4::class)
class PackageFactoryTest : PackageLoadingTestCase() {
    val extraRules: com.google.common.collect.ImmutableList<RuleDefinition?>
        get() = com.google.common.collect.ImmutableList.of<RuleDefinition?>(FAKE_CC_LIBRARY)

    private var throwOnReaddir: Path? = null

    // Overrides FileSystem.readdir for the benefit of one test method
    // (testTransientErrorsInGlobbing) that injects a failure.
    override fun createFileSystem(): FileSystem {
        return object : InMemoryFileSystem(DigestHashFunction.SHA256) {
            @Throws(IOException::class)
            public override fun readdir(path: PathFragment, followSymlinks: Boolean): MutableCollection<Dirent?> {
                if (throwOnReaddir != null && throwOnReaddir.asFragment().equals(path)) {
                    throw FileNotFoundException(path.getPathString())
                }
                return super.readdir(path, followSymlinks)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatePackage() {
        scratch.file("pkgname/BUILD", "# empty build file ")
        val pkg: java.lang.Package = getPackage("pkgname")
        Truth.assertThat(pkg.getName()).isEqualTo("pkgname")
        Truth.assertThat(com.google.common.collect.Sets.newHashSet(pkg.getTargets(Rule::class.java))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadRuleName() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("badrulename/BUILD", "fake_cc_library(name = 3)")
        val pkg: java.lang.Package = getPackage("badrulename")
        assertContainsEvent("cc_library 'name' attribute must be a string")
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoRuleName() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("badrulename/BUILD", "fake_cc_library()")
        val pkg: java.lang.Package = getPackage("badrulename")
        assertContainsEvent("cc_library rule has no 'name' attribute")
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadPackageName() {
        // This is a "shallow" syntactic error: failure to form the
        // PackageIdentifier that is the real argument to loadPackage.
        val e: LabelSyntaxException? =
            org.junit.Assert.assertThrows<T?>(
                LabelSyntaxException::class.java,
                org.junit.function.ThrowingRunnable { getPackage("not even a legal/.../label") })
        assertThat(e).hasMessageThat().contains("invalid package name 'not even a legal/.../label'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testColonInExportsFilesTargetName() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "googledata/cafe/BUILD", "exports_files(['houseads/house_ads:ca-aol_parenting_html'])"
        )
        val pkg: java.lang.Package = getPackage("googledata/cafe")
        assertContainsEvent("target names may not contain ':'")
        assertThat(pkg.getTargets(FileTarget::class.java).toString())
            .doesNotContain("houseads/house_ads:ca-aol_parenting_html")
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportsFilesVisibilityMustBeSequence() {
        expectEvalError(
            "in call to exports_files(), parameter 'visibility' got value of type 'depset', want"
                    + " 'sequence or NoneType'",
            "exports_files(srcs=[], visibility=depset(['notice']))"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportsFilesLicensesMustBeSequence() {
        expectEvalError(
            "in call to exports_files(), parameter 'licenses' got value of type 'depset', want"
                    + " 'sequence or NoneType'",
            "exports_files(srcs=[], licenses=depset(['notice']))"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageNameWithPROTECTEDIsOk() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // One "PROTECTED":
        Truth.assertThat(isValidPackageName("foo/PROTECTED/bar")).isTrue()
        // Multiple "PROTECTED"s:
        Truth.assertThat(isValidPackageName("foo/PROTECTED/bar/PROTECTED/wiz")).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun isValidPackageName(packageName: String?): Boolean {
        // Write a license decl just in case it's a third_party package:
        scratch.file(packageName + "/BUILD", "licenses(['notice'])")
        val pkg: java.lang.Package = getPackage(packageName)
        return !pkg.containsErrors()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicatedDependencies() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "has_dupe/BUILD",
            """
        fake_cc_library(name='dep')
        fake_cc_library(name='has_dupe', deps=[':dep', ':dep'])
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = getPackage("has_dupe")
        assertContainsEvent(
            "Label '//has_dupe:dep' is duplicated in the 'deps' attribute of rule 'has_dupe'"
        )
        assertThat(pkg.containsErrors()).isTrue()
        assertThat(pkg.getRule("has_dupe")).isNotNull()
        assertThat(pkg.getRule("dep")).isNotNull()
        assertThat(pkg.getRule("has_dupe").containsErrors()).isTrue()
        // All rules in an errant package are themselves errant.
        assertThat(pkg.getRule("dep").containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefixWithinSameRule1() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "fruit/orange/BUILD", "genrule(name='orange', srcs=[], outs=['a', 'a/b'], cmd='')"
        )
        val pkg: java.lang.Package = getPackage("fruit/orange")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("rule 'orange' has conflicting output files 'a/b' and 'a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefixWithinSameRule2() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "fruit/orange/BUILD", "genrule(name='orange', srcs=[], outs=['a/b', 'a'], cmd='')"
        )
        val pkg: java.lang.Package = getPackage("fruit/orange")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent("rule 'orange' has conflicting output files 'a' and 'a/b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefixBetweenRules1() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "fruit/kiwi/BUILD",
            """
        genrule(name='kiwi1', srcs=[], outs=['a'], cmd='')
        genrule(name='kiwi2', srcs=[], outs=['a/b'], cmd='')
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("fruit/kiwi")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "output file 'a/b' of rule 'kiwi2' conflicts with output file 'a' of rule 'kiwi1'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrefixBetweenRules2() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "fruit/kiwi/BUILD",
            """
        genrule(name='kiwi1', srcs=[], outs=['a/b'], cmd='')
        genrule(name='kiwi2', srcs=[], outs=['a'], cmd='')
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("fruit/kiwi")
        assertThat(pkg.containsErrors()).isTrue()
        assertContainsEvent(
            "output file 'a' of rule 'kiwi2' conflicts with output file 'a/b' of rule 'kiwi1'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageNameFunction() {
        scratch.file("pina/BUILD", "fake_cc_library(name=package_name() + '-colada')")
        val pkg: java.lang.Package = getPackage("pina")
        assertNoEvents()
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getRule("pina-colada")).isNotNull()
        assertThat(pkg.getRule("pina-colada").containsErrors()).isFalse()
        Truth.assertThat(com.google.common.collect.Sets.newHashSet(pkg.getTargets(Rule::class.java)).size)
            .isSameInstanceAs(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateRuleName() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "duplicaterulename/BUILD",
            """
        filegroup(name = 'spellcheck_proto',
                 srcs = ['spellcheck.proto'])
        fake_cc_library(name = 'spellcheck_proto')  # conflict error stops execution
        x = 1//0  # not reached
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("duplicaterulename")
        assertContainsEvent(
            "cc_library rule 'spellcheck_proto' conflicts with" + " existing filegroup rule"
        )
        assertDoesNotContainEvent("division by zero")
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildFileTargetExists() {
        scratch.file("foo/BUILD")
        val target: Target = getTarget("//foo:BUILD")
        assertThat(target.getName()).isEqualTo("BUILD")
        // Test that it's memoized:
        assertThat(getPackage(target.getLabel().getPackageIdentifier()).getTarget("BUILD"))
            .isSameInstanceAs(target)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreationOfInputFiles() {
        setBuildLanguageOptions("--incompatible_no_implicit_file_export")
        scratch.file(
            "foo/BUILD",
            "exports_files(['Z'], visibility=[\"//visibility:public\"],"
                    + " licenses=[\"restricted\"])",
            "fake_cc_library(name='W', deps=['X', 'Y', 'A'])",
            "fake_cc_library(name='X', srcs=['X'])",
            "fake_cc_library(name='Y')"
        )
        val pkg: java.lang.Package = getPackage("foo")
        assertThat(pkg.containsErrors()).isFalse()

        // X is a rule with a circular self-dependency.
        assertThat(pkg.getTarget("X").getClass()).isSameInstanceAs(Rule::class.java)

        // Y is a rule
        assertThat(pkg.getTarget("Y").getClass()).isSameInstanceAs(Rule::class.java)

        // Z is an export file with specified visibility and license specified
        val exportFileTarget: Target = pkg.getTarget("Z")
        assertThat(exportFileTarget.getClass())
            .isSameInstanceAs(VisibilityLicenseSpecifiedInputFile::class.java)
        assertThat((exportFileTarget as VisibilityLicenseSpecifiedInputFile).isVisibilitySpecified())
            .isTrue()
        assertThat(exportFileTarget.getVisibility().getDeclaredLabels())
            .containsExactly(RuleVisibility.PUBLIC_LABEL)
        assertThat((exportFileTarget as VisibilityLicenseSpecifiedInputFile).isLicenseSpecified())
            .isTrue()
        assertThat(exportFileTarget.getLicense().getLicenseTypes())
            .containsExactly(LicenseType.RESTRICTED)

        // A is an input file with private visibility
        val inputFileTarget: Target = pkg.getTarget("A")
        assertThat(inputFileTarget.getClass()).isSameInstanceAs(PrivateVisibilityInputFile::class.java)
        assertThat((inputFileTarget as PrivateVisibilityInputFile).isVisibilitySpecified()).isTrue()
        assertThat(inputFileTarget.getVisibility().getDeclaredLabels())
            .containsExactly(RuleVisibility.PRIVATE_LABEL)

        // B is nothing
        val e: NoSuchTargetException? = org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("B") })
        assertThat(e)
            .hasMessageThat()
            .contains("no such target '//foo:B': target 'B' not declared in package 'foo'")

        // These are the only input files: BUILD, Z
        val inputFiles: MutableSet<String?> = com.google.common.collect.Sets.newTreeSet<String?>()
        for (inputFile in pkg.getTargets(InputFile::class.java)) {
            inputFiles.add(inputFile.getName())
        }
        Truth.assertThat(java.util.ArrayList<String?>(inputFiles)).containsExactly("A", "BUILD", "Z").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDuplicateRuleIsNotAddedToPackage() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "dup/BUILD",
            """
        filegroup(name = 'dup_proto',
                      srcs  = ['dup.proto'])

        fake_cc_library(name = 'dup_proto',
                   srcs = ['dup.pb.cc', 'dup.pb.h'])
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("dup")
        assertContainsEvent("cc_library rule 'dup_proto' conflicts with existing filegroup rule")
        assertThat(pkg.containsErrors()).isTrue()

        val dupProto: Rule = pkg.getRule("dup_proto")
        // Check that the first rule of the given name "wins", and that each of the
        // "winning" rule's outputs is a member of the package.
        assertThat(dupProto.getRuleClass()).isEqualTo("filegroup")
        for (out in dupProto.getOutputFiles()) {
            com.google.common.truth.Subject.contains(out)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictingRuleDoesNotUpdatePackage() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        // In this test, rule2's outputs conflict with rule1, so rule2 is rejected.
        // However, we must check that neither rule2, nor any of its inputs or
        // outputs is a member of the package, and that the conflicting output file
        // "out2" still has rule1 as its getGeneratingRule().
        scratch.file(
            "conflict/BUILD",
            """
        genrule(name = 'rule1',
                cmd = '',
                srcs = ['in1', 'in2'],
                outs = ['out1', 'out2'])
        genrule(name = 'rule2',
                cmd = '',
                srcs = ['in3', 'in4'],
                outs = ['out3', 'out2'])
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("conflict")
        assertContainsEvent(
            "generated file 'out2' in rule 'rule2' "
                    + "conflicts with existing generated file from rule 'rule1'"
        )
        assertThat(pkg.containsErrors()).isTrue()

        assertThat(pkg.getRule("rule2")).isNull()

        // Ensure that rule2's "out2" didn't overwrite rule1's:
        assertThat((pkg.getTarget("out2") as OutputFile).getGeneratingRule())
            .isSameInstanceAs(pkg.getRule("rule1"))

        // None of rule2, its inputs, or its outputs should belong to pkg:
        val found: MutableList<Target?> = java.util.ArrayList<Target?>()
        for (targetName in com.google.common.collect.ImmutableList.of<String?>("rule2", "in3", "in4", "out3")) {
            try {
                found.add(pkg.getTarget(targetName))
                // No fail() here: if there's no exception, we add the name to a list
                // and we check below that it's empty.
            } catch (e: NoSuchTargetException) {
                /* good! */
            }
        }
        Truth.assertThat(found).isEmpty()
    }

    // Was: Regression test for bug "Rules declared after an error in
    // a package should be considered 'in error'".
    // Then: Regression test for bug "Why aren't ERRORS considered
    // fatal?*"
    // Now: Regression test for: execution should stop at the first EvalException;
    // all rules created prior to the exception error are marked in error.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllRulesInErrantPackageAreInError() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "error/BUILD",
            """
        genrule(name = 'rule1',
                cmd = ':',
                outs = ['out.1'])
        list = ['bad']
        x = 1//0  # dynamic error
        genrule(name = 'rule2',
                cmd = ':',
                outs = list)
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("error")
        assertContainsEvent("division by zero")

        assertThat(pkg.containsErrors()).isTrue()

        // rule1 would be fine but is still marked as in error:
        assertThat(pkg.getRule("rule1").containsErrors()).isTrue()

        // rule2's genrule is never executed.
        val rule2: Rule? = pkg.getRule("rule2")
        assertThat(rule2).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHelpfulErrorForMissingExportsFiles() {
        scratch.file("x/BUILD", "fake_cc_library(name='x', srcs=['x.cc'])")
        scratch.file("x/x.cc")
        scratch.file("x/y.cc")
        scratch.file("x/dir/dummy")

        val pkg: java.lang.Package = getPackage("x")

        assertThat(pkg.getTarget("x.cc")).isNotNull() // existing and mentioned.

        var e: NoSuchTargetException? =
            org.junit.Assert.assertThrows<T?>(
                NoSuchTargetException::class.java,
                org.junit.function.ThrowingRunnable { pkg.getTarget("y.cc") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("no such target '//x:y.cc': "
                        + "target 'y.cc' not declared in package 'x' "
                        + "defined by /workspace/x/BUILD; "
                        + "however, a source file of this name exists.  "
                        + "(Perhaps add 'exports_files([\"y.cc\"])' to x/BUILD?)")
            )

        e = org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("z.cc") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("no such target '//x:z.cc': "
                        + "target 'z.cc' not declared in package 'x' "
                        + "defined by /workspace/x/BUILD (did you mean x.cc?)")
            )

        e = org.junit.Assert.assertThrows<T?>(
            NoSuchTargetException::class.java,
            org.junit.function.ThrowingRunnable { pkg.getTarget("dir") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                ("no such target '//x:dir': target 'dir' not declared in package 'x' defined by"
                        + " /workspace/x/BUILD; however, a source directory of this name exists.  (Perhaps"
                        + " add 'exports_files([\"dir\"])' to x/BUILD, or define a filegroup?)")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestSuitesImplicitlyDependOnAllRulesInPackage() {
        scratch.file(
            "x/foo_test.bzl",
            """
        def _impl(ctx):
          pass
        foo_test = rule(implementation = _impl, test = True,
          attrs = {"srcs": attr.label_list(allow_files=True)})
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        load(':foo_test.bzl', 'foo_test')
        foo_test(name='s', srcs = ['foo.sh'])
        test_suite(name='t1')
        test_suite(name='t2', tests=[])
        test_suite(name='t3', tests=['//foo'])
        test_suite(name='t4', tests=['//foo'])
        foo_test(name='c')
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("x")

        // Things to note:
        // - The '$implicit_tests' attribute is unset unless the 'tests' attribute is unset or empty.
        // - The '$implicit_tests' attribute's value for t1 and t2 is magically able to contain both s
        //    and c, even though c is instantiated after t1 and t2 are.
        assertThat(attributes(pkg.getRule("t1")).get("\$implicit_tests", BuildType.LABEL_LIST))
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.newHashSet<E?>(
                    Label.parseCanonical("//x:c"),
                    Label.parseCanonical("//x:s")
                )
            )
        assertThat(attributes(pkg.getRule("t2")).get("\$implicit_tests", BuildType.LABEL_LIST))
            .containsExactlyElementsIn(
                com.google.common.collect.Sets.newHashSet<E?>(
                    Label.parseCanonical("//x:c"),
                    Label.parseCanonical("//x:s")
                )
            )
        assertThat(attributes(pkg.getRule("t3")).get("\$implicit_tests", BuildType.LABEL_LIST))
            .isEmpty()
        assertThat(attributes(pkg.getRule("t4")).get("\$implicit_tests", BuildType.LABEL_LIST))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageValidationFailureRegisteredAfterLoading() {
        scratch.file("x/BUILD", "# old")
        val pkg: java.lang.Package = getPackage("x")
        assertThat(pkg.containsErrors()).isFalse()

        // Install a validator.
        this.validator =
            object : PackageValidator() {
                @Throws(InvalidPackageException::class)
                public override fun validate(
                    pkg2: java.lang.Package,
                    metrics: Metrics?,
                    eventHandler: ExtendedEventHandler
                ) {
                    if (pkg2.getName() == "x") {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("warning event"))
                        throw InvalidPackageException(pkg2.getPackageIdentifier(), "nope")
                    }
                }
            }

        scratch.overwriteFile("x/BUILD", "# new") // change file to cause reloading
        invalidatePackages()

        val ex: InvalidPackageException? = org.junit.Assert.assertThrows<T?>(
            InvalidPackageException::class.java,
            org.junit.function.ThrowingRunnable { getPackage("x") })
        assertThat(ex).hasMessageThat().contains("no such package 'x': nope")
        assertContainsEvent("warning event")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDirectoryExclusion() {
        emptyFile("fruit/data/apple")
        emptyFile("fruit/data/pear")
        emptyFile("fruit/data/berry/black")
        emptyFile("fruit/data/berry/blue")
        scratch.file(
            "fruit/BUILD",
            """
        fake_cc_library(name = 'yes', srcs = glob(['data/*']))
        fake_cc_library(name = 'no',  srcs = glob(['data/*'], exclude_directories=0))
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("fruit")
        assertNoEvents()
        val yesFiles: MutableList<Label?>? = attributes(pkg.getRule("yes")).get("srcs", BuildType.LABEL_LIST)
        val noFiles: MutableList<Label?>? = attributes(pkg.getRule("no")).get("srcs", BuildType.LABEL_LIST)

        Truth.assertThat(yesFiles)
            .containsExactly(
                Label.parseCanonical("@//fruit:data/apple"),
                Label.parseCanonical("@//fruit:data/pear")
            )

        Truth.assertThat(noFiles)
            .containsExactly(
                Label.parseCanonical("@//fruit:data/apple"),
                Label.parseCanonical("@//fruit:data/pear"),
                Label.parseCanonical("@//fruit:data/berry")
            )
    }

    // TODO(bazel-team): This is really a test for GlobCache.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRecursiveGlob() {
        emptyFile("rg/a.cc")
        emptyFile("rg/foo/bar.cc")
        emptyFile("rg/foo/foo.cc")
        emptyFile("rg/foo/wiz/bam.cc")
        emptyFile("rg/foo/wiz/bum.cc")
        emptyFile("rg/foo/wiz/quid/gav.cc")
        scratch.file(
            "rg/BUILD",
            """
        fake_cc_library(name = 'ri', srcs = glob(['**/*.cc']))
        fake_cc_library(name = 're', srcs = glob(['*.cc'], exclude=['**/*.c']))
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("rg")
        assertNoEvents()

        assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "BUILD",
                "a.cc",
                "foo",
                "foo/bar.cc",
                "foo/foo.cc",
                "foo/wiz",
                "foo/wiz/bam.cc",
                "foo/wiz/bum.cc",
                "foo/wiz/quid",
                "foo/wiz/quid/gav.cc"
            ),
            "**"
        )

        assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "a.cc",
                "foo/bar.cc",
                "foo/foo.cc",
                "foo/wiz/bam.cc",
                "foo/wiz/bum.cc",
                "foo/wiz/quid/gav.cc"
            ),
            "**/*.cc"
        )
        assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>("foo/bar.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc"),
            "**/b*.cc"
        )
        assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "foo/bar.cc", "foo/foo.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc", "foo/wiz/quid/gav.cc"
            ),
            "**/*/*.cc"
        )
        assertGlob(pkg, com.google.common.collect.ImmutableList.of<String?>("foo/wiz/quid/gav.cc"), "foo/**/quid/*.cc")

        Companion.assertGlob(
            pkg,
            mutableListOf<String?>(),
            com.google.common.collect.ImmutableList.of<String?>("*.cc", "*/*.cc", "*/*/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc")
        )
        Companion.assertGlob(
            pkg,
            mutableListOf<String?>(),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc")
        )
        Companion.assertGlob(
            pkg,
            mutableListOf<String?>(),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("*.cc", "*/*.cc", "*/*/*.cc", "*/*/*/*.cc")
        )
        Companion.assertGlob(
            pkg,
            mutableListOf<String?>(),
            com.google.common.collect.ImmutableList.of<String?>("**"),
            com.google.common.collect.ImmutableList.of<String?>("*", "*/*", "*/*/*", "*/*/*/*")
        )
        Companion.assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "foo/bar.cc", "foo/foo.cc", "foo/wiz/bam.cc", "foo/wiz/bum.cc", "foo/wiz/quid/gav.cc"
            ),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("*.cc")
        )
        Companion.assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "a.cc",
                "foo/wiz/bam.cc",
                "foo/wiz/bum.cc",
                "foo/wiz/quid/gav.cc"
            ),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("*/*.cc")
        )
        Companion.assertGlob(
            pkg,
            com.google.common.collect.ImmutableList.of<String?>(
                "a.cc",
                "foo/bar.cc",
                "foo/foo.cc",
                "foo/wiz/quid/gav.cc"
            ),
            com.google.common.collect.ImmutableList.of<String?>("**/*.cc"),
            com.google.common.collect.ImmutableList.of<String?>("**/wiz/*.cc")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooManyArgumentsGlobErrors() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertGlobFails(
            "glob(['incl'],['excl'],3,True,'extraarg')",
            "glob() accepts no more than 4 positional arguments but got 5"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobEnforcesListArgument() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertGlobFails(
            "glob(1, exclude=2)",
            "in call to glob(), parameter 'include' got value of type 'int', want 'sequence'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobEnforcesListOfStringsArguments() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertGlobFails(
            "glob(['a', 'b'], exclude=['c', 42])",
            "expected value of type 'string' for element 1 of 'glob' argument, but got 42 (int)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobNegativeTest() {
        // Negative test that assertGlob does throw an error when asserting against the wrong values.
        // The AssertionError comes from FoundationTestCase.failFastHandler.
        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable {
                    assertGlobMatches( /*result=*/
                        com.google.common.collect.ImmutableList.of<String?>(
                            "Wombat1.java",
                            "This_file_doesn_t_exist.java"
                        ),  /*includes=*/
                        com.google.common.collect.ImmutableList.of<String?>("W*", "subdir"),  /*excludes=*/
                        com.google.common.collect.ImmutableList.of<String?>(),  /* excludeDirs= */
                        true
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("incorrect glob result")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobExcludeDirectories() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>("Wombat1.java", "Wombat2.java"),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*", "subdir"),  /*excludes=*/
            com.google.common.collect.ImmutableList.of<String?>(),  /* excludeDirs= */
            true
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobDoesNotExcludeDirectories() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>(
                "Wombat1.java",
                "Wombat2.java",
                "subdir"
            ),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*", "subdir"),  /*excludes=*/
            com.google.common.collect.ImmutableList.of<String?>(),  /* excludeDirs= */
            false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithEmptyExcludedList() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>("Wombat1.java", "Wombat2.java"),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*"),  /*excludes=*/
            mutableListOf<String?>(),  /* excludeDirs= */
            false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithQuestionMarkProducesError() {
        assertGlobProducesError("Wombat?.java", true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithoutQuestionMarkDoesntProduceError() {
        assertGlobProducesError("Wombat*.java", false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithNonMatchingExcludedList() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>("Wombat1.java"),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*"),  /*excludes=*/
            com.google.common.collect.ImmutableList.of<String?>("*2*"),  /* excludeDirs= */
            false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithTwoMatchingGlobExpressionsAndNonmatchingExclusion() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>("Wombat1.java", "subdir/Wombat3.java"),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*", "subdir/W*"),  /*excludes=*/
            com.google.common.collect.ImmutableList.of<String?>("*2*"),  /* excludeDirs= */
            false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithSubdirMatchAndExclusion() {
        assertGlobMatches( /*result=*/
            com.google.common.collect.ImmutableList.of<String?>("subdir/Wombat3.java"),  /*includes=*/
            com.google.common.collect.ImmutableList.of<String?>("W*", "subdir/W*"),  /*excludes=*/
            com.google.common.collect.ImmutableList.of<String?>("Wombat*.java"),  /* excludeDirs= */
            false
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadCharacterInGlob() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        assertGlobFails("glob(['?'])", "Error in glob: invalid glob pattern '?': wildcard ? forbidden")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadExcludePattern() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        // The 'exclude' check is currently only reached if the pattern is "complex".
        // This seems like a bug:
        //   assertGlobFails("glob(['BUILD'], ['/'])", "pattern cannot be absolute");
        assertGlobFails("glob(['BUILD'], ['/*/*'])", "pattern cannot be absolute")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobEscapesAt() {
        // See lib.skyframe.PackageFunctionTest.globEscapesAt and
        // https://github.com/bazelbuild/bazel/issues/10606.
        scratch.file("p/@f.txt")
        scratch.file(
            "p/BUILD",
            """
        name = glob(['*.txt'])[0]
        # Note the prepended colon
        name == ':@f.txt' or fail('got %s' % name)
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("p") // no error
        assertThat(pkg.containsErrors()).isFalse()
    }

    /**
     * Tests that a glob evaluation that encounters an I/O error throws instead of constructing a
     * package.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobWithIOErrors() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("pkg/BUILD", "glob(['globs/**'])")
        val dir: Path = scratch.dir("pkg/globs/unreadable")
        dir.setReadable(false)

        val ex: NoSuchPackageException? = org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { getPackage("pkg") })
        assertThat(ex)
            .hasMessageThat()
            .contains("error globbing [globs/**] op=FILES: " + dir + " (Permission denied)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNativeModuleIsDisabled() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("pkg/BUILD", "native.fake_cc_library(name='bar')")
        val pkg: java.lang.Package = getPackage("pkg")
        assertThat(pkg.containsErrors()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupSpecMinimal() {
        expectEvalSuccess("package_group(name='skin', packages=[])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupSpecSimple() {
        expectEvalSuccess("package_group(name='skin', packages=['//group/abelian'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupSpecEmpty() {
        expectEvalSuccess("package_group(name='seed')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupSpecIncludes() {
        expectEvalSuccess(
            "package_group(name='wine',",
            "              includes=['//wine:cabernet_sauvignon',",
            "                        '//wine:pinot_noir'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupSpecBad() {
        expectEvalError("invalid package name", "package_group(name='skin', packages=['--25:17--'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupsWithSameName() {
        expectEvalError(
            "conflicts with existing package group",
            "package_group(name='skin', packages=[])",
            "package_group(name='skin', packages=[])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupNamedArguments() {
        expectEvalError(
            "package_group() got unexpected positional argument", "package_group('skin', name = 'x')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageSpecMinimal() {
        val pkg: java.lang.Package = expectEvalSuccess("package(default_visibility=[])")
        assertThat(pkg.getPackageArgs().defaultVisibility()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageSpecSimple() {
        expectEvalSuccess("package(default_visibility=['//group:lie'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageSpecBad() {
        expectEvalError("invalid target name", "package(default_visibility=[':::'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoublePackageSpecification() {
        expectEvalError(
            "can only be used once",
            "package(default_visibility=[])",
            "package(default_visibility=[])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyPackageSpecification() {
        expectEvalError("at least one argument must be given to the 'package' function", "package()")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultTestonly() {
        val pkg: java.lang.Package = expectEvalSuccess("package(default_testonly = 1)")
        assertThat(pkg.getPackageArgs().defaultTestOnly()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultDeprecation() {
        val testMessage = "OMG PONIES!"
        val pkg: java.lang.Package = expectEvalSuccess("package(default_deprecation = \"" + testMessage + "\")")
        assertThat(pkg.getPackageArgs().defaultDeprecation()).isEqualTo(testMessage)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportsBuildFile() {
        val pkg: java.lang.Package =
            expectEvalSuccess("exports_files(['BUILD'], visibility=['//visibility:private'])")
        assertThat(pkg.getTarget("BUILD")).isEqualTo(pkg.getBuildFile())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultDeprecationPropagation() {
        val msg = "I am completely operational, and all my circuits are functioning perfectly."
        scratch.file(
            "foo/BUILD",
            "package(default_deprecation = \"" + msg + "\")",
            "filegroup(name = 'bar', srcs=['b'])"
        )
        val fooRule: Rule = getTarget("//foo:bar") as Rule
        val deprAttr: String? =
            attributes(fooRule).get("deprecation", com.google.devtools.build.lib.packages.Type.STRING)
        Truth.assertThat(deprAttr).isEqualTo(msg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultTestonlyPropagation() {
        scratch.file(
            "foo/BUILD",
            """
        package(default_testonly = 1)
        filegroup(name = 'foo', srcs=['b'])
        filegroup(name = 'bar', srcs=['b'], testonly = 0)
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("foo")

        val fooRule: Rule? = pkg.getTarget("foo") as Rule?
        assertThat(
            attributes(fooRule)
                .get("testonly", com.google.devtools.build.lib.packages.Type.BOOLEAN)
        )
            .isTrue()

        val barRule: Rule? = pkg.getTarget("bar") as Rule?
        assertThat(
            attributes(barRule)
                .get("testonly", com.google.devtools.build.lib.packages.Type.BOOLEAN)
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultDeprecationOverriding() {
        val msg = "I am completely operational, and all my circuits are functioning perfectly."
        val deceive = "OMG PONIES!"
        scratch.file(
            "foo/BUILD",
            "package(default_deprecation = \"" + deceive + "\")",
            "filegroup(name = 'bar', srcs=['b'], deprecation = \"" + msg + "\")"
        )
        val pkg: java.lang.Package = getPackage("foo")

        val fooRule: Rule? = pkg.getTarget("bar") as Rule?
        val deprAttr: String? =
            attributes(fooRule).get("deprecation", com.google.devtools.build.lib.packages.Type.STRING)
        Truth.assertThat(deprAttr).isEqualTo(msg)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageFeatures() {
        scratch.file(
            "a/BUILD",
            """
        filegroup(name='before')
        package(features=['b', 'c'])
        filegroup(name='after')
        
        """.trimIndent()
        )
        val pkg: java.lang.Package = getPackage("a")
        assertThat(pkg.getPackageArgs().features())
            .isEqualTo(FeatureSet.parse(com.google.common.collect.ImmutableList.of<E?>("b", "c")))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransientErrorsInGlobbing() {
        val buildFile: Path = scratch.file("e/BUILD", "filegroup(name = 'e', srcs = glob(['*']))")
        throwOnReaddir = buildFile.getParentDirectory()
        invalidatePackages()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            NoSuchPackageException::class.java,
            org.junit.function.ThrowingRunnable { getPackage("e") }) // symlink cycle

        throwOnReaddir = null
        invalidatePackages()

        reporter.addHandler(FoundationTestCase.failFastHandler)
        val pkg: java.lang.Package = getPackage("e") // no error
        assertThat(pkg.containsErrors()).isFalse()
        assertThat(pkg.getRule("e")).isNotNull()
        val globList = pkg.getRule("e").getAttr("srcs") as MutableList<*>?
        Truth.assertThat(globList).containsExactly(Label.parseCanonical("//e:BUILD"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportTwicePublicOK() {
        // In theory, this could be an error, but too many existing files rely on it
        // and it is okay.
        expectEvalSuccess(
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportTwicePublicOK2() {
        expectEvalSuccess(
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:private\" ])",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:private\" ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportTwiceFail() {
        expectEvalError(
            "visibility for exported file 'a.cc' declared twice",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:private\" ])",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportTwiceFail2() {
        expectEvalError(
            "visibility for exported file 'a.cc' declared twice",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:private\" ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportLicenseTwice() {
        expectEvalError(
            "licenses for exported file 'a.cc' declared twice",
            "exports_files([\"a.cc\"], licenses = [\"notice\"])",
            "exports_files([\"a.cc\"], licenses = [\"notice\"])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExportGenruleConflict() {
        expectEvalError(
            "generated file 'a.cc' in rule 'foo' conflicts with existing source file",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])",
            "genrule(name = 'foo',",
            "    outs = ['a.cc'],",
            "    cmd = '')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenruleExportConflict() {
        expectEvalError(
            "source file 'a.cc' conflicts with existing generated file from rule 'foo'",
            "genrule(name = 'foo',",
            "    outs = ['a.cc'],",
            "    cmd = '')",
            "exports_files([\"a.cc\"],",
            "    visibility = [ \"//visibility:public\" ])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testValidEnvironmentGroup() {
        expectEvalSuccess(
            "environment(name = 'foo')",
            "environment_group(name='group', environments = [':foo'], defaults = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompleteEnvironmentGroup() {
        expectEvalError(
            "environment_group() missing 1 required named argument: defaults",
            "environment(name = 'foo')",
            "environment_group(name='group', environments = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupMissingTarget() {
        expectEvalError(
            "environment //pkg:foo does not exist",
            "environment_group(name='group', environments = [':foo'], defaults = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupWrongTargetType() {
        expectEvalError(
            "//pkg:foo is not a valid environment",
            "fake_cc_library(name = 'foo')",
            "environment_group(name='group', environments = [':foo'], defaults = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupWrongPackage() {
        expectEvalError(
            "//foo:foo is not in the same package as group //pkg:group",
            "environment_group(name='group', environments = ['//foo'], defaults = ['//foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupInvalidDefault() {
        expectEvalError(
            "default //pkg:bar is not a declared environment for group //pkg:group",
            "environment(name = 'foo')",
            "environment(name = 'bar')",
            "environment_group(name='group', environments = [':foo'], defaults = [':bar'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupDuplicateEnvironments() {
        expectEvalError(
            "label '//pkg:foo' is duplicated in the 'environments' list of 'group'",
            "environment(name = 'foo')",
            "environment_group(name='group', environments = [':foo', ':foo'], defaults = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvironmentGroupDuplicateDefaults() {
        expectEvalError(
            "label '//pkg:foo' is duplicated in the 'defaults' list of 'group'",
            "environment(name = 'foo')",
            "environment_group(name='group', environments = [':foo'], defaults = [':foo', ':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleEnvironmentGroupsValidMembership() {
        expectEvalSuccess(
            "environment(name = 'foo')",
            "environment(name = 'bar')",
            "environment_group(name='foo_group', environments = [':foo'], defaults = [':foo'])",
            "environment_group(name='bar_group', environments = [':bar'], defaults = [':bar'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleEnvironmentGroupsConflictingMembership() {
        expectEvalError(
            "environment //pkg:foo belongs to both //pkg:bar_group and //pkg:foo_group",
            "environment(name = 'foo')",
            "environment(name = 'bar')",
            "environment_group(name='foo_group', environments = [':foo'], defaults = [':foo'])",
            "environment_group(name='bar_group', environments = [':foo'], defaults = [':foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFulfillsReferencesWrongTargetType() {
        expectEvalError(
            "in \"fulfills\" attribute of //pkg:foo: //pkg:bar is not a valid environment",
            "environment(name = 'foo', fulfills = [':bar'])",
            "fake_cc_library(name = 'bar')",
            "environment_group(name='foo_group', environments = [':foo'], defaults = [])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFulfillsNotInEnvironmentGroup() {
        expectEvalError(
            "in \"fulfills\" attribute of //pkg:foo: //pkg:bar is not a member of this group",
            "environment(name = 'foo', fulfills = [':bar'])",
            "environment(name = 'bar')",
            "environment_group(name='foo_group', environments = [':foo'], defaults = [])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageDefaultEnvironments() {
        val pkg: java.lang.Package =
            expectEvalSuccess(
                "package(",
                "    default_compatible_with=['//foo'],",
                "    default_restricted_to=['//bar'],",
                ")"
            )
        assertThat(pkg.getPackageArgs().defaultCompatibleWith())
            .containsExactly(Label.parseCanonical("//foo"))
        assertThat(pkg.getPackageArgs().defaultRestrictedTo())
            .containsExactly(Label.parseCanonical("//bar"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageDefaultCompatibilityDuplicates() {
        expectEvalError(
            "duplicate label(s) in default_compatible_with: //foo:foo",
            "package(default_compatible_with=['//foo', '//bar', '//foo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageDefaultRestrictionDuplicates() {
        expectEvalError(
            "duplicate label(s) in default_restricted_to: //foo:foo",
            "package(default_restricted_to=['//foo', '//bar', '//foo'])"
        )
    }

    /**
     * Defines a symbolic macro "my_macro" in //pkg:my_macro.bzl, and enables the experimental flag.
     * 
     * 
     * The macro does not define any targets.
     */
    @Throws(java.lang.Exception::class)
    private fun defineEmptyMacroBzl() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_duplicateMacroNamesDisallowed() {
        // However, note that duplicates are allowed if one is a submacro of the other.
        // See SymbolicMacroTest#submacroMayHaveSameNameAsAncestorMacros for coverage of that.
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndRuleClash_macroDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "target 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        fake_cc_library(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndRuleClash_ruleDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing target",
            """
        load(":my_macro.bzl", "my_macro")
        fake_cc_library(name = "foo")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndOutputClash_macroDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "target 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        genrule(name = "gen", outs = ["foo"], cmd = "")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndOutputClash_outputDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing target",
            """
        load(":my_macro.bzl", "my_macro")
        genrule(name = "gen", outs = ["foo"], cmd = "")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroMayCollideWithPrefixOfOutput() {
        // TODO(#19922): Currently we only prevent output file prefixes from colliding with other output
        // files, and don't check if they collide with other types of targets. If we become more
        // restrictive in the future, and to the extent we restrict collisions between macro names and
        // target names (i.e., exclusive prefixes), we should also ensure output prefixes can't collide
        // with macros.
        defineEmptyMacroBzl()
        expectEvalSuccess(
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        genrule(name = "gen", outs = ["foo/bar"], cmd = "")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndEnvironmentGroupClash_macroDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "target 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        environment(name = "env")
        environment_group(name="foo", environments = [":env"], defaults = [":env"])
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndEnvironmentGroupClash_environmentGroupDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing target",
            """
        load(":my_macro.bzl", "my_macro")
        environment(name = "env")
        environment_group(name="foo", environments = [":env"], defaults = [":env"])
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndPackageGroupClash_macroDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "target 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        package_group(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndPackageGroupClash_packageGroupDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing target",
            """
        load(":my_macro.bzl", "my_macro")
        package_group(name = "foo")
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndInputClash_macroDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "target 'foo' conflicts with an existing macro (and was not created by it)",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        exports_files(["foo"])
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_macroAndInputClash_inputDeclaredFirst() {
        defineEmptyMacroBzl()
        expectEvalError(
            "macro 'foo' conflicts with an existing target",
            """
        load(":my_macro.bzl", "my_macro")
        exports_files(["foo"])
        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_implicitlyCreatedInput_isCreatedEvenInsideMacroNamespace() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        fake_cc_library(
            name = "toplevel_target",
            srcs = ["foo_implicit"],
        )
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTarget("foo_implicit")).isInstanceOf(InputFile::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_implicitlyCreatedInput_isNotCreatedIfMacroDeclaresTarget() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(name = name + "_declared_target")
            native.fake_cc_library(name = "illegally_named_target")
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        fake_cc_library(
            name = "toplevel_target",
            srcs = [
                "foo_declared_target",
                "illegally_named_target",
            ],
        )
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTarget("foo_declared_target")).isInstanceOf(Rule::class.java)
        // This target doesn't lie within the macro's namespace and so can't be analyzed, but it still
        // exists and prevents input file creation. (Under a lazy macro evaluation model, we would
        // potentially create an InputFile for it but later discover a name clash if the macro is
        // evaluated.)
        // TODO: #23852 - Test behavior under lazy macro evaluation when implemented.
        assertThat(pkg.getTarget("illegally_named_target")).isInstanceOf(Rule::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_implicitlyCreatedInput_isNotCreatedIfMacroNameMatchesExactly() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility):
            pass
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        fake_cc_library(
            name = "toplevel_target",
            srcs = ["foo"],
        )
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTargets()).doesNotContainKey("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_implicitlyCreatedInput_isCreatedByUsageInMacroAttr() {
        // A usage in a macro, provided it is top-level, is sufficient to cause an input file to be
        // implicitly created, even if that input file is not also referred to by any actual targets.
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _impl(name, visibility, src):
            pass
        my_macro = macro(
            implementation = _impl,
            attrs = {"src": attr.label()},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(
            name = "foo",
            src = "//pkg:input",
        )
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTarget("input")).isInstanceOf(InputFile::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_implicitlyCreatedInput_isNotCreatedByUsageInMacroBody() {
        // A usage in the body of a macro (whether the declaration is for a target or submacro), does
        // not by itself cause an input file to be implicitly created.
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _sub_impl(name, visibility):
            native.fake_cc_library(
                name = name,
                srcs = ["//pkg:input"],
            )
        my_submacro = macro(implementation = _sub_impl)

        def _impl(name, visibility):
            native.fake_cc_library(
                name = name,
                srcs = ["//pkg:input"],
            )
            my_submacro(name = name + "_submacro")
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "my_macro")
        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTargets()).doesNotContainKey("input")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_deferredEvaluationExpandsTransitively() {
        scratch.file(
            "pkg/my_macro.bzl",
            """
        def _inner_impl(name, visibility):
            native.fake_cc_library(name = name)
        inner_macro = macro(implementation=_inner_impl, finalizer = True)

        def _middle_impl(name, visibility):
            inner_macro(name = name)
        middle_macro = macro(implementation=_middle_impl, finalizer = True)

        def _outer_impl(name, visibility):
            middle_macro(name = name)
        outer_macro = macro(implementation=_outer_impl, finalizer = True)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":my_macro.bzl", "outer_macro")
        outer_macro(name = "abc")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertThat(pkg.getTargets()).containsKey("abc")
        assertThat(pkg.getMacrosById().keySet()).containsExactly("abc:1", "abc:2", "abc:3")
    }

    @Throws(java.lang.Exception::class)
    private fun defineRecursiveMacro(deferredEvaluation: Boolean) {
        scratch.file(
            "pkg/recursive_macro.bzl",
            String.format(
                """
            def _impl(name, visibility, height):
                if height == 0:
                    native.fake_cc_library(name = name)
                else:
                    recursive_macro(
                        name = name + "_x",
                        height = height - 1,
                    )

            recursive_macro = macro(
                implementation = _impl,
                attrs = {
                    "height": attr.int(configurable=False),
                },
                finalizer = %s,
            )
            
            """.trimIndent(),
                if (deferredEvaluation) "True" else "False"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_recursionProhibitedWithEagerEvaluation() {
        defineRecursiveMacro( /* deferredEvaluation= */false)
        expectEvalError(
            """
        macro 'abc_x' is a direct recursive call of 'abc'. Macro instantiation traceback (most recent call last):
        ${'\t'}Package //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%recursive_macro
        ${'\t'}Package //pkg, macro 'abc_x' of type //pkg:recursive_macro.bzl%recursive_macro
        """.trimIndent(),
            """
        load(":recursive_macro.bzl", "recursive_macro")
        recursive_macro(
            name = "abc",
            height = 3,
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_recursionProhibitedWithDeferredEvaluation() {
        defineRecursiveMacro( /* deferredEvaluation= */true)
        expectEvalError(
            """
        macro 'abc_x' is a direct recursive call of 'abc'. Macro instantiation traceback (most recent call last):
        ${'\t'}Package //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%recursive_macro
        ${'\t'}Package //pkg, macro 'abc_x' of type //pkg:recursive_macro.bzl%recursive_macro
        """.trimIndent(),
            """
        load(":recursive_macro.bzl", "recursive_macro")
        recursive_macro(
            name = "abc",
            height = 3,
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSymbolicMacro_indirectRecursionAlsoProhibited() {
        // Define a pair of macros where A calls B calls A (and then would stop, if allowed to get that
        // far). Wrap it in a different entry point to test that the non-cyclic part is included in the
        // traceback.
        scratch.file(
            "pkg/recursive_macro.bzl",
            """
        def _A_impl(name, visibility, stop):
            if stop:
                native.fake_cc_library(name = name)
            else:
                macro_B(name = name + "_B")

        macro_A = macro(
            implementation = _A_impl,
            attrs = {
                "stop": attr.bool(default=False, configurable=False),
            },
        )

        def _B_impl(name, visibility):
            macro_A(
                name = name + "_A",
                stop = True,
            )

        macro_B = macro(implementation = _B_impl)

        def _main_impl(name, visibility):
            macro_A(name = name)

        main_macro = macro(implementation = _main_impl)
        
        """.trimIndent()
        )
        expectEvalError(
            """
        macro 'abc_B_A' is an indirect recursive call of 'abc'. Macro instantiation traceback (most recent call last):
        ${'\t'}Package //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%main_macro
        ${'\t'}Package //pkg, macro 'abc' of type //pkg:recursive_macro.bzl%macro_A
        ${'\t'}Package //pkg, macro 'abc_B' of type //pkg:recursive_macro.bzl%macro_B
        ${'\t'}Package //pkg, macro 'abc_B_A' of type //pkg:recursive_macro.bzl%macro_A
        """.trimIndent(),
            """
        load(":recursive_macro.bzl", "main_macro")
        main_macro(name = "abc")
        
        """.trimIndent()
        )
    }

    // TODO: #19922 - Add tests for graceful failure when the macro stack is too deep or there are too
    // many macros overall, for both eager and deferred evaluation.
    /**
     * Asserts that the target's [actual visibility][Target.getActualVisibility] contains exactly
     * the given labels.
     */
    private fun assertVisibilityIs(target: Target, vararg visibilityLabels: String?) {
        val labels: com.google.common.collect.ImmutableList.Builder<Label?> =
            com.google.common.collect.ImmutableList.builder<Label?>()
        for (item in visibilityLabels) {
            labels.add(Label.parseCanonicalUnchecked(item))
        }
        assertThat(
            target.getActualVisibility().getDeclaredLabels()
        ) // Values are sorted by virtue of visibility being a label_list.
            .containsExactlyElementsIn(labels.build())
    }

    @Throws(java.lang.Exception::class)
    private fun enableMacrosAndUsePrivateVisibility() {
        // BuildViewTestCase makes everything public by default.
        setPackageOptions("--default_visibility=private")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_occursBothInsideAndOutsideMacros() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(
                name = name,
                visibility = ["//other_pkg:__pkg__"],
            )
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        fake_cc_library(
            name = "foo",
            visibility = ["//other_pkg:__pkg__"],
        )
        my_macro(name = "bar")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//pkg:__pkg__")
        assertVisibilityIs(pkg.getTarget("bar"), "//other_pkg:__pkg__", "//lib:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_usesInnermostMacroLocation() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("inner/BUILD")
        scratch.file(
            "inner/macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(
                name = name,
                visibility = ["//other_pkg:__pkg__"],
            )
        inner_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file("outer/BUILD")
        scratch.file(
            "outer/macro.bzl",
            """
        load("//inner:macro.bzl", "inner_macro")
        def _impl(name, visibility):
            inner_macro(name = name)
        outer_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//outer:macro.bzl", "outer_macro")

        outer_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//inner:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_doesNotApplyPackageDefaultVisibility() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(name = name)
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")
        package(default_visibility = ["//other_pkg:__pkg__"])

        fake_cc_library(name = "foo")
        my_macro(name = "bar")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//pkg:__pkg__")
        // other_pkg doesn't propagate to bar, it only has its own instantiation location.
        assertVisibilityIs(pkg.getTarget("bar"), "//lib:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitVisibility_worksWithPackageDefaultVisibility() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
def _impl(name, visibility):
    native.fake_cc_library(name = name, visibility = native.package_default_visibility())
my_macro = macro(implementation = _impl)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")
        package(default_visibility = ["//other_pkg:__pkg__"])

        fake_cc_library(name = "foo")
        my_macro(name = "bar")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo"), "//other_pkg:__pkg__", "//pkg:__pkg__")
        // Package default visibility is propagated to bar via native.package_default_visibility()
        // Visibility to the package where the macro is defined is propagated implicitly.
        assertVisibilityIs(
            pkg.getTarget("bar"), "//lib:__pkg__", "//other_pkg:__pkg__", "//pkg:__pkg__"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageDefaultVisibility_playsWellWithPrivateVisibility() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
def _impl(name, visibility):
    native.fake_cc_library(name = name, visibility = native.package_default_visibility())
my_macro = macro(implementation = _impl)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")
        package(default_visibility = ["//visibility:private"])

        my_macro(name = "bar")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("bar"), "//lib:__pkg__", "//pkg:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageDefaultVisibility_succeedsIfNoDefaultVisibilitySet() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
def _impl(name, visibility):
    native.fake_cc_library(name = name, visibility = native.package_default_visibility())
my_macro = macro(implementation = _impl)

""".trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "bar")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("bar"), "//lib:__pkg__", "//pkg:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_worksWithPublicPrivateAndDuplicateVisibilities() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(
                name = name + "_public",
                visibility = ["//visibility:public"],
            )
            native.fake_cc_library(
                name = name + "_private",
                visibility = ["//visibility:private"],
            )
            native.fake_cc_library(
                name = name + "_selfvisible",
                visibility = ["//lib:__pkg__"],
            )
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo_public"), "//visibility:public")
        assertVisibilityIs(pkg.getTarget("foo_private"), "//lib:__pkg__")
        // The visibility concatenation operation does not add any label that would duplicate an
        // existing one. (Note that we can't eliminate *all* possible redundancy, since the visibility
        // list's semantics depend on expanding package_groups.)
        assertVisibilityIs(pkg.getTarget("foo_selfvisible"), "//lib:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_appliesToExportsFiles() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.exports_files([name + "_exported"])
            native.exports_files([name + "_internal"], visibility = ["//visibility:private"])
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo_exported"), "//visibility:public")
        assertVisibilityIs(pkg.getTarget("foo_internal"), "//lib:__pkg__")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_hasNoEffectOnPackageGroups() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.package_group(name = name)
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        
        """.trimIndent()
        )

        val pkg: java.lang.Package = loadPackageAndAssertSuccess("pkg")
        assertVisibilityIs(pkg.getTarget("foo"), "//visibility:public")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeclarationVisibilityUnioning_failsGracefullyOnInvalidVisibility() {
        enableMacrosAndUsePrivateVisibility()
        scratch.file("lib/BUILD")
        scratch.file(
            "lib/macro.bzl",
            """
        def _impl(name, visibility):
            native.fake_cc_library(
                name = name,
                visibility = ["//visibility:not_a_valid_specifier"],
            )
        my_macro = macro(implementation = _impl)
        
        """.trimIndent()
        )
        expectEvalError(
            "//pkg:foo Invalid visibility label '//visibility:not_a_valid_specifier'",
            """
        load("//lib:macro.bzl", "my_macro")

        my_macro(name = "foo")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGlobPatternExtractor() {
        val file: net.starlark.java.syntax.StarlarkFile? =
            net.starlark.java.syntax.StarlarkFile.parse(
                net.starlark.java.syntax.ParserInput.fromLines(
                    "pattern = '*'",
                    "some_variable = glob([",
                    "  '**/*',",
                    "  'a' + 'b',",
                    "  pattern,",
                    "])",
                    "other_variable = glob(include = ['a'], exclude = ['b'])",
                    "third_variable = glob(['c'], exclude_directories = 0)"
                )
            )
        val globs: MutableList<String?> = java.util.ArrayList<String?>()
        val globsWithDirs: MutableList<String?> = java.util.ArrayList<String?>()
        val subpackages: MutableList<String?> = java.util.ArrayList<String?>()
        PackageFactory.checkBuildSyntax(file, globs, globsWithDirs, subpackages, HashMap<K?, V?>())
        Truth.assertThat(globs).containsExactly("ab", "a", "**/*")
        Truth.assertThat(globsWithDirs).containsExactly("c")
        Truth.assertThat(subpackages).isEmpty()
    }

    // Tests of BUILD file dialect checks:
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefInBuild() {
        checkBuildDialectError(
            "def func(): pass",  //
            "functions may not be defined in BUILD files"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLambdaInBuild() {
        checkBuildDialectError(
            "lambda: None",  //
            "functions may not be defined in BUILD files"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForStatementForbiddenInBuild() {
        checkBuildDialectError(
            "for _ in []: pass",  //
            "`for` statements are not allowed in BUILD files"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfStatementForbiddenInBuild() {
        checkBuildDialectError(
            "if False: pass",  //
            "`if` statements are not allowed in BUILD files"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsForbiddenInBuild() {
        checkBuildDialectError(
            "print(**dict)",  //
            "**kwargs arguments are not allowed in BUILD files"
        )
        checkBuildDialectError(
            "len(dict(**{'a': 1}))",  //
            "**kwargs arguments are not allowed in BUILD files"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgsForbiddenInBuild() {
        checkBuildDialectError(
            "print(*['a'])",  //
            "*args arguments are not allowed in BUILD files"
        )
    }

    // Asserts that evaluation of the specified BUILD file produces the expected error.
    // Modifies: scratch, events, packages; be careful when calling more than once per @Test!
    @Throws(java.lang.Exception::class)
    private fun checkBuildDialectError(content: String?, expectedError: String?) {
        eventCollector.clear()
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.overwriteFile("p/BUILD", content)
        invalidatePackages()
        val pkg: java.lang.Package = getPackage("p")
        assertContainsEvent(expectedError)
        assertThat(pkg.containsErrors()).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun expectEvalSuccess(vararg content: String?): java.lang.Package {
        scratch.file("pkg/BUILD", *content)
        val pkg: java.lang.Package = getPackage("pkg")
        assertThat(pkg.containsErrors()).isFalse()
        return pkg
    }

    @Throws(java.lang.Exception::class)
    private fun expectEvalError(expectedError: String?, vararg content: String?) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file("pkg/BUILD", *content)
        val pkg: java.lang.Package = getPackage("pkg")
        Truth.assertWithMessage("Expected evaluation error, but none was not reported")
            .that(pkg.containsErrors())
            .isTrue()
        assertContainsEvent(expectedError)
    }

    private fun emptyFile(path: String?): Path {
        try {
            return scratch.file(path)
        } catch (e: IOException) {
            throw java.lang.IllegalStateException(e)
        }
    }

    /********************************************************************
     * *
     * Test "glob" function in build language              *
     * *
     */
    @Throws(java.lang.Exception::class)
    private fun assertGlobFails(globCallExpression: String?, expectedError: String?) {
        val pkg: java.lang.Package = buildPackageWithGlob(globCallExpression)

        assertContainsEvent(expectedError)
        assertThat(pkg.containsErrors()).isTrue()
    }

    @Throws(java.lang.Exception::class)
    private fun buildPackageWithGlob(globCallExpression: String?): java.lang.Package {
        scratch.deleteFile("dummypackage/BUILD")
        scratch.file("dummypackage/BUILD", "x = " + globCallExpression)
        return getPackage("dummypackage")
    }

    /**
     * Test globbing in the context of a package, using the build language. We use the specially setup
     * "globs" test package and the files beneath it.
     * 
     * @param result the expected list of filenames that match the glob
     * @param includes an include pattern for the glob
     * @param excludes an exclude pattern for the glob
     * @param excludeDirs an exclude_directories flag for the glob
     * @throws Exception if the glob doesn't match the expected result.
     */
    @Throws(java.lang.Exception::class)
    private fun assertGlobMatches(
        result: MutableList<String?>?,
        includes: MutableList<String?>?,
        excludes: MutableList<String?>?,
        excludeDirs: Boolean
    ) {
        // If the glob doesn't match the expected result, BUILD execution calls fail() which
        // posts an ERROR to the fail-fast handler, throwing AssertionError.
        val pkg: java.lang.Package =
            evaluateGlob(
                includes,
                excludes,
                excludeDirs,
                String.format(
                    "(result == sorted(%s)) or fail('incorrect glob result: got %%s, want %%s' %%"
                            + " (result, sorted(%s)))",
                    Starlark.repr(result, StarlarkSemantics.DEFAULT),
                    Starlark.repr(result, StarlarkSemantics.DEFAULT)
                )
            )
        // Execution succeeded. Assert that there were no other errors in the package.
        assertThat(pkg.containsErrors()).isFalse()
    }

    /**
     * Evaluate a glob() call against a test directory and BUILD code to process the results.
     * 
     * @param includes a list of glob patterns; glob will include these files.
     * @param excludes a list of glob patterns to exclude even if previously included.
     * @param excludeDirs true if directories should be excluded from the match.
     * @param resultAssertion code in the BUILD language that can access the variable result, to which
     * the result of the glob will be bound, and that may contain an assertion on it.
     * @throws AssertionError if any ERROR events are reported to the fail-fast handler during
     * execution.
     */
    // TODO(adonovan): these tests would be cleaner if they did print(glob(...)) as a side effect
    // of package loading so that the caller of loadPackage can extract and return the value,
    // for @Test methods to make assertions in the usual way.
    @Throws(java.lang.Exception::class)
    private fun evaluateGlob(
        includes: MutableList<String?>?, excludes: MutableList<String?>?, excludeDirs: Boolean, resultAssertion: String?
    ): java.lang.Package {
        val globsDir: Path = scratch.dir("globs")
        globsDir.getChild("subdir").createDirectory()
        for (file in com.google.common.collect.ImmutableList.of<String?>(
            "Wombat1.java",
            "Wombat2.java",
            "subdir/Wombat3.java"
        )) {
            FileSystemUtils.createEmptyFile(globsDir.getRelative(file))
        }
        scratch.file(
            "globs/BUILD",
            String.format(
                "result = glob(%s, exclude=%s, exclude_directories=%d, allow_empty = True)",
                Starlark.repr(includes, StarlarkSemantics.DEFAULT),
                Starlark.repr(excludes, StarlarkSemantics.DEFAULT),
                if (excludeDirs) 1 else 0
            ),
            resultAssertion
        )
        return getPackage("globs")
    }

    @Throws(java.lang.Exception::class)
    private fun assertGlobProducesError(pattern: String, errorExpected: Boolean) {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val pkg: java.lang.Package = evaluateGlob(
            com.google.common.collect.ImmutableList.of<String?>(pattern),
            com.google.common.collect.ImmutableList.of<String?>(),
            false,
            ""
        )
        assertThat(pkg.containsErrors()).isEqualTo(errorExpected)
        var foundError = false
        for (event in eventCollector) {
            if (event.getMessage().contains("glob")) {
                if (!errorExpected) {
                    org.junit.Assert.fail("error not expected for glob pattern " + pattern + ", but got: " + event)
                    return
                }
                foundError = errorExpected
                break
            }
        }
        Truth.assertThat(foundError).isEqualTo(errorExpected)
    }

    @Throws(java.lang.Exception::class)
    private fun loadPackageAndAssertSuccess(pkgid: String?): java.lang.Package {
        val pkg: java.lang.Package = getPackage(pkgid)
        assertThat(pkg.containsErrors()).isFalse()
        return pkg
    }

    companion object {
        private val FAKE_CC_LIBRARY: RuleDefinition = MockRule {
            MockRule.define(
                "fake_cc_library",
                { builder, env ->
                    builder
                        .add(attr("srcs", LABEL_LIST).legacyAllowAnyFileType())
                        .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType())
                        .add(attr("hdrs", LABEL_LIST).legacyAllowAnyFileType())
                        .add(attr("generator_name", STRING))
                        .add(attr("linkstatic", BOOLEAN))
                        .add(attr("alwayslink", BOOLEAN))
                })
        } as MockRule

        private fun attributes(rule: Rule?): AttributeMap {
            return RawAttributeMapper.of(rule)
        }

        @Throws(java.lang.Exception::class)
        private fun assertGlob(pkg: java.lang.Package, expected: MutableList<String?>?, vararg include: String?) {
            Companion.assertGlob(
                pkg,
                expected,
                com.google.common.collect.ImmutableList.copyOf<String?>(include),
                com.google.common.collect.ImmutableList.of<String?>()
            )
        }

        @Throws(java.lang.Exception::class)
        private fun assertGlob(
            pkg: java.lang.Package,
            expected: MutableList<String?>?,
            include: MutableList<String?>?,
            exclude: MutableList<String?>?
        ) {
            val executorService: ExecutorService = Executors.newFixedThreadPool(10)
            try {
                val globCache: GlobCache =
                    GlobCache(
                        pkg.getFilename().asPath().getParentDirectory(),
                        pkg.getPackageIdentifier(),
                        IgnoredSubdirectories.EMPTY,  // a package locator that finds no packages
                        object : CachingPackageLocator() {
                            public override fun getBuildFileForPackage(packageName: PackageIdentifier?): Path? {
                                return null
                            }

                            public override fun getBaseNameForLoadedPackage(packageName: PackageIdentifier?): String? {
                                return null
                            }
                        },
                        SyscallCache.NO_CACHE,
                        executorService,
                        -1,
                        ThreadStateReceiver.NULL_INSTANCE
                    )
                assertThat(globCache.globUnsorted(include, exclude, Globber.Operation.FILES_AND_DIRS, true))
                    .containsExactlyElementsIn(expected)
            } finally {
                executorService.shutdownNow()
            }
        }
    }
}
