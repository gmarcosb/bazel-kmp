// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.starlark.StarlarkGlobalsImpl

/** Tests for [PackagePiece].  */ // TODO(https://github.com/bazelbuild/bazel/issues/23852): add tests that really evaluate Starlark
// (requires package piece support in PackageManager); test getTarget error case,
// tryGetTargetRecursingUp, checkMacroNamespaceCompliance, etc.
@RunWith(JUnit4::class)
class PackagePieceTest {
    private var fileSystem: FileSystem? = null
    private var noopMacroImplementation: StarlarkFunction? = null
    private var failMacroImplementation: StarlarkFunction? = null

    @Before
    @Throws(
        net.starlark.java.syntax.SyntaxError.Exception::class,
        net.starlark.java.eval.EvalException::class,
        java.lang.InterruptedException::class
    )
    fun setUp() {
        this.fileSystem = InMemoryFileSystem(DigestHashFunction.SHA256)

        val input: net.starlark.java.syntax.ParserInput? =
            net.starlark.java.syntax.ParserInput.fromLines(
                """
def noop_impl(name, visibility, **kwargs):
    pass

def fail_impl(name, visibility, **kwargs):
    fail("always fails")

""".trimIndent()
            )

        val module: net.starlark.java.eval.Module =
            net.starlark.java.eval.Module.withPredeclared(
                StarlarkSemantics.DEFAULT, StarlarkGlobalsImpl.INSTANCE.getUtilToplevels()
            )
        Mutability.create().use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            Starlark.execFile(input, net.starlark.java.syntax.FileOptions.DEFAULT, module, thread)
            this.noopMacroImplementation = module.getGlobal("noop_impl") as StarlarkFunction?
            this.failMacroImplementation = module.getGlobal("fail_impl") as StarlarkFunction?
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun identifier_equality() {
        EqualsTester()
            .addEqualityGroup(
                ForBuildFile(PackageIdentifier.createInMainRepo("test_pkg")),
                ForBuildFile(PackageIdentifier.createInMainRepo("test_pkg"))
            )
            .addEqualityGroup(
                ForBuildFile(PackageIdentifier.parse("@repo//test_pkg"))
            )
            .addEqualityGroup(
                ForMacro(
                    PackageIdentifier.createInMainRepo("test_pkg"),
                    ForBuildFile(
                        PackageIdentifier.createInMainRepo("test_pkg")
                    ),
                    "foo"
                ),
                ForMacro(
                    PackageIdentifier.createInMainRepo("test_pkg"),
                    ForBuildFile(
                        PackageIdentifier.createInMainRepo("test_pkg")
                    ),
                    "foo"
                )
            )
            .addEqualityGroup(
                ForMacro(
                    PackageIdentifier.createInMainRepo("test_pkg"),
                    ForMacro(
                        PackageIdentifier.createInMainRepo("test_pkg"),
                        ForBuildFile(
                            PackageIdentifier.createInMainRepo("test_pkg")
                        ),
                        "foo"
                    ),
                    "foo_bar"
                ),
                ForMacro(
                    PackageIdentifier.createInMainRepo("test_pkg"),
                    ForMacro(
                        PackageIdentifier.createInMainRepo("test_pkg"),
                        ForBuildFile(
                            PackageIdentifier.createInMainRepo("test_pkg")
                        ),
                        "foo"
                    ),
                    "foo_bar"
                )
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packagePieceForBuildFileBuilder_basicFunctionality() {
        val builder: PackagePiece.ForBuildFile.Builder = minimalBuildFilePieceBuilder("test_pkg")
        addRule(builder, Label.parseCanonical("//test_pkg:foo"), FAUX_TEST_CLASS)
        val macroClass: MacroClass = failMacroClass("my_macro") // would fail if expanded
        addMacro(builder, macroClass, "bar")
        val buildFilePiece: PackagePiece.ForBuildFile = builder.buildPartial().finishBuild()
        assertThat(buildFilePiece.getPackageIdentifier())
            .isEqualTo(PackageIdentifier.createInMainRepo("test_pkg"))
        assertThat(buildFilePiece.getMetadata().buildFileLabel())
            .isEqualTo(Label.parseCanonical("//test_pkg:BUILD"))
        assertThat(buildFilePiece.getBuildFile().getLabel())
            .isEqualTo(Label.parseCanonical("//test_pkg:BUILD"))
        assertThat(buildFilePiece.getTargets()).hasSize(2) // BUILD file + foo
        assertThat(buildFilePiece.getTargets(Rule::class.java)).hasSize(1)
        val foo: Target = buildFilePiece.getTarget("foo")
        assertThat(foo).isNotNull()
        assertThat(foo.getLabel()).isEqualTo(Label.parseCanonical("//test_pkg:foo"))
        assertThat(foo.getRuleClass()).isEqualTo(FAUX_TEST_CLASS.getName())
        assertThat(foo.getPackageoid()).isSameInstanceAs(buildFilePiece)
        assertThat(foo.getDeclaringMacro()).isNull()
        assertThat(foo.getDeclaringPackage()).isEqualTo(PackageIdentifier.createInMainRepo("test_pkg"))
        val bar: MacroInstance = buildFilePiece.getMacroByName("bar")
        assertThat(bar).isNotNull()
        assertThat(bar.getName()).isEqualTo("bar")
        assertThat(bar.getMacroClass()).isSameInstanceAs(macroClass)
        assertThat(bar.getPackageMetadata()).isSameInstanceAs(buildFilePiece.getMetadata())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun packagePieceForMacroBuilder_basicFunctionality() {
        val fooMacroClass: MacroClass = noopMacroClass("foo_macro")
        val barMacroClass: MacroClass = noopMacroClass("bar_macro")
        val buildFilePieceBuilder: PackagePiece.ForBuildFile.Builder =
            minimalBuildFilePieceBuilder("test_pkg")
        val fooMacro: MacroInstance? = addMacro(buildFilePieceBuilder, fooMacroClass, "foo")
        val buildFilePiece: PackagePiece.ForBuildFile = buildFilePieceBuilder.buildPartial().finishBuild()
        val fooMacroPieceBuilder: PackagePiece.ForMacro.Builder =
            minimalMacroPieceBuilder(fooMacro, buildFilePiece.getIdentifier(), buildFilePiece)
        // Normally, the macro frame would be set by MacroClass#executeMacroImplementation
        var unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fooMacroPieceBuilder.setCurrentMacroFrame(MacroFrame(fooMacro))
        addRule(fooMacroPieceBuilder, Label.parseCanonical("//test_pkg:foo_test"), FAUX_TEST_CLASS)
        val fooBarMacro: MacroInstance? = addMacro(fooMacroPieceBuilder, barMacroClass, "foo_bar")
        val fooMacroPiece: PackagePiece.ForMacro = fooMacroPieceBuilder.buildPartial().finishBuild()
        val fooBarMacroPieceBuilder: PackagePiece.ForMacro.Builder =
            minimalMacroPieceBuilder(fooBarMacro, fooMacroPiece.getIdentifier(), buildFilePiece)
        unused = fooBarMacroPieceBuilder.setCurrentMacroFrame(MacroFrame(fooBarMacro))
        addRule(
            fooBarMacroPieceBuilder, Label.parseCanonical("//test_pkg:foo_bar_test"), FAUX_TEST_CLASS
        )
        val fooBarMacroPiece: PackagePiece.ForMacro = fooBarMacroPieceBuilder.buildPartial().finishBuild()

        assertThat(fooMacroPiece.getEvaluatedMacro()).isSameInstanceAs(fooMacro)
        assertThat(fooBarMacroPiece.getEvaluatedMacro()).isSameInstanceAs(fooBarMacro)

        assertThat(fooMacroPiece.getMetadata()).isSameInstanceAs(buildFilePiece.getMetadata())
        assertThat(fooMacroPiece.getDeclarations()).isSameInstanceAs(buildFilePiece.getDeclarations())
        assertThat(fooBarMacroPiece.getMetadata()).isSameInstanceAs(buildFilePiece.getMetadata())
        assertThat(fooBarMacroPiece.getDeclarations())
            .isSameInstanceAs(buildFilePiece.getDeclarations())

        assertThat(fooMacroPiece.getTargets()).hasSize(1)
        assertThat(fooMacroPiece.getTargets(Rule::class.java)).hasSize(1)
        val fooTest: Target = fooMacroPiece.getTarget("foo_test")
        assertThat(fooTest).isNotNull()
        assertThat(fooTest.getLabel()).isEqualTo(Label.parseCanonical("//test_pkg:foo_test"))
        assertThat(fooTest.getRuleClass()).isEqualTo(FAUX_TEST_CLASS.getName())
        assertThat(fooTest.getPackageoid()).isSameInstanceAs(fooMacroPiece)
        assertThat(fooTest.getDeclaringMacro()).isSameInstanceAs(fooMacro)
        assertThat(fooTest.getDeclaringPackage()).isEqualTo(FAKE_BZL_LABEL.getPackageIdentifier())

        assertThat(fooMacroPiece.getMacroByName("foo_bar")).isSameInstanceAs(fooBarMacro)

        assertThat(fooBarMacroPiece.getTargets()).hasSize(1)
        assertThat(fooBarMacroPiece.getTargets(Rule::class.java)).hasSize(1)
        val fooBarTest: Target = fooBarMacroPiece.getTarget("foo_bar_test")
        assertThat(fooBarTest).isNotNull()
        assertThat(fooBarTest.getLabel()).isEqualTo(Label.parseCanonical("//test_pkg:foo_bar_test"))
        assertThat(fooBarTest.getRuleClass()).isEqualTo(FAUX_TEST_CLASS.getName())
        assertThat(fooBarTest.getPackageoid()).isSameInstanceAs(fooBarMacroPiece)
        assertThat(fooBarTest.getDeclaringMacro()).isSameInstanceAs(fooBarMacro)
        assertThat(fooBarTest.getDeclaringPackage()).isEqualTo(FAKE_BZL_LABEL.getPackageIdentifier())
    }

    private fun minimalBuildFilePieceBuilder(name: String?): PackagePiece.ForBuildFile.Builder {
        val pkgId: PackageIdentifier? = PackageIdentifier.createInMainRepo(name)
        return PackagePiece.ForBuildFile.newBuilder(
            PackageSettings.DEFAULTS,
            ForBuildFile(pkgId),  /* filename= */
            RootedPath.toRootedPath(
                Root.fromPath(fileSystem.getPath("/irrelevantRoot")),
                PathFragment.create(name + "/BUILD")
            ),
            "workspace",  /* associatedModuleName= */
            java.util.Optional.empty<T?>(),  /* associatedModuleVersion= */
            java.util.Optional.empty<T?>(),  /* noImplicitFileExport= */
            true,  /* simplifyUnconditionalSelectsInRuleAttrs= */
            StarlarkSemantics.DEFAULT.getBool(
                BuildLanguageOptions.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),  /* repositoryMapping= */
            RepositoryMapping.EMPTY,  /* mainRepositoryMapping= */
            null,  /* cpuBoundSemaphore= */
            null,
            PackageOverheadEstimator.NOOP_ESTIMATOR,  /* generatorMap= */
            null,  /* configSettingVisibilityPolicy= */
            null,  /* globber= */
            null,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            false,
            java.lang.Package.Builder.PackageLimits.DEFAULTS
        )
            .setLoads(com.google.common.collect.ImmutableList.of<E?>())
    }

    private fun minimalMacroPieceBuilder(
        macro: MacroInstance?,
        parentIdentifier: PackagePieceIdentifier?,
        pieceForBuildFile: PackagePiece.ForBuildFile
    ): PackagePiece.ForMacro.Builder {
        return PackagePiece.ForMacro.newBuilder(
            pieceForBuildFile.getMetadata(),
            pieceForBuildFile.getDeclarations(),
            macro,
            parentIdentifier,  /* simplifyUnconditionalSelectsInRuleAttrs= */
            StarlarkSemantics.DEFAULT.getBool(
                BuildLanguageOptions.INCOMPATIBLE_SIMPLIFY_UNCONDITIONAL_SELECTS_IN_RULE_ATTRS
            ),  /* mainRepositoryMapping= */
            null,  /* cpuBoundSemaphore= */
            null,
            PackageOverheadEstimator.NOOP_ESTIMATOR,  /* enableNameConflictChecking= */
            true,  /* trackFullMacroInformation= */
            false,
            java.lang.Package.Builder.PackageLimits.DEFAULTS,  /* existingRulesMapForFinalizer= */
            null
        )
    }

    private fun noopMacroClass(name: String?): MacroClass {
        return Builder(noopMacroImplementation)
            .setName(name)
            .setDefiningBzlLabel(FAKE_BZL_LABEL)
            .build()
    }

    private fun failMacroClass(name: String?): MacroClass {
        return Builder(failMacroImplementation)
            .setName(name)
            .setDefiningBzlLabel(FAKE_BZL_LABEL)
            .build()
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun addMacro(
        targetDefinitionContext: TargetDefinitionContext,
        macroClass: MacroClass,
        macroInstanceName: String
    ): MacroInstance? {
        val macro: MacroInstance? =
            targetDefinitionContext.createMacro(
                macroClass, macroInstanceName,  /* sameNameDepth= */1, com.google.common.collect.ImmutableList.of<E?>()
            )
        macroClass
            .getAttributeProvider()
            .populateRuleAttributeValues(
                macro,
                targetDefinitionContext,
                BuildLangTypedAttributeValuesMap(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        "name",
                        macroInstanceName,
                        "visibility",
                        Starlark.NONE
                    )
                ),  /* failOnUnknownAttributes= */
                true,  /* isStarlark= */
                true
            )
        targetDefinitionContext.addMacro(macro)
        return macro
    }

    companion object {
        private val FAUX_TEST_CLASS: RuleClass = Builder("faux_test", RuleClassType.TEST,  /* starlark= */false)
            .addAttribute(
                Attribute.attr("tags", Types.STRING_LIST).nonconfigurable("tags aren't").build()
            )
            .addAttribute(Attribute.attr("size", Type.STRING).nonconfigurable("size isn't").build())
            .addAttribute(Attribute.attr("timeout", Type.STRING).build())
            .addAttribute(Attribute.attr("flaky", Type.BOOLEAN).build())
            .addAttribute(Attribute.attr("shard_count", Type.INTEGER).build())
            .addAttribute(Attribute.attr("local", Type.BOOLEAN).build())
            .setConfiguredTargetFunction(< T > mock < T ? > (StarlarkCallable::class.java))
        .build()

        private val FAKE_BZL_LABEL: Label = Label.parseCanonicalUnchecked("//fake:fake.bzl")

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.Exception::class)
        private fun addRule(
            targetDefinitionContext: TargetDefinitionContext, label: Label?, ruleClass: RuleClass?
        ): Rule {
            val rule: Rule =
                targetDefinitionContext.createRule(
                    label, ruleClass,  /* threadCallStack= */com.google.common.collect.ImmutableList.of<E?>()
                )
            rule.populateOutputFiles(
                StoredEventHandler(), targetDefinitionContext.getPackageIdentifier()
            )
            targetDefinitionContext.addRule(rule)
            return rule
        }
    }
}
