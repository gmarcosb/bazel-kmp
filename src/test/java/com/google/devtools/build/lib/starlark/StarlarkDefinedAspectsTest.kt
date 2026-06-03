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
package com.google.devtools.build.lib.starlark

import com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX

/** Tests for Starlark aspects  */
@RunWith(JUnit4::class)
open class StarlarkDefinedAspectsTest : AnalysisTestCase() {
    protected open fun keepGoing(): Boolean {
        return false
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun simpleAspect() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCanBeDefinedUsingFactory() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(target, ctx):
            return []

        def aspect_factory():
            return aspect(implementation=_impl)

        my_aspect = aspect_factory()
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = "abc")
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("pkg/foo.bzl%my_aspect"), "//pkg:abc")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//pkg:abc")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//pkg:foo.bzl%my_aspect(//pkg:abc)")
    }

    // Regression test for b/409532322
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCannotBeDefinedInBuildFileThread() {
        scratch.file(
            "pkg/foo.bzl",
            """
        def _impl(target, ctx):
            return []

        def aspect_factory():
            return aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "pkg/BUILD",
            """
        load(":foo.bzl", "aspect_factory")
        my_aspect = aspect_factory()
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//pkg:BUILD") })
        assertContainsEvent("aspect() can only be used during .bzl initialization")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithSingleDeclaredProvider() {
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()
        def _impl(target, ctx):
           return foo()
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")

        assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithDeclaredProviders() {
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()
        bar = provider()
        def _impl(target, ctx):
           return [foo(), bar()]
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")
        val barKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "bar")

        assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey)
        assertThat(configuredAspect.get(barKey).getProvider().getKey()).isEqualTo(barKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithDeclaredProvidersInAStruct() {
        scratch.file(
            "test/aspect.bzl",
            """
        foo = provider()
        bar = provider()
        def _impl(target, ctx):
           return [foo(), bar()]
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)")

        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())

        val fooKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "foo")
        val barKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "bar")

        assertThat(configuredAspect.get(fooKey).getProvider().getKey()).isEqualTo(fooKey)
        assertThat(configuredAspect.get(barKey).getProvider().getKey()).isEqualTo(barKey)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCommandLineLabel() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("//test:aspect.bzl%MyAspect(//test:xxx)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCommandLineRepoLabel() {
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name='local')",
            "local_path_override(module_name='local', path='local/repo')"
        )
        scratch.file("local/repo/MODULE.bazel", "module(name='local')")
        scratch.file(
            "local/repo/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file("local/repo/BUILD")

        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("@local//:aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        Truth.assertThat(getAspectDescriptions(analysisResult))
            .containsExactly("@@local+//:aspect.bzl%MyAspect(//test:xxx)")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAllowsFragmentsToBeSpecified() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl, fragments=['java'])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")

        val key: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().keySet())
        val aspectValue: AspectValue = skyframeExecutor.getEvaluator().getExistingValue(key) as AspectValue
        val aspectDefinition: AspectDefinition = aspectValue.getAspect().getDefinition()
        assertThat(
            aspectDefinition
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(JavaConfiguration::class.java)
        )
            .isTrue()
        assertThat(
            aspectDefinition
                .getConfigurationFragmentPolicy()
                .isLegalConfigurationFragment(CppConfiguration::class.java)
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagating() {
        scratch.file(
            "test/aspect.bzl",
            """
Info = provider()
def _impl(target, ctx):
   s = depset([target.label], transitive = [i[Info].target_labels for i in ctx.rule.attr.deps])
   c = depset([ctx.rule.kind], transitive = [i[Info].rule_kinds for i in ctx.rule.attr.deps])
   return Info(target_labels = s, rule_kinds = c)

MyAspect = aspect(
   implementation=_impl,
   attr_aspects=['deps'],
)

""".trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = [':yyy'],
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        val configuredAspect: ConfiguredAspect = analysisResult.getAspectsMap().values().iterator().next()
        assertThat(configuredAspect).isNotNull()
        val info: StarlarkInfo = getStarlarkProvider(configuredAspect, "//test:aspect.bzl", "Info")
        val names: NestedSet<Label?> =
            Depset.cast(info.getValue("target_labels"), Label::class.java, "target_labels")
        assertThat(names.toList().stream().map(Label::toString))
            .containsExactly("//test:xxx", "//test:yyy")
        val ruleKinds: Any =
            getStarlarkProvider(configuredAspect, "//test:aspect.bzl", "Info").getValue("rule_kinds")
        Truth.assertThat(ruleKinds).isInstanceOf(Depset::class.java)
        assertThat((ruleKinds as Depset).toList()).containsExactly("java_library")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsPropagatingForDefaultAndImplicit() {
        useConfiguration(
            "--experimental_builtins_injection_override=+cc_library",
            "--incompatible_enable_cc_toolchain_resolution"
        )
        scratch.file(
            "test/aspect.bzl",
            """
        Info = provider()
        def _impl(target, ctx):
           s = []
           c = []
           a = ctx.rule.attr
           if getattr(a, '_defaultattr', None):
               s += [a._defaultattr[Info].target_labels]
               c += [a._defaultattr[Info].rule_kinds]
           if getattr(a, '_cc_toolchain', None):
               s += [a._cc_toolchain[Info].target_labels]
               c += [a._cc_toolchain[Info].rule_kinds]
           return Info(
               target_labels = depset([target.label], transitive = s),
               rule_kinds = depset([ctx.rule.kind], transitive = c))

        def _rule_impl(ctx):
           pass

        my_rule = rule(implementation = _rule_impl,
           attrs = { '_defaultattr' : attr.label(default = Label('//test:xxx')) },
        )
        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['_defaultattr', '_cc_toolchain'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load('//test:aspect.bzl', 'my_rule')
        cc_library(
             name = 'xxx',
        )
        my_rule(
             name = 'yyy',
        )
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:yyy")
        val configuredAspect: ConfiguredAspect = analysisResult.getAspectsMap().values().iterator().next()
        assertThat(configuredAspect).isNotNull()
        val infoKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "Info")
        val nameSet: Any = (configuredAspect.get(infoKey) as StarlarkInfo).getValue("target_labels")
        val names: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.copyOf<E?>(
                com.google.common.collect.Iterables.transform<F?, T?>(
                    (nameSet as Depset).toList(),
                    com.google.common.base.Function { o: F? ->
                        assertThat(o).isInstanceOf(Label::class.java)
                        (o as Label).name
                    })
            )

        Truth.assertThat(names).containsAtLeast("xxx", "yyy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsDirOnMergedTargets() {
        scratch.file(
            "test/aspect.bzl",
            """
        Info = provider()
        def _impl(target, ctx):
           return Info(aspect_provider = 'data')

        p = provider()
        MyAspect = aspect(implementation=_impl)
        def _rule_impl(ctx):
           if ctx.attr.dep:
              return [p(dir = dir(ctx.attr.dep))]
           return [p()]

        my_rule = rule(implementation = _rule_impl,
           attrs = { 'dep' : attr.label(aspects = [MyAspect]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx',)
        my_rule(name = 'yyy', dep = ':xxx')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:yyy")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())

        val names: StructImpl = getStarlarkProvider(target, "p")
        Truth.assertThat(names.getValue("dir") as Iterable<*>?)
            .containsExactly(
                "actions",
                "data_runfiles",
                "default_runfiles",
                "files",
                "files_to_run",
                "label",
                "output_groups"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithOutputGroupsDeclaredProvider() {
        scratch.file(
            "test/aspect.bzl",
            "def _impl(target, ctx):",
            "   f = target[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
            "   return [OutputGroupInfo(my_result = f)]",
            "",
            "MyAspect = aspect(",
            "   implementation=_impl,",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        val configuredAspect: ConfiguredAspect? = analysisResult.getAspectsMap().values().iterator().next()
        val outputGroupInfo: OutputGroupInfo = OutputGroupInfo.get(configuredAspect)

        assertThat(outputGroupInfo).isNotNull()
        val names: NestedSet<Artifact?> = outputGroupInfo.getOutputGroup("my_result")
        assertThat(names.toList()).isNotEmpty()

        // Configuration of the true Artifact may diverge slightly (e.g. be trimmed) causing owners to
        // also diverge so just compare paths instead of the whole Artifact.
        val paths: com.google.common.collect.ImmutableList<Path?>? =
            names.toList().stream().map(Artifact::getPath)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        val expectedPaths: com.google.common.collect.ImmutableList<Path?>? =
            OutputGroupInfo.get(getConfiguredTarget("//test:xxx"))
                .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL)
                .toList()
                .stream()
                .map(Artifact::getPath)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(paths).containsExactlyElementsIn(expectedPaths)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectWithOutputGroupsAsListDeclaredProvider() {
        scratch.file(
            "test/aspect.bzl",
            "def _impl(target, ctx):",
            "   g = target[OutputGroupInfo]._hidden_top_level" + INTERNAL_SUFFIX,
            "   return [OutputGroupInfo(my_result=g.to_list())]",
            "",
            "MyAspect = aspect(",
            "   implementation=_impl,",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                analysisResult.getTargetsToBuild(),
                com.google.common.base.Function { configuredTarget: F? -> configuredTarget.getLabel().toString() })
        )
            .containsExactly("//test:xxx")
        val configuredAspect: ConfiguredAspect? = analysisResult.getAspectsMap().values().iterator().next()
        val outputGroupInfo: OutputGroupInfo = OutputGroupInfo.get(configuredAspect)
        assertThat(outputGroupInfo).isNotNull()
        val names: NestedSet<Artifact?> = outputGroupInfo.getOutputGroup("my_result")
        assertThat(names.toList()).isNotEmpty()

        // Configuration of the true Artifact may diverge slightly (e.g. be trimmed) causing owners to
        // also diverge so just compare paths instead of the whole Artifact.
        val paths: com.google.common.collect.ImmutableList<Path?>? =
            names.toList().stream().map(Artifact::getPath)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        val expectedPaths: com.google.common.collect.ImmutableList<Path?>? =
            OutputGroupInfo.get(getConfiguredTarget("//test:xxx"))
                .getOutputGroup(OutputGroupInfo.HIDDEN_TOP_LEVEL)
                .toList()
                .stream()
                .map(Artifact::getPath)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(paths).containsExactlyElementsIn(expectedPaths)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsFromStarlarkRules() {
        scratch.file(
            "test/aspect.bzl",
            """
AspectInfo = provider()
def _aspect_impl(target, ctx):
   s = depset([target.label], transitive =
       [i[AspectInfo].target_labels for i in ctx.rule.attr.deps])
   return AspectInfo(target_labels = s)

TargetInfo = provider()
def _rule_impl(ctx):
   s = depset(transitive = [i[AspectInfo].target_labels for i in ctx.attr.attr])
   return TargetInfo(rule_deps = s)

MyAspect = aspect(
   implementation=_aspect_impl,
   attr_aspects=['deps'],
)
my_rule = rule(
   implementation=_rule_impl,
   attrs = { 'attr' :
             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]) },
)

""".trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:xxx")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val names: NestedSet<Label?> =
            Depset.cast(
                getStarlarkProvider(target, "TargetInfo").getValue("rule_deps", Depset::class.java),
                Label::class.java,
                "rule_deps"
            )
        assertThat(names.toList().stream().map(Label::toString)).containsExactly("//test:yyy")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsNonExported() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
           return []

        def _rule_impl(ctx):
           pass

        def mk_aspect():
           return aspect(implementation=_aspect_impl)
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' : attr.label_list(aspects = [mk_aspect()]) },  # line 11
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val analysisResult: AnalysisResult = update("//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(analysisResult.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expected
        } catch (e: TargetParsingException) {
        }

        // attr.label_list() fails, stack=[<toplevel>@rules.bzl:11:38, label_list:<builtin>]
        assertContainsEvent("File \"/workspace/test/aspect.bzl\", line 11, column 38, in <toplevel>")
        assertContainsEvent(
            "Error in label_list: Aspects should be top-level values in extension files that define"
                    + " them."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectReturnsNonExportedProvider() {
        scratch.file(
            "test/inc.bzl",
            """
        a = aspect(implementation = lambda target, ctx: [provider()()])
        r = rule(
          implementation = lambda ctx: [],
          attrs = {'a': attr.label_list(aspects = [a])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:inc.bzl', 'r')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name = 'j')
        r(name = 'test', a = [':j'])
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            update("//test")
            /* reached if --keep_going=true */
        } catch (unused: ViewCreationFailedException) {
            /* reached if --keep_going=false */
        }
        assertContainsEvent(
            "aspect function returned an instance of a provider "
                    + "(defined at /workspace/test/inc.bzl:1:58) that is not a global"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun providerNonExported() {
        scratch.file(
            "test/rule.bzl",
            """
        def mk_provider():
           return provider()
        def _rule_impl(ctx):
           pass
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' : attr.label_list(providers = [mk_provider()]) },  # line 7
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:rule.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = [':yyy'],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val analysisResult: AnalysisResult = update("//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(analysisResult.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expected
        } catch (e: TargetParsingException) {
        }

        // attr.label_list() fails, stack=[<toplevel>@rules.bzl:7:38, label_list:<builtin>]
        assertContainsEvent("File \"/workspace/test/rule.bzl\", line 7, column 38, in <toplevel>")
        assertContainsEvent(
            "Error in label_list: Providers should be top-level values in extension files that define"
                    + " them."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnLabelAttr() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           return AspectInfo(aspect_data='foo')

        RuleInfo = provider()
        def _rule_impl(ctx):
           return RuleInfo(data=ctx.attr.attr[AspectInfo].aspect_data)

        MyAspect = aspect(
           implementation=_aspect_impl,
        )
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' :
                     attr.label(aspects = [MyAspect]) },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = ':yyy',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val value: String? = getStarlarkProvider(target, "RuleInfo").getValue("data", String::class.java)
        Truth.assertThat(value).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelKeyedStringDictAllowsAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           return AspectInfo(aspect_data=target.label.name)

        RuleInfo = provider()
        def _rule_impl(ctx):
           return RuleInfo(
               data=','.join(['{}:{}'.format(dep[AspectInfo].aspect_data, val)
                              for dep, val in ctx.attr.attr.items()]))

        MyAspect = aspect(
           implementation=_aspect_impl,
        )
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' :
                     attr.label_keyed_string_dict(aspects = [MyAspect]) },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'yyy',
        )
        my_rule(
             name = 'xxx',
             attr = {':yyy': 'zzz'},
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val value: String? = getStarlarkProvider(target, "RuleInfo").getValue("data", String::class.java)
        Truth.assertThat(value).isEqualTo("yyy:zzz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun labelListDictAllowsAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           return AspectInfo(aspect_data=target.label.name)

        RuleInfo = provider()
        def _rule_impl(ctx):
           return RuleInfo(
               data=','.join(['{}:{}'.format(dep[AspectInfo].aspect_data, val)
                              for val, deps in ctx.attr.attr.items() for dep in deps]))

        MyAspect = aspect(
           implementation=_aspect_impl,
        )
        my_rule = rule(
           implementation=_rule_impl,
           attrs = { 'attr' : attr.label_list_dict(aspects = [MyAspect]) },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
             name = 'yyy1',
        )
        cc_library(
             name = 'yyy2',
        )
        my_rule(
             name = 'xxx',
             attr = {'zzz': [':yyy1', ':yyy2']},
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val value: String? = getStarlarkProvider(target, "RuleInfo").getValue("data", String::class.java)
        Truth.assertThat(value).isEqualTo("yyy1:zzz,yyy2:zzz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsDoNotAttachToFiles() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []

        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['deps'],
        )
        
        """.trimIndent()
        )
        scratch.file("test/zzz.jar")
        scratch.file(
            "test/BUILD",
            """
        exports_files(['zzz.jar'])
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = ['//test:zzz.jar'],
        )
        
        """.trimIndent()
        )

        val result: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsDoNotAttachToTopLevelFiles() {
        scratch.file(
            "test/aspect.bzl",
            """
        p = provider()
        def _impl(target, ctx):
           return [p()]

        MyAspect = aspect(
           implementation=_impl,
           attr_aspects=['deps'],
        )
        
        """.trimIndent()
        )
        scratch.file("test/zzz.jar")
        scratch.file(
            "test/BUILD",
            """
        exports_files(['zzz.jar'])
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
             name = 'xxx',
             srcs = ['A.java'],
             deps = ['//test:zzz.jar'],
        )
        
        """.trimIndent()
        )

        val result: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:zzz.jar")
        assertThat(result.hasError()).isFalse()
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(result.getAspectsMap().values())
                .getProviders()
                .getProviderCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectFailingExecution() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return 1 // 0

        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        // Stack doesn't include source lines because we haven't told EvalException
        // how to read from scratch.
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:2:13: in "
                    + "//test:aspect.bzl%MyAspect aspect on java_library rule //test:xxx: \n"
                    + "Traceback (most recent call last):\n"
                    + "\tFile \"/workspace/test/aspect.bzl\", line 2, column 13, in _impl\n"
                    + "\t\treturn 1 // 0\n"
                    + "Error: integer division by zero")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectFailingReturnsNotAStruct() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return 0

        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent(
            "Aspect implementation should return a list, or a provider instance, but got int"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectFailingOrphanArtifacts() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
          ctx.actions.declare_file('missing_in_action.txt')
          return []

        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:2:13: in "
                    + "//test:aspect.bzl%MyAspect aspect on java_library rule //test:xxx: \n"
                    + "The following files have no generating action:\n"
                    + "test/missing_in_action.txt")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectSkippingOrphanArtifactsWithLocation() {
        scratch.file(
            "simple/print.bzl",
            """
        Info = provider()
        def _print_expanded_location_impl(target, ctx):
            return Info(result=ctx.expand_location(ctx.rule.attr.cmd, []))

        print_expanded_location = aspect(
            implementation = _print_expanded_location_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "simple/BUILD",
            """
        filegroup(
            name = "files",
            srcs = ["afile"],
        )

        genrule(
            name = "concat_all_files",
            srcs = [":files"],
            outs = ["concatenated.txt"],
            cmd = "${'$'}(location :files)"
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//simple:print.bzl%print_expanded_location"),
                "//simple:concat_all_files"
            )
        assertThat(analysisResult.hasError()).isFalse()
        val configuredAspect: ConfiguredAspect = analysisResult.getAspectsMap().values().iterator().next()
        val result: String? =
            getStarlarkProvider(configuredAspect, "//simple:print.bzl", "Info")
                .getValue("result", String::class.java)

        Truth.assertThat(result).isEqualTo("simple/afile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun expandLocationFailsForTargetsWithSameLabel() {
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = 'function_transition_allowlist',
            packages = [
                '//a/...',
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/defs.bzl",
            "def _transition_impl(settings, attr):",
            "    return {",
            "        'opt': {'//command_line_option:compilation_mode': 'opt'},",
            "        'dbg': {'//command_line_option:compilation_mode': 'dbg'},",
            "    }",
            "split_transition = transition(",
            "    implementation = _transition_impl,",
            "    inputs = [],",
            "    outputs = ['//command_line_option:compilation_mode'])",
            "def _split_deps_rule_impl(ctx):",
            "    pass",
            "split_deps_rule = rule(",
            "    implementation = _split_deps_rule_impl,",
            "    attrs = {",
            "        'my_dep': attr.label(cfg = split_transition),",
            "    })",
            "",
            "Info = provider()",
            "def _print_expanded_location_impl(target, ctx):",
            "    return Info(result=ctx.expand_location('$(location //a:lib)',"
                    + " [ctx.rule.attr.my_dep[0], ctx.rule.attr.my_dep[1]]))",
            "",
            "print_expanded_location = aspect(",
            "    implementation = _print_expanded_location_impl,",
            ")"
        )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load('//a:defs.bzl', 'split_deps_rule')
        cc_library(name = 'lib', srcs = ['lib.cc'])
        split_deps_rule(
            name = 'a',
            my_dep = ':lib')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)

        try {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//a:defs.bzl%print_expanded_location"),
                    "//a"
                )
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(analysisResult.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("Label \"//a:lib\" is found more than once in 'targets' list.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectIsNotAnAspect() {
        scratch.file("test/aspect.bzl", "MyAspect = 4")
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx')"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("MyAspect from //test:aspect.bzl is not an aspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateOutputGroups() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
          a = ctx.actions.declare_file('aspect.txt')
          ctx.actions.write(a, 'f')
          return [OutputGroupInfo(duplicate = depset([a]))]

        MyAspect = aspect(implementation=_aspect_impl)
        def _rule_impl(ctx):
          r = ctx.actions.declare_file('rule.txt')
          ctx.actions.write(r, 'r')
          return [OutputGroupInfo(duplicate = depset([r]))]
        my_rule = rule(_rule_impl)
        def _rbase_impl(ctx):
          return [DefaultInfo(files=ctx.attr.dep[OutputGroupInfo].duplicate)]
        rbase = rule(_rbase_impl, attrs = { 'dep' : attr.label(aspects = [MyAspect]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule', 'rbase')
        my_rule(name = 'xxx')
        rbase(name = 'yyy', dep = ':xxx')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:yyy")
        val filesToBuild: NestedSet<Artifact?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
                .getProvider(FileProvider::class.java)
                .getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aspect.txt", "rule.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mergesFileProvider() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _asp_impl(target, ctx):
          f = ctx.actions.declare_file('aspectfile')
          ctx.actions.write(f, 'f')
          return [DefaultInfo(files=depset([f]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files.dep))]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:a")
        val filesToBuild: NestedSet<Artifact?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
                .getProvider(FileProvider::class.java)
                .getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("sourcefile", "aspectfile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDefaultInfoWithNonArtifacts() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _asp_impl(target, ctx):
          return [DefaultInfo(files=depset([1]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo()]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            update("//test:a")
        } catch (e: ViewCreationFailedException) {
            // expected.
        }
        assertContainsEvent("should contain a depset of files")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDefaultInfoSomethingElseThanFiles() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _asp_impl(target, ctx):
          return [DefaultInfo(runfiles=ctx.runfiles([]))]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo()]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            update("//test:a")
        } catch (e: ViewCreationFailedException) {
            // expected.
        }
        assertContainsEvent("must only have the 'files' field set")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectEmptyDefaultInfo() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _asp_impl(target, ctx):
          return [DefaultInfo()]

        asp = aspect(implementation=_asp_impl)
        def _rule_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files.dep))]
        my_rule = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [asp]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule')
        my_rule(name='a', dep=':b')
        filegroup(name='b', srcs=['sourcefile'])
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:a")
        val filesToBuild: NestedSet<Artifact?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
                .getProvider(FileProvider::class.java)
                .getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild)).containsExactly("sourcefile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputGroupsFromOneAspect() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = {k:ctx.attr.dep.output_groups[k] for k in ctx.attr.dep[OutputGroupInfo]}
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule1')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val outputGroupInfo: OutputGroupInfo =
            OutputGroupInfo.get(com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild()))
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
            .containsExactly("test/base_a1.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun returningOutputGroupsNotList() {
        // OutputGroupInfo is also a list, tests that an aspect can return it without a list
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return OutputGroupInfo(a1_group = depset([f]))

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = {k:ctx.attr.dep.output_groups[k] for k in ctx.attr.dep[OutputGroupInfo]}
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule1')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val outputGroupInfo: OutputGroupInfo =
            OutputGroupInfo.get(com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild()))
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
            .containsExactly("test/base_a1.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputGroupsDeclaredProviderFromOneAspect() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          return [OutputGroupInfo(a1_group = ctx.attr.dep[OutputGroupInfo].a1_group)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule1')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:xxx")
        val outputGroupInfo: OutputGroupInfo =
            OutputGroupInfo.get(com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild()))
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
            .containsExactly("test/base_a1.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputGroupsFromTwoAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = {k:ctx.attr.dep.output_groups[k] for k in ctx.attr.dep.output_groups}
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        def _a2_impl(target, ctx):
          g = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(g, 'f')
          return [OutputGroupInfo(a2_group = depset([g]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])
        my_rule2 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a2]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule1', 'my_rule2')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        my_rule2(name = 'yyy', dep = ':xxx')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:yyy")
        val outputGroupInfo: OutputGroupInfo =
            OutputGroupInfo.get(com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild()))
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
            .containsExactly("test/base_a1.txt")
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a2_group"))
            .containsExactly("test/xxx_a2.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun outputGroupsDeclaredProvidersFromTwoAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          f = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(f, 'f')
          return [OutputGroupInfo(a1_group = depset([f]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])
        def _rule_impl(ctx):
          if not ctx.attr.dep:
             return []
          og = dict()
          dep_og = ctx.attr.dep[OutputGroupInfo]
          if hasattr(dep_og, 'a1_group'):
             og['a1_group'] = dep_og.a1_group
          if hasattr(dep_og, 'a2_group'):
             og['a2_group'] = dep_og.a2_group
          return [OutputGroupInfo(**og)]
        my_rule1 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a1]) })
        def _a2_impl(target, ctx):
          g = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(g, 'f')
          return [OutputGroupInfo(a2_group = depset([g]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])
        my_rule2 = rule(_rule_impl, attrs = { 'dep' : attr.label(aspects = [a2]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule1', 'my_rule2')
        my_rule1(name = 'base')
        my_rule1(name = 'xxx', dep = ':base')
        my_rule2(name = 'yyy', dep = ':xxx')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:yyy")
        val outputGroupInfo: OutputGroupInfo =
            OutputGroupInfo.get(com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild()))
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a1_group"))
            .containsExactly("test/base_a1.txt")
        Truth.assertThat(getOutputGroupContents(outputGroupInfo, "a2_group"))
            .containsExactly("test/xxx_a2.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateOutputGroupsFromTwoAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _a1_impl(target, ctx):
          a1 = ctx.actions.declare_file(target.label.name + '_a1.txt')
          ctx.actions.write(a1, 'f')
          return [OutputGroupInfo(aspect_group = depset([a1]))]

        a1 = aspect(implementation=_a1_impl, attr_aspects = ['dep'])

        def _a2_impl(target, ctx):
          a2 = ctx.actions.declare_file(target.label.name + '_a2.txt')
          ctx.actions.write(a2, 'f')
          return [OutputGroupInfo(aspect_group = depset([a2]))]

        a2 = aspect(implementation=_a2_impl, attr_aspects = ['dep'])

        def _base_impl(ctx):
          return []

        base = rule(_base_impl, attrs = {})

        def _top_impl(ctx):
          return [DefaultInfo(files=ctx.attr.dep[OutputGroupInfo].aspect_group)]

        top = rule(_top_impl, attrs = { 'dep' : attr.label(aspects = [a1, a2]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'base', 'top')
        base(name = 'base')
        top(name = 'top', dep = ':base')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:top")
        val filesToBuild: NestedSet<Artifact?>? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
                .getProvider(FileProvider::class.java)
                .getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("base_a1.txt", "base_a2.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun duplicateStarlarkProviders() {
        scratch.file(
            "test/aspect.bzl",
            """
        Info = provider()
        def _impl(target, ctx):
          return Info(duplicate = 'x')

        MyAspect = aspect(implementation=_impl)
        def _rule_impl(ctx):
          return Info(duplicate = 'y')
        my_rule = rule(_rule_impl)
        def _noop(ctx):
          pass
        rbase = rule(_noop, attrs = { 'dep' : attr.label(aspects = [MyAspect]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'my_rule', 'rbase')
        my_rule(name = 'xxx')
        rbase(name = 'yyy', dep = ':xxx')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update("//test:yyy")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("ERROR /workspace/test/BUILD:3:6: Provider Info provided twice")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectDoesNotExist() {
        scratch.file("test/aspect.bzl", "")
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx')"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("MyAspect is not exported from //test:aspect.bzl")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectDoesNotExist2() {
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx')"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("cannot load '//test:aspect.bzl': no such file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectDoesNotExistNoBuildFile() {
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx')"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("foo/aspect.bzl%MyAspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
        }
        assertContainsEvent("Every .bzl file must have a corresponding package")
    }

    /**
     * Tests that a loading-level error (missing bzl file) is properly transformed by the configured
     * target that requested the relevant package, and doesn't bubble up to a higher configured
     * target/aspect that wasn't expecting a loading-level error. The complication is that the
     * configured target that depends directly on the error tries to do configuration resolution after
     * noticing the error, and configuration resolution is interruptible, so it is interrupted. It
     * needs to then throw the error, rather than the interruption.
     * 
     * 
     * This test covers error propagation up to both the configured target that depends on the one
     * in error, as well as the aspect on that configured target, since the error goes through both.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectBaseConfiguredTargetTransitivelyDependingOnPackageInError() {
        setRulesAndAspectsAvailableInTests(
            com.google.common.collect.ImmutableList.of<NativeAspectClass>(),
            com.google.common.collect.ImmutableList.of<RuleDefinition>()
        )
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            "package_group(name = 'function_transition_allowlist', packages = ['//aspect/...'])"
        )
        scratch.file(
            "aspect/aspect.bzl",
            """
        def _setting_impl(ctx):
          return []

        string_flag = rule(
          implementation = _setting_impl,
          build_setting = config.string(flag=True),
        )

        def _rule_impl(ctx):
          pass

        def _transition_impl(settings, attr):
          return {'//aspect:formation': 'mesa'}

        formation_transition = transition(
          implementation = _transition_impl,
          inputs = ['//aspect:formation'],
          outputs = ['//aspect:formation'],
        )

        def _aspect_impl(target, ctx):
          pass

        myaspect = aspect(implementation = _aspect_impl)

        cfgrule = rule(
          implementation = _rule_impl,
          attrs = {
            'to': attr.label(),
            'innocent': attr.label(cfg = formation_transition),
          }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "aspect/BUILD",
            """
        load('aspect.bzl', 'cfgrule', 'string_flag')
        string_flag(name = 'formation', build_setting_default = 'canyon')
        filegroup(name = 'innocent')
        cfgrule(name = 'top', to = '//baz:baz', innocent = ':innocent')
        
        """.trimIndent()
        )
        scratch.file(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'bar', deps = ['//baz:baz'])"
        )
        scratch.file(
            "baz/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        load('//baz/subdir:missing.bzl', 'sym')
        foo_library(name = 'baz')
        
        """.trimIndent()
        )
        scratch.file("baz/subdir/missing.bzl")
        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//aspect:aspect.bzl%myaspect"),
                    "//aspect:top"
                )
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect to fail.
            Truth.assertThat(keepGoing()).isFalse()
        }
        assertContainsEvent("Label '//baz/subdir:missing.bzl' is invalid")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersUncovered() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: java.lang.Exception) {
            // expect to fail.
        }
        assertContainsEvent( // "ERROR /workspace/test/aspect.bzl:9:11: "
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type string."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersTypeMismatch() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectMismatch = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectMismatch]),
                      'my_attr' : attr.int() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 4)
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: java.lang.Exception) {
            // expect to fail.
        }
        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectMismatch requires rule my_rule to specify attribute "
                    + "'my_attr' with type string."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersDontSupportSelect() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectMismatch = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectMismatch]),
                      'my_attr' : attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:aspect.bzl", "my_rule")

        # Needed to avoid the select() being eliminated as trivial.
        config_setting(
            name = "config",
            values = {"defines": "something"},
        )

        my_rule(
            name = "xxx",
            my_attr = select({
                ":config": "foo",
                "//conditions:default": "bar",
            }),
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: java.lang.Exception) {
            // expect to fail.
        }
        assertContainsEvent(
            ("//test:xxx: attribute 'my_attr' has a select() and aspect "
                    + "//test:aspect.bzl%MyAspectMismatch also declares '//test:xxx'. Aspect attributes "
                    + "don't currently support select().")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersBadDefault() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectBadDefault = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a'], default='b') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectBadDefault]) },  # line 11
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: java.lang.Exception) {
            // expect to fail.
        }

        // aspect fails, stack = [<toplevel>@:5:28, aspect@<builtin>]
        assertContainsEvent("File \"/workspace/test/aspect.bzl\", line 5, column 28, in <toplevel>")
        assertContainsEvent(
            "Error in aspect: Aspect parameter attribute 'my_attr' has a bad default value: has to be"
                    + " one of 'a' instead of 'b'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersBadValue() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectBadValue = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectBadValue]),
                      'my_attr' : attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr='b')
        
        """.trimIndent()
        )

        reporter.removeHandler(failFastHandler)
        try {
            val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(result.hasError()).isTrue()
        } catch (e: java.lang.Exception) {
            // expect to fail.
        }
        assertContainsEvent(
            "ERROR /workspace/test/BUILD:2:8: //test:xxx: invalid value in 'my_attr' "
                    + "attribute: has to be one of 'a' instead of 'b'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParameters() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspect = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa']) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]),
                      'my_attr' : attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 'aaa')
        
        """.trimIndent()
        )

        val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersConfigurationField() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_my_attr' : attr.label(default=
                     configuration_field(fragment = "coverage", name = "output_generator")) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        
        """.trimIndent()
        )

        val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParameterComputedDefault() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        def _defattr():
           return Label('//foo/bar:baz')
        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_extra' : attr.label(default = _defattr) }
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspect]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        if (keepGoing()) {
            val result: AnalysisResult = update("//test:xxx")
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:xxx") })
        }
        assertContainsEvent(
            "Aspect attribute '_extra' (label) with computed default value is unsupported."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersOptional() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectOptParam = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['aaa'], default='aaa') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectOptParam]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx')
        
        """.trimIndent()
        )

        val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectParametersOptionalOverride() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           if (ctx.attr.my_attr == 'a'):
               fail('Rule is not overriding default, still has value ' + ctx.attr.my_attr)
           return []
        def _rule_impl(ctx):
           return []
        MyAspectOptOverride = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.string(values=['a', 'b'], default='a') },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectOptOverride]),
                      'my_attr' : attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'xxx', my_attr = 'b')
        
        """.trimIndent()
        )

        val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:xxx")
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleExecutablesInTarget() {
        scratch.file(
            "foo/extension.bzl",
            """
        def _aspect_impl(target, ctx):
           return []
        my_aspect = aspect(_aspect_impl)
        def _main_rule_impl(ctx):
           pass
        my_rule = rule(_main_rule_impl,
           attrs = {
              'exe1' : attr.label(executable = True, allow_files = True, cfg = 'exec'),
              'exe2' : attr.label(executable = True, allow_files = True, cfg = 'exec'),
           },
        )
        
        """.trimIndent()
        )

        scratch.file("foo/tool.sh", "#!/bin/bash")
        scratch.file(
            "foo/BUILD",
            """
        load(':extension.bzl',  'my_rule')
        my_rule(name = 'main', exe1 = ':tool.sh', exe2 = ':tool.sh')
        
        """.trimIndent()
        )
        val analysisResultOfRule: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//foo:main")
        assertThat(analysisResultOfRule.hasError()).isFalse()

        val analysisResultOfAspect: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("/foo/extension.bzl%my_aspect"), "//foo:main")
        assertThat(analysisResultOfAspect.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectFragmentAccessSuccess() {
        analyzeConfiguredTargetForAspectFragment("ctx.fragments.java.strict_java_deps", "'java'", "")
        assertNoEvents()
    }

    @org.junit.Test
    fun aspectFragmentAccessError() {
        reporter.removeHandler(failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable {
                analyzeConfiguredTargetForAspectFragment(
                    "ctx.fragments.java.strict_java_deps", "'cpp'", "'cpp'"
                )
            })
        assertContainsEvent(
            ("//test:aspect.bzl%MyAspect aspect on my_rule has to declare 'java' as a "
                    + "required fragment in order to access it. Please update the 'fragments' argument of "
                    + "the rule definition (for example: fragments = [\"java\"])")
        )
    }

    @Throws(java.lang.Exception::class)
    private fun analyzeConfiguredTargetForAspectFragment(
        fullFieldName: String?, fragments: String?, ruleFragments: String?
    ) {
        scratch.file(
            "test/aspect.bzl",
            "AspectInfo = provider()",
            "def _aspect_impl(target, ctx):",
            "   return AspectInfo(result = str(" + fullFieldName + "))",
            "",
            "RuleInfo = provider()",
            "def _rule_impl(ctx):",
            "   return RuleInfo(stuff = '...')",
            "",
            "MyAspect = aspect(",
            "   implementation=_aspect_impl,",
            "   attr_aspects=['deps'],",
            "   fragments=[" + fragments + "],",
            ")",
            "my_rule = rule(",
            "   implementation=_rule_impl,",
            "   attrs = { 'attr' : ",
            "             attr.label_list(mandatory=True, allow_files=True, aspects = [MyAspect]) },",
            "   fragments=[" + ruleFragments + "],",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        exports_files(['zzz'])
        my_rule(
             name = 'yyy',
             attr = ['zzz'],
        )
        my_rule(
             name = 'xxx',
             attr = ['yyy'],
        )
        
        """.trimIndent()
        )

        val result: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        if (result.hasError()) {
            Truth.assertThat(keepGoing()).isTrue()
            val errorMessage = "Analysis failed"
            throw ViewCreationFailedException(
                errorMessage,
                FailureDetail.newBuilder()
                    .setMessage(errorMessage)
                    .setAnalysis(Analysis.newBuilder().setCode(Code.ANALYSIS_UNKNOWN))
                    .build()
            )
        }

        assertThat(getConfiguredTarget("//test:xxx")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidateAspectOnBzlFileChange() {
        scratch.file("test/build_defs.bzl", aspectBzlFile("'deps'"))
        scratch.file(
            "test/BUILD",
            """
        load(':build_defs.bzl', 'repro', 'repro_no_aspect')
        repro_no_aspect(name = 'r0')
        repro_no_aspect(name = 'r1', deps = [':r0'])
        repro(name = 'r2', deps = [':r1'])
        
        """.trimIndent()
        )
        buildTargetAndCheckRuleInfo("@@//test:r0", "@@//test:r1")

        // Make aspect propagation list empty.
        scratch.overwriteFile("test/build_defs.bzl", aspectBzlFile(""))

        // The aspect should not propagate to //test:r0 anymore.
        buildTargetAndCheckRuleInfo("@@//test:r1")
    }

    @Throws(java.lang.Exception::class)
    private fun buildTargetAndCheckRuleInfo(vararg expectedLabels: String?) {
        val result: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//test:r2")
        val configuredTarget: ConfiguredTarget = result.getTargetsToBuild().iterator().next()
        val ruleInfoValue: Depset =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("rule_info") as Depset
        assertThat(ruleInfoValue.getSet(String::class.java).toList())
            .containsExactlyElementsIn(expectedLabels)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOutputsToBinDirectory() {
        scratch.file(
            "foo/extension.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
           file = ctx.actions.declare_file('aspect-output-' + target.label.name)
           ctx.actions.write(file, 'data')
           return AspectInfo(aspect_file = file)
        my_aspect = aspect(_aspect_impl)
        def _rule_impl(ctx):
           pass
        rule_bin_out = rule(_rule_impl, output_to_genfiles=False)
        rule_gen_out = rule(_rule_impl, output_to_genfiles=True)
        RuleInfo = provider()
        def _main_rule_impl(ctx):
           s = depset([d[AspectInfo].aspect_file for d in ctx.attr.deps])
           return RuleInfo(aspect_files = s)
        main_rule = rule(_main_rule_impl,
           attrs = { 'deps' : attr.label_list(aspects = [my_aspect]) },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load(':extension.bzl', 'rule_bin_out', 'rule_gen_out', 'main_rule')
        rule_bin_out(name = 'rbin')
        rule_gen_out(name = 'rgen')
        main_rule(name = 'main', deps = [':rbin', ':rgen'])
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update(com.google.common.collect.ImmutableList.of<String?>(), "//foo:main")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val aspectFiles: NestedSet<Artifact?> =
            getStarlarkProvider(target, "RuleInfo")
                .getValue("aspect_files", Depset::class.java)
                .getSet(Artifact::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                aspectFiles.toList(),
                Artifact::getFilename
            )
        )
            .containsExactly("aspect-output-rbin", "aspect-output-rgen")
        for (aspectFile in aspectFiles.toList()) {
            val rootPath: String? = aspectFile.getRoot().getExecPath().toString()
            Truth.assertWithMessage("Artifact %s should not be in genfiles", aspectFile)
                .that(rootPath)
                .doesNotContain("genfiles")
            Truth.assertWithMessage("Artifact %s should be in bin", aspectFile).that(rootPath).endsWith("bin")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toplevelAspectOnFile() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file("test/BUILD", "exports_files(['file.txt'])")
        scratch.file("test/file.txt", "")
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:file.txt")
        assertThat(analysisResult.hasError()).isFalse()
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
                .getProviders()
                .getProviderCount()
        )
            .isEqualTo(0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedAttributeDefinitionWithAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target,ctx):
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        _ATTR = { 'deps' : attr.label_list(aspects = [my_aspect]) }
        def _dummy_impl(ctx):
          pass
        r1 = rule(_dummy_impl, attrs =  _ATTR)
        r2 = rule(_dummy_impl, attrs =  _ATTR)
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 't1')
        r2(name = 't2', deps = [':t1'])
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:t2")
        assertThat(analysisResult.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target,ctx):
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        def _dummy_impl(ctx):
          pass
        r1 = rule(_dummy_impl,
                  attrs = { 'deps' : attr.label_list(aspects = [my_aspect, my_aspect]) })
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val result: AnalysisResult = update("//test:r1")
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:r1") })
        }
        assertContainsEvent("aspect //test:aspect.bzl%my_aspect added more than once")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectsAndExtraActions() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target,ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > ${'$'}(location f)',
                                mnemonic='AspectAction')
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        extra_action(
            name = 'xa',
            cmd = 'echo ${'$'}(EXTRA_ACTION_FILE) > ${'$'}(output file.xa)',
            out_templates = ['file.xa'],
        )
        action_listener(
            name = 'al',
            mnemonics = [ 'AspectAction' ],
            extra_actions = [ ':xa' ])
        java_library(name = 'xxx')
        
        """.trimIndent()
        )
        useConfiguration("--experimental_action_listener=//test:al")
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%my_aspect"), "//test:xxx")
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                analysisResult.getArtifactsToBuild(),
                Artifact::getFilename
            )
        )
            .contains("file.xa")
    }

    /** Regression test for b/137960630.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectsAndExtraActionsWithConflict() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > ${'$'}(location f)',
                                mnemonic='AspectAction')
          return []
        my_aspect = aspect(implementation = _aspect_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        extra_action(
            name = 'xa',
            cmd = 'echo ${'$'}(EXTRA_ACTION_FILE) > ${'$'}(output file.xa)',
            out_templates = ['file.xa'],
        )
        action_listener(
            name = 'al',
            mnemonics = ['AspectAction'],
            extra_actions = [':xa'],
        )
        java_library(name = 'xxx')
        java_library(name = 'yyy')
        
        """.trimIndent()
        )
        useConfiguration("--experimental_action_listener=//test:al")
        reporter.removeHandler(failFastHandler) // We expect an error.

        if (keepGoing()) {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%my_aspect"),
                    "//test:xxx",
                    "//test:yyy"
                )
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%my_aspect"),
                        "//test:xxx",
                        "//test:yyy"
                    )
                })
        }
        assertContainsEvent(
            "file 'extra_actions/test/xa/test/file.xa' is generated by these conflicting actions"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsPropagatingToAllAttributes() {
        scratch.file(
            "test/aspect.bzl",
            """
        MyInfo = provider()
        def _impl(target, ctx):
           s = depset([target.label], transitive =
             [i[MyInfo].target_labels for i in ctx.rule.attr.runtime_deps]
             if hasattr(ctx.rule.attr, 'runtime_deps') else [])
           return MyInfo(target_labels = s)

        MyAspect = aspect(
            implementation=_impl,
            attrs = { '_tool' : attr.label(default = Label('//test:tool')) },
            attr_aspects=['*'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = 'tool',
        )
        java_library(
             name = 'bar',
             runtime_deps = [':tool'],
        )
        java_library(
             name = 'foo',
             runtime_deps = [':bar'],
        )
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:foo")
        val configuredAspect: ConfiguredAspect = analysisResult.getAspectsMap().values().iterator().next()
        assertThat(configuredAspect).isNotNull()
        val names: Any =
            getStarlarkProvider(configuredAspect, "//test:aspect.bzl", "MyInfo")
                .getValue("target_labels")
        Truth.assertThat(names).isInstanceOf(Depset::class.java)
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<F?, T?>(
                (names as Depset).toList(),
                com.google.common.base.Function { o: F? ->
                    assertThat(o).isInstanceOf(Label::class.java)
                    (o as Label).name
                })
        )
            .containsExactly("foo", "bar", "tool")
    }

    /** Simple straightforward linear aspects-on-aspects.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectLinear() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p(text = 'random')
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if  ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return a2p(value = value)
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "r2info").getValue("result", net.starlark.java.eval.Sequence::class.java)

        // "yes" means that aspect a2 sees a1's providers.
        Truth.assertThat(result)
            .containsExactly(
                "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a2\"]=yes",
                "@@//test:r1[\"//test:aspect.bzl%a2\"]=no"
            )
    }

    /**
     * Diamond case. rule r1 depends or r0 with aspect a1. rule r2 depends or r0 with aspect a2. rule
     * rcollect depends on r1, r2 with aspect a3.
     * 
     * 
     * Aspect a3 should be applied twice to target r0: once in [a1, a3] sequence and once in [a2,
     * a3] sequence.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectDiamond() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        a2p = provider()
        a3p = provider()
        def _a1_impl(target,ctx):
          return [a1p(value = 'text from a1')]
        a1 = aspect(_a1_impl, attr_aspects = ['deps'], provides = [a1p])

        def _a2_impl(target,ctx):
          return [a2p(value = 'text from a2')]
        a2 = aspect(_a2_impl, attr_aspects = ['deps'], provides = [a2p])

        def _a3_impl(target,ctx):
          value = []
          f = ctx.actions.declare_file('a3.out')
          ctx.actions.write(f, 'text')
          for dep in ctx.rule.attr.deps:
             if a3p in dep:
                 value += dep[a3p].value
          s = str(target.label) + str(ctx.aspect_ids) + '='
          if a1p in target:
             s += 'a1p'
          if a2p in target:
             s += 'a2p'
          value.append(s)
          return [a3p(value = value)]
        a3 = aspect(_a3_impl, attr_aspects = ['deps'],
                    required_aspect_providers = [[a1p], [a2p]])
        def _r1_impl(ctx):
          pass
        RCollectInfo = provider()
        def _rcollect_impl(ctx):
          value = []
          for dep in ctx.attr.deps:
             if a3p in dep:
                 value += dep[a3p].value
          return RCollectInfo(result = value)
        r1 = rule(_r1_impl, attrs = { 'deps' : attr.label_list(aspects = [a1])})
        r2 = rule(_r1_impl, attrs = { 'deps' : attr.label_list(aspects = [a2])})
        rcollect = rule(_rcollect_impl, attrs = { 'deps' : attr.label_list(aspects = [a3])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2', 'rcollect')
        r1(name = 'r0')
        r1(name = 'r1', deps = [':r0'])
        r2(name = 'r2', deps = [':r0'])
        rcollect(name = 'rcollect', deps = [':r1', ':r2'])
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:rcollect")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "RCollectInfo").getValue("result") as net.starlark.java.eval.Sequence<*>?
        Truth.assertThat(result)
            .containsExactly(
                "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a3\"]=a1p",
                "@@//test:r1[\"//test:aspect.bzl%a3\"]=",
                "@@//test:r0[\"//test:aspect.bzl%a2\", \"//test:aspect.bzl%a3\"]=a2p",
                "@@//test:r2[\"//test:aspect.bzl%a3\"]="
            )
    }

    /**
     * Linear with duplicates. r2_1 depends on r0 with aspect a2. r1 depends on r2_1 with aspect a1.
     * r2 depends on r1 with aspect a2.
     * 
     * 
     * a2 is not interested in a1. There should be just one instance of aspect a2 on r0, and is
     * should *not* see a1.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectLinearDuplicates() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p()
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return a2p(value = value)
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r2(name = 'r2_1', dep = ':r0')
        r1(name = 'r1', dep = ':r2_1')
        r2(name = 'r2', dep = ':r1')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "r2info").getValue("result", net.starlark.java.eval.Sequence::class.java)
        // "yes" means that aspect a2 sees a1's providers.
        Truth.assertThat(result)
            .containsExactly(
                "@@//test:r0[\"//test:aspect.bzl%a2\"]=no",
                "@@//test:r1[\"//test:aspect.bzl%a2\"]=no",
                "@@//test:r2_1[\"//test:aspect.bzl%a2\"]=no"
            )
    }

    /** Linear aspects-on-aspects with alias rule.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectLinearAlias() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          value = []
          if ctx.rule.attr.dep and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          if a1p in target:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=yes')
          else:
             value.append(str(target.label) + str(ctx.aspect_ids) + '=no')
          return [a2p(value = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        alias(name = 'a0', actual = ':r0')
        r1(name = 'r1', dep = ':a0')
        r2(name = 'r2', dep = ':r1')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "r2info").getValue("result", net.starlark.java.eval.Sequence::class.java)

        // "yes" means that aspect a2 sees a1's providers.
        Truth.assertThat(result)
            .containsExactly(
                "@@//test:r0[\"//test:aspect.bzl%a1\", \"//test:aspect.bzl%a2\"]=yes",
                "@@//test:r1[\"//test:aspect.bzl%a2\"]=no"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectDescriptions() {
        scratch.file(
            "test/aspect.bzl",
            """
AspectInfo = provider()
def _a_impl(target,ctx):
  s = str(target.label) + str(ctx.aspect_ids) + '='
  value = []
  if ctx.rule.attr.dep:
     d = ctx.rule.attr.dep
     this_id = ctx.aspect_ids[len(ctx.aspect_ids) - 1]
     s += str(d.label) + str(d[AspectInfo].my_ids) + ',' + str(this_id in d[AspectInfo].my_ids)
     value += ctx.rule.attr.dep[AspectInfo].ap
  else:
     s += 'None'
  value.append(s)
  return AspectInfo(ap = value, my_ids = ctx.aspect_ids)
a = aspect(_a_impl, attr_aspects = ['dep'])
RuleInfo = provider()
def _r_impl(ctx):
  if not ctx.attr.dep:
     return RuleInfo(result = [])
  return RuleInfo(result = ctx.attr.dep[AspectInfo].ap)
r = rule(_r_impl, attrs = { 'dep' : attr.label(aspects = [a])})

""".trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r')
        r(name = 'r0')
        r(name = 'r1', dep = ':r0')
        r(name = 'r2', dep = ':r1')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "RuleInfo").getValue("result") as net.starlark.java.eval.Sequence<*>?

        Truth.assertThat(result)
            .containsExactly(
                "@@//test:r0[\"//test:aspect.bzl%a\"]=None",
                "@@//test:r1[\"//test:aspect.bzl%a\"]=@@//test:r0[\"//test:aspect.bzl%a\"],True"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun attributesWithAspectsReused() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        my_aspect = aspect(_impl)
        a_dict = { 'foo' : attr.label_list(aspects = [my_aspect]) }
        
        """.trimIndent()
        )

        scratch.file(
            "test/r1.bzl",
            """
        load(':aspect.bzl', 'my_aspect', 'a_dict')
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs = a_dict)
        
        """.trimIndent()
        )

        scratch.file(
            "test/r2.bzl",
            """
        load(':aspect.bzl', 'my_aspect', 'a_dict')
        def _rule_impl(ctx):
           pass
        r2 = rule(_rule_impl, attrs = a_dict)
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load(':r1.bzl', 'r1')
        load(':r2.bzl', 'r2')
        r1(name = 'x1')
        r2(name = 'x2', foo = [':x1'])
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:x2")
        assertThat(analysisResult.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectAdvertisingProviders() {
        scratch.file(
            "test/aspect.bzl",
            """
        MyInfo = provider()
        def _impl(target, ctx):
           return []
        my_aspect = aspect(_impl, provides = [MyInfo])
        a_dict = { 'foo' : attr.label_list(aspects = [my_aspect]) }
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        reporter.removeHandler(failFastHandler)
        try {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%my_aspect"), "//test:xxx")
            Truth.assertThat(keepGoing()).isTrue()
            assertThat(analysisResult.hasError()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expect exception
        }
        assertContainsEvent(
            "Aspect '//test:aspect.bzl%my_aspect', applied to '//test:xxx', "
                    + "does not provide advertised provider 'MyInfo'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectInconsistentVisibility() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          return [a2p(value = 'random')]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        r1(name = 'r1_1', dep = ':r2')
        r2(name = 'r2_1', dep = ':r1_1')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        try {
            val analysisResult: AnalysisResult = update("//test:r2_1")
            assertThat(analysisResult.hasError()).isTrue()
            Truth.assertThat(keepGoing()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expected
        }
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:3:3: Aspect //test:aspect.bzl%a2 is"
                    + " applied twice, both before and after aspect //test:aspect.bzl%a1 "
                    + "(when propagating to //test:r1)")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectInconsistentVisibilityIndirect() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          return [a1p(text = 'random')]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
          return [a2p(value = 'random')]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r1_impl(ctx):
          pass
        r2info = provider()
        def _r2_impl(ctx):
          return r2info(result = ctx.attr.dep[a2p].value)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        def _r0_impl(ctx):
          pass
        r0 = rule(_r0_impl, attrs = { 'dep' : attr.label()})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        r1(name = 'r1_1', dep = ':r2')
        r2(name = 'r2_1', dep = ':r1_1')
        r0(name = 'r0_2', dep = ':r2_1')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        try {
            val analysisResult: AnalysisResult = update("//test:r0_2")
            assertThat(analysisResult.hasError()).isTrue()
            Truth.assertThat(keepGoing()).isTrue()
        } catch (e: ViewCreationFailedException) {
            // expected
        }
        assertContainsEvent(
            ("ERROR /workspace/test/BUILD:3:3: Aspect //test:aspect.bzl%a2 is"
                    + " applied twice, both before and after aspect //test:aspect.bzl%a1 "
                    + "(when propagating to //test:r1)")
        )
    }

    /**
     * Aspect a3 sees aspect a2, aspect a2 sees aspect a1, but a3 does not see a1. All three aspects
     * should still propagate together.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectOnAspect() {
        scratch.file(
            "test/aspect.bzl",
            """
        p1 = provider()
        def _a1_impl(target, ctx):
           return [p1()]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [p1])
        p2 = provider()
        def _a2_impl(target, ctx):
           value = True if p1 in target else False
           return [p2(has_p1 = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'],
           required_aspect_providers = [p1], provides = [p2])
        p3 = provider()
        def _a3_impl(target, ctx):
           list = []
           if ctx.rule.attr.dep:
             list = ctx.rule.attr.dep[p3].value
           my_value = str(target.label) +'=' + str(target[p2].has_p1 if p2 in target else False)
           return [p3(value = list + [my_value])]
        a3 = aspect(_a3_impl, attr_aspects = ['dep'],
           required_aspect_providers = [p2])
        def _r0_impl(ctx):
          pass
        r0 = rule(_r0_impl, attrs = { 'dep' : attr.label()})
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          pass
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0_1')
        r0(name = 'r0_2', dep = ':r0_1')
        r0(name = 'r0_3', dep = ':r0_2')
        r1(name = 'r1_1', dep = ':r0_3')
        r2(name = 'r2_1', dep = ':r1_1')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%a3"), "//test:r2_1")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val p3: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "p3")
        val p3Provider: StructImpl = configuredAspect.get(p3) as StructImpl
        Truth.assertThat(p3Provider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "@@//test:r0_1=True",
                "@@//test:r0_2=True",
                "@@//test:r0_3=True",
                "@@//test:r1_1=False",
                "@@//test:r2_1=False"
            )
    }

    /**
     * r0 is a dependency of r1 via two attributes, dep1 and dep2. r1 sends an aspect 'a' along dep1
     * but not along dep2.
     * 
     * 
     * rcollect depends upon r1 and sends another aspect, 'collector', along its dep dependency.
     * 'collector' wants to see aspect 'a' and propagates along dep1 and dep2. It should be applied
     * both to r0 and to r0+a.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleDepsDifferentAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        PAspect = provider()
        PCollector = provider()
        def _aspect_impl(target, ctx):
           return [PAspect()]
        a = aspect(_aspect_impl, attr_aspects = ['dep'], provides = [PAspect])
        def _collector_impl(target, ctx):
           suffix = '+PAspect' if PAspect in target else ''
           result = [str(target.label)+suffix]
           for a in ['dep', 'dep1', 'dep2']:
             if hasattr(ctx.rule.attr, a):
                result += getattr(ctx.rule.attr, a)[PCollector].result
           return [PCollector(result=result)]
        collector = aspect(_collector_impl, attr_aspects = ['*'],
                           required_aspect_providers = [PAspect])
        def _rimpl(ctx):
           pass
        r0 = rule(_rimpl)
        r1 = rule(_rimpl,
                  attrs = {
                     'dep1' : attr.label(),
                     'dep2' : attr.label(aspects = [a]),
                  },
        )
        def _rcollect_impl(ctx):
            return [ctx.attr.dep[PCollector]]
        rcollect = rule(_rcollect_impl,
                        attrs = {
                          'dep' : attr.label(aspects = [collector]),
                        })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r1', 'rcollect')
        r0(name = 'r0')
        r1(name = 'r1', dep1 = ':r0', dep2 = ':r0')
        rcollect(name = 'rcollect', dep = ':r1')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:rcollect")
        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val pCollector: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector"
            )
        val pCollectorProvider: StructImpl = configuredTarget.get(pCollector) as StructImpl
        Truth.assertThat(pCollectorProvider.getValue("result") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly("@@//test:r1", "@@//test:r0", "@@//test:r0+PAspect")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectSeesOtherAspectAttributes() {
        scratch.file(
            "test/aspect.bzl",
            """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['aspect_attr'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
                   provides = [PAspect],
                   attrs = {'_a_attr' : attr.label(default = '//test:foo')})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, '_a_attr'):
             return [PCollector(aspect_attr = ctx.rule.attr._a_attr.label)]
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl, attrs = { 'dep' : attr.label(aspects = [a]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'foo')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%acollect"), "//test:baz")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val pCollector: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector"
            )
        val collector: StructImpl = configuredAspect.get(pCollector) as StructImpl
        assertThat(collector.getValue("aspect_attr")).isEqualTo(Label.parseCanonical("//test:foo"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleAttributesWinOverAspects() {
        scratch.file(
            "test/aspect.bzl",
            """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['attr_value'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
                   provides = [PAspect],
                   attrs = {'_same_attr' : attr.int(default = 239)})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, '_same_attr'):
             return [PCollector(attr_value = ctx.rule.attr._same_attr)]
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a]),
                          '_same_attr' : attr.int(default = 30)
                  })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'foo')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%acollect"), "//test:baz")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val pCollector: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector"
            )
        val collector: StructImpl = configuredAspect.get(pCollector) as StructImpl
        assertThat(collector.getValue("attr_value")).isEqualTo(StarlarkInt.of(30))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun earlyAspectAttributesWin() {
        scratch.file(
            "test/aspect.bzl",
            """
        PAspect1 = provider(fields = [])
        PAspect2 = provider(fields = [])
        PCollector = provider(fields = ['attr_value'])
        def _a1_impl(target, ctx):
          return [PAspect1()]
        def _a2_impl(target, ctx):
          return [PAspect2()]
        a1 = aspect(_a1_impl,
                    provides = [PAspect1],
                    attrs = {'_same_attr' : attr.int(default = 30)})
        a2 = aspect(_a2_impl,
                    provides = [PAspect2],
                    attrs = {'_same_attr' : attr.int(default = 239)})
        def _rcollect(target, ctx):
          if hasattr(ctx.rule.attr, 'dep'):
             return [ctx.rule.attr.dep[PCollector]]
          if hasattr(ctx.rule.attr, '_same_attr'):
             return [PCollector(attr_value = ctx.rule.attr._same_attr)]
          fail('???')
          return [PCollector()]
        acollect = aspect(_rcollect, attr_aspects = ['*'],
                          required_aspect_providers = [[PAspect1], [PAspect2]])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r1 = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a1]),
                  })
        r2 = rule(_rimpl,
                  attrs = {
                          'dep' : attr.label(aspects = [a2]),
                  })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'bar')
        r1(name = 'baz', dep = ':bar')
        r2(name = 'quux', dep = ':baz')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%acollect"), "//test:quux")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val pCollector: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector"
            )
        val collector: StructImpl = configuredAspect.get(pCollector) as StructImpl
        assertThat(collector.getValue("attr_value")).isEqualTo(StarlarkInt.of(30))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectPropagatesOverOtherAspectAttributes() {
        scratch.file(
            "test/aspect.bzl",
            """
        PAspect = provider(fields = [])
        PCollector = provider(fields = ['visited'])
        def _a_impl(target, ctx):
          return [PAspect()]
        a = aspect(_a_impl,
               provides = [PAspect],
               attrs = {'_a_attr' : attr.label(default = '//test:referenced_from_aspect_only')})
        def _rcollect(target, ctx):
          transitive = []
          if hasattr(ctx.rule.attr, 'dep') and ctx.rule.attr.dep:
             transitive += [ctx.rule.attr.dep[PCollector].visited]
          if hasattr(ctx.rule.attr, '_a_attr') and ctx.rule.attr._a_attr:
             transitive += [ctx.rule.attr._a_attr[PCollector].visited]
          visited = depset([target.label], transitive = transitive, )
          return [PCollector(visited = visited)]
        acollect = aspect(_rcollect, attr_aspects = ['*'], required_aspect_providers = [PAspect])
        def _rimpl(ctx):
          pass
        r0 = rule(_rimpl)
        r = rule(_rimpl, attrs = { 'dep' : attr.label(aspects = [a]) })
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r')
        r0(name = 'referenced_from_aspect_only')
        r0(name = 'bar')
        r(name = 'baz', dep = ':bar')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:aspect.bzl%acollect"), "//test:baz")
        val configuredAspect: ConfiguredAspect? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
        val pCollector: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "PCollector"
            )
        val collector: StructImpl = configuredAspect.get(pCollector) as StructImpl
        assertThat((collector.getValue("visited") as Depset).toList())
            .containsExactly(
                Label.parseCanonical("//test:referenced_from_aspect_only"),
                Label.parseCanonical("//test:bar"),
                Label.parseCanonical("//test:baz")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectActionProvider() {
        scratch.file(
            "test/aspect.bzl",
            """
        a1p = provider()
        def _a1_impl(target,ctx):
          ctx.actions.run_shell(
            outputs = [ctx.actions.declare_file('a1')],
            command = 'touch ${'$'}@'
          )
          return [a1p()]
        a1 = aspect(_a1_impl, attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target, ctx):
          value = []
          if hasattr(ctx.rule.attr, 'dep') and a2p in ctx.rule.attr.dep:
             value += ctx.rule.attr.dep[a2p].value
          value += target.actions
          return [a2p(value = value)]
        a2 = aspect(_a2_impl, attr_aspects = ['dep'], required_aspect_providers = [a1p])
        def _r0_impl(ctx):
          ctx.actions.run_shell(
            outputs = [ctx.actions.declare_file('r0')],
            command = 'touch ${'$'}@'
          )
        RuleInfo = provider()
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[a2p].value)
        r0 = rule(_r0_impl)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':aspect.bzl', 'r0', 'r1', 'r2')
        r0(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: net.starlark.java.eval.Sequence<*>? =
            getStarlarkProvider(target, "RuleInfo").getValue("result") as net.starlark.java.eval.Sequence<*>?

        // We should see both the action from the 'r0' rule, and the action from the 'a1' aspect
        Truth.assertThat(result).hasSize(2)
        Truth.assertThat(
            result.stream()
                .map<Any?> { a: Any? -> (a as Action).getPrimaryOutput().getExecPath().getBaseName() }
                .collect(Collectors.toList()))
            .containsExactly("r0", "a1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAndAspectAttrConflict() {
        scratch.file(
            "test/aspect.bzl",
            """
        MyInfo = provider()
        def _impl(target, ctx):
           return [MyInfo(hidden_attr_label = str(ctx.attr._hiddenattr.label))]

        def _rule_impl(ctx):
           return []

        my_rule = rule(implementation = _rule_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:xxx')) },
        )
        MyAspect = aspect(
           implementation=_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:zzz')) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load('//test:aspect.bzl', 'my_rule')
        cc_library(
             name = 'xxx',
        )
        my_rule(
             name = 'yyy',
        )
        cc_library(
             name = 'zzz',
        )
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:yyy")
        val configuredAspect: ConfiguredAspect = analysisResult.getAspectsMap().values().iterator().next()
        assertThat(configuredAspect).isNotNull()

        val myInfoKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:aspect.bzl")), "MyInfo")
        val myInfo: StructImpl = configuredAspect.get(myInfoKey) as StructImpl
        assertThat(myInfo.getValue("hidden_attr_label")).isEqualTo("@@//test:zzz")
    }

    /** Simple straightforward linear aspects-on-aspects.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAspectAttrConflict() {
        scratch.file(
            "test/aspect.bzl",
            """
        MyInfo = provider()
        a1p = provider()
        def _a1_impl(target,ctx):
          return a1p(text = 'random')
        a1 = aspect(_a1_impl,
           attrs = { '_hiddenattr' : attr.label(default = Label('//test:xxx')) },
           attr_aspects = ['dep'], provides = [a1p])
        a2p = provider()
        def _a2_impl(target,ctx):
           return [MyInfo(hidden_attr_label = str(ctx.attr._hiddenattr.label))]
        a2 = aspect(_a2_impl,
          attrs = { '_hiddenattr' : attr.label(default = Label('//test:zzz')) },
          attr_aspects = ['dep'], required_aspect_providers = [a1p])
        RuleInfo = provider()
        def _r1_impl(ctx):
          pass
        def _r2_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[MyInfo].hidden_attr_label)
        r1 = rule(_r1_impl, attrs = { 'dep' : attr.label(aspects = [a1])})
        r2 = rule(_r2_impl, attrs = { 'dep' : attr.label(aspects = [a1, a2])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':aspect.bzl', 'r1', 'r2')
        r1(name = 'r0')
        r1(name = 'r1', dep = ':r0')
        r2(name = 'r2', dep = ':r1')
        cc_library(
             name = 'xxx',
        )
        cc_library(
             name = 'zzz',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:r2")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result: String? = getStarlarkProvider(target, "RuleInfo").getValue("result", String::class.java)

        Truth.assertThat(result).isEqualTo("@@//test:zzz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllCcLibraryAttrsAreValidTypes() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
          for entry in dir(ctx.rule.attr):
            val = getattr(ctx.rule.attr, entry, None)
            # Only legitimate Starlark values can be passed to dir(), so this effectively
            # verifies val is an appropriate Starlark type.
            _test_dir = dir(val)
          return []

        MyAspect = aspect(
          implementation=_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
             name = 'xxx',
        )
        
        """.trimIndent()
        )
        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")
        assertThat(analysisResult.getAspectsMap().values().iterator().next()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testApplyToGeneratingRules() {
        // Create test rules:
        // dep_rule: a rule which may depend on other dep_rule targets and may optionally create
        //     an output file.
        // root_{with,no}_files: a rule which depends on dep_rule targets and attaches an aspect.
        //     The rule returns a RootInfo provider which contains two fields:
        //        'from_aspect' : a list of all labels that the aspect propagated to
        //        'non_aspect' : a list of all labels that information was obtained from without aspect
        //     root_with_files uses an aspect with apply_to_generating_rules=True, and root_no_files
        //     uses an aspect with apply_to_generating_rules=False.
        scratch.file(
            "test/lib.bzl",
            """
        RootInfo = provider()
        NonAspectInfo = provider()
        FromAspectInfo = provider()
        def _aspect_impl(target, ctx):
          dep_labels = []
          for dep in ctx.rule.attr.deps:
            if FromAspectInfo in dep:
              dep_labels += [dep[FromAspectInfo].labels]
          return FromAspectInfo(labels = depset(direct = [ctx.label], transitive = dep_labels))

        def _rule_impl(ctx):
          non_aspect = []
          from_aspect = []
          for dep in ctx.attr.deps:
            if NonAspectInfo in dep:
              non_aspect +=  dep[NonAspectInfo].labels.to_list()
            if FromAspectInfo in dep:
              from_aspect += dep[FromAspectInfo].labels.to_list()
          return RootInfo(from_aspect = from_aspect, non_aspect = non_aspect)

        def _dep_rule_impl(ctx):
          if ctx.outputs.output:
            ctx.actions.run_shell(outputs = [ctx.outputs.output], command = 'dont run me')
          dep_labels = []
          for dep in ctx.attr.deps:
            if NonAspectInfo in dep:
              dep_labels += [dep[NonAspectInfo].labels]
          return NonAspectInfo(labels = depset(direct = [ctx.label], transitive = dep_labels))

        aspect_with_files = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
          apply_to_generating_rules = True)

        aspect_no_files = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
          apply_to_generating_rules = False)

        root_with_files = rule(implementation = _rule_impl,
          attrs = {'deps' : attr.label_list(aspects = [aspect_with_files])})

        root_no_files = rule(implementation = _rule_impl,
          attrs = {'deps' : attr.label_list(aspects = [aspect_no_files])})

        dep_rule = rule(implementation = _dep_rule_impl,
          attrs = {'deps' : attr.label_list(allow_files = True), 'output' : attr.output()})
        
        """.trimIndent()
        )

        // Create a target graph such that two graph roots each point to a common subgraph
        // alpha -> beta_output -> charlie, where beta_output is a generated output file of target
        // 'beta'.
        scratch.file(
            "test/BUILD",
            """
        load('//test:lib.bzl', 'root_with_files', 'root_no_files', 'dep_rule')

        root_with_files(name = 'test_with_files', deps = [':alpha'])
        root_no_files(name = 'test_no_files', deps = [':alpha'])
        dep_rule(name = 'alpha', deps = [':beta_output'])
        dep_rule(name = 'beta', deps = [':charlie'], output = 'beta_output')
        dep_rule(name = 'charlie')
        
        """.trimIndent()
        )

        val rootInfoKey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootInfo")

        val analysisResultWithFiles: AnalysisResult = update("//test:test_with_files")
        val targetWithFiles: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResultWithFiles.getTargetsToBuild())
        val rootInfoWithFiles: StructImpl = targetWithFiles.get(rootInfoKey) as StructImpl
        // With apply_to_generating_rules=True, the aspect should have traversed :beta_output and
        // applied to both :beta and :charlie.
        assertThat(rootInfoWithFiles.getValue("from_aspect", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(
                Label.parseCanonical("//test:charlie"),
                Label.parseCanonical("//test:beta"),
                Label.parseCanonical("//test:alpha")
            )
        assertThat(rootInfoWithFiles.getValue("non_aspect", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))

        val analysisResultNoFiles: AnalysisResult = update("//test:test_no_files")
        val targetNoFiles: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResultNoFiles.getTargetsToBuild())
        val rootInfoNoFiles: StructImpl = targetNoFiles.get(rootInfoKey) as StructImpl
        // With apply_to_generating_rules=False, the aspect should have only accessed :alpha, as it
        // must have stopped before :beta_output.
        assertThat(rootInfoNoFiles.getValue("from_aspect", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
        assertThat(rootInfoWithFiles.getValue("non_aspect", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
    }

    @Throws(java.lang.Exception::class)
    private fun setupAspectOnAspectTargetGraph(
        applyRootToGeneratingRules: Boolean, applyDepToGeneratingRules: Boolean
    ) {
        // RootAspectInfo.both_labels returns a list of target labels which
        //     were evaluated as [root_aspect(dep_aspect(target))].
        // RootAspectInfo.root_only_labels returns a list of target labels which
        //     were evaluated as [root_aspect(target)].
        // DepAspectInfo.labels returns a list of target labels which were evaluated by dep_aspect.
        scratch.file(
            "test/lib.bzl",
            "RootAspectInfo = provider()",
            "DepAspectInfo = provider()",
            "def _root_aspect_impl(target, ctx):",
            "  both_labels = []",
            "  root_only_labels = []",
            "  for dep in ctx.rule.attr.deps:",
            "    if RootAspectInfo in dep:",
            "      both_labels += dep[RootAspectInfo].both_labels",
            "      root_only_labels += dep[RootAspectInfo].root_only_labels",
            "      if DepAspectInfo in dep:",
            "        both_labels += [dep.label]",
            "      else:",
            "        root_only_labels += [dep.label]",
            "  return RootAspectInfo(both_labels = both_labels, root_only_labels = root_only_labels)",
            "",
            "def _dep_aspect_impl(target, ctx):",
            "  dep_labels = [ctx.label]",
            "  for dep in ctx.rule.attr.deps:",
            "    if DepAspectInfo in dep:",
            "      dep_labels += dep[DepAspectInfo].labels",
            "  return DepAspectInfo(labels = dep_labels)",
            "",
            "def _root_rule_impl(ctx):",
            "  return [ctx.attr.deps[0][RootAspectInfo], ctx.attr.deps[0][DepAspectInfo]]",
            "",
            "def _dep_with_aspect_rule_impl(ctx):",
            "  return [ctx.attr.deps[0][DepAspectInfo]]",
            "",
            "def _dep_rule_impl(ctx):",
            "  if ctx.outputs.output:",
            "    ctx.actions.run_shell(outputs = [ctx.outputs.output], command = 'dont run me')",
            "  return []",
            "",
            "root_aspect = aspect(",
            "  implementation = _root_aspect_impl,",
            "  attr_aspects = ['deps'],",
            "  required_aspect_providers = [DepAspectInfo],",
            "  apply_to_generating_rules = " + (if (applyRootToGeneratingRules) "True" else "False") + ")",
            "",
            "dep_aspect = aspect(",
            "  implementation = _dep_aspect_impl,",
            "  attr_aspects = ['deps'],",
            "  provides = [DepAspectInfo],",
            "  apply_to_generating_rules = " + (if (applyDepToGeneratingRules) "True" else "False") + ")",
            "",
            "root_rule = rule(implementation = _root_rule_impl,",
            "  attrs = {'deps' : attr.label_list(aspects = [root_aspect])})",
            "",
            "dep_with_aspect_rule = rule(implementation = _dep_with_aspect_rule_impl,",
            "  attrs = {'deps' : attr.label_list(aspects = [dep_aspect])})",
            "",
            "dep_rule = rule(implementation = _dep_rule_impl,",
            "  attrs = {'deps' : attr.label_list(allow_files = True), 'output' : attr.output()})"
        )

        // Target graph: test -> aspect_propagating_target -> alpha -> beta_output
        // beta_output is an output of target `beta`
        // beta -> charlie
        scratch.file(
            "test/BUILD",
            """
        load('//test:lib.bzl', 'root_rule', 'dep_with_aspect_rule', 'dep_rule')

        root_rule(name = 'test', deps = [':aspect_propagating_target'])
        dep_with_aspect_rule(name = 'aspect_propagating_target', deps = [':alpha'])
        dep_rule(name = 'alpha', deps = [':beta_output'])
        dep_rule(name = 'beta', deps = [':charlie'], output = 'beta_output')
        dep_rule(name = 'charlie')
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnAspectApplyToGeneratingRules_bothPropagate() {
        setupAspectOnAspectTargetGraph( /* applyRootToGeneratingRules= */
            true,  /* applyDepToGeneratingRules= */true
        )

        val rootInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo"
            )
        val depInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo"
            )

        val analysisResult: AnalysisResult = update("//test:test")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val rootInfo: StructImpl = target.get(rootInfoKey) as StructImpl
        val depInfo: StructImpl = target.get(depInfoKey) as StructImpl

        assertThat(rootInfo.getValue("both_labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(
                Label.parseCanonical("//test:alpha"),
                Label.parseCanonical("//test:beta_output"),
                Label.parseCanonical("//test:charlie")
            )
        assertThat(rootInfo.getValue("root_only_labels", net.starlark.java.eval.Sequence::class.java)).isEmpty()
        assertThat(depInfo.getValue("labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(
                Label.parseCanonical("//test:alpha"),
                Label.parseCanonical("//test:beta"),
                Label.parseCanonical("//test:charlie")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnAspectApplyToGeneratingRules_neitherPropagate() {
        setupAspectOnAspectTargetGraph( /* applyRootToGeneratingRules= */
            false,  /* applyDepToGeneratingRules= */false
        )

        val rootInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo"
            )
        val depInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo"
            )

        val analysisResult: AnalysisResult = update("//test:test")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val rootInfo: StructImpl = target.get(rootInfoKey) as StructImpl
        val depInfo: StructImpl = target.get(depInfoKey) as StructImpl

        assertThat(rootInfo.getValue("both_labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
        assertThat(rootInfo.getValue("root_only_labels", net.starlark.java.eval.Sequence::class.java)).isEmpty()
        assertThat(depInfo.getValue("labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnAspectApplyToGeneratingRules_rootOnly() {
        setupAspectOnAspectTargetGraph( /* applyRootToGeneratingRules= */
            true,  /* applyDepToGeneratingRules= */false
        )

        val rootInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo"
            )
        val depInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo"
            )

        val analysisResult: AnalysisResult = update("//test:test")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val rootInfo: StructImpl = target.get(rootInfoKey) as StructImpl
        val depInfo: StructImpl = target.get(depInfoKey) as StructImpl

        assertThat(rootInfo.getValue("both_labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
        assertThat(rootInfo.getValue("root_only_labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(
                Label.parseCanonical("//test:beta_output"), Label.parseCanonical("//test:charlie")
            )
        assertThat(depInfo.getValue("labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnAspectApplyToGeneratingRules_depOnly() {
        setupAspectOnAspectTargetGraph( /* applyRootToGeneratingRules= */
            false,  /* applyDepToGeneratingRules= */true
        )

        val rootInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "RootAspectInfo"
            )
        val depInfoKey: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:lib.bzl")), "DepAspectInfo"
            )

        val analysisResult: AnalysisResult = update("//test:test")
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val rootInfo: StructImpl = target.get(rootInfoKey) as StructImpl
        val depInfo: StructImpl = target.get(depInfoKey) as StructImpl

        assertThat(rootInfo.getValue("both_labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(Label.parseCanonical("//test:alpha"))
        assertThat(rootInfo.getValue("root_only_labels", net.starlark.java.eval.Sequence::class.java)).isEmpty()
        assertThat(depInfo.getValue("labels", net.starlark.java.eval.Sequence::class.java))
            .containsExactly(
                Label.parseCanonical("//test:alpha"),
                Label.parseCanonical("//test:beta"),
                Label.parseCanonical("//test:charlie")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectActionsDontInheritExecProperties() {
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'my_target',
          deps = [':my_dep'],
        )
        cc_binary(
          name = 'my_dep',
          srcs = ['dep.cc'],
          exec_properties = {'mem': '16g'},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_impl(target, ctx):
          f = ctx.actions.declare_file('f.txt')
          ctx.actions.write(f, 'f')
          return []
        my_aspect = aspect(
          implementation = _aspect_impl,
          attr_aspects = ['deps'],
        )
        def _rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _rule_impl,
          attrs = {
            'deps': attr.label_list(aspects = [my_aspect])
          },
        )
        
        """.trimIndent()
        )
        scratch.file("test/dep.cc", "int main() {return 0;}")

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%my_aspect"), "//test:my_target")
        assertThat(analysisResult).isNotNull()
        val owner: ActionOwner =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().values())
                    .getActions()
            )
                .getOwner()
        assertThat(owner.getExecProperties()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredProviders_defaultNoRequiredProviders() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()

        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return [AspectInfo(target_labels = targets_labels)]

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects = [my_aspect])
          },
        )

        def _rule_with_providers_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_providers = rule(
          implementation = _rule_with_providers_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_providers_not_advertised = rule(
          implementation = _rule_with_providers_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_with_providers', 'rule_without_providers',
                                'rule_with_providers_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_providers',
                  ':target_with_providers_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_providers(
          name = 'target_with_providers',
        )
        rule_with_providers(
          name = 'target_with_providers_indeps',
        )
        rule_with_providers_not_advertised(
          name = 'target_with_providers_not_advertised',
          deps = [':target_with_providers_indeps'],
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // my_aspect does not require any providers so it will be applied to all the dependencies of
        // main target
        val expected: MutableList<String?> = java.util.ArrayList<String?>()
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_without_providers)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers_not_advertised)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_providers_indeps)")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val ruleDepsUnchecked: Any? = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps")
        Truth.assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList::class.java)
        val ruleDeps: StarlarkList<*>? = ruleDepsUnchecked as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredProviders_flatSetOfRequiredProviders() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()

        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return AspectInfo(target_labels = targets_labels)

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
          required_providers = [prov_a, prov_b],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects=[my_aspect])
          },
          provides = []
        )

        def _rule_with_a_impl(ctx):
          return [prov_a()]

        rule_with_a = rule(
          implementation = _rule_with_a_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a]
        )

        def _rule_with_ab_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_ab = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_ab_not_advertised = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_without_providers', 'rule_with_a', 'rule_with_ab',
                                'rule_with_ab_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_a', ':target_with_ab',
                  ':target_with_ab_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_a(
          name = 'target_with_a',
          deps = [':target_with_ab_indeps_not_reached']
        )
        rule_with_ab(
          name = 'target_with_ab',
          deps = [':target_with_ab_indeps_reached']
        )
        rule_with_ab(
          name = 'target_with_ab_indeps_not_reached',
        )
        rule_with_ab(
          name = 'target_with_ab_indeps_reached',
        )
        rule_with_ab_not_advertised(
          name = 'target_with_ab_not_advertised',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // my_aspect will only be applied on target_with_ab and target_with_ab_indeps_reached since
        // their rule (rule_with_ab) is the only rule that advertises the aspect required providers.
        // However, my_aspect cannot be propagated to target_with_ab_indeps_not_reached because it was
        // not applied to its parent (target_with_a)
        val expected: MutableList<String?> = java.util.ArrayList<String?>()
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab_indeps_reached)")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val ruleDepsUnchecked: Any? = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps")
        Truth.assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList::class.java)
        val ruleDeps: StarlarkList<*>? = ruleDepsUnchecked as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredProviders_listOfRequiredProvidersLists() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()
        AspectInfo = provider()
        def _my_aspect_impl(target, ctx):
          targets_labels = ["//test:defs.bzl%my_aspect({})".format(target.label)]
          for dep in ctx.rule.attr.deps:
            if AspectInfo in dep:
              targets_labels.extend(dep[AspectInfo].target_labels)
          return [AspectInfo(target_labels = targets_labels)]

        my_aspect = aspect(
          implementation = _my_aspect_impl,
          attr_aspects = ['deps'],
          required_providers = [[prov_a, prov_b], [prov_c]],
        )

        RuleInfo = provider()
        def _rule_without_providers_impl(ctx):
          s = []
          for dep in ctx.attr.deps:
            if AspectInfo in dep:
              s.extend(dep[AspectInfo].target_labels)
          return RuleInfo(rule_deps = s)

        rule_without_providers = rule(
          implementation = _rule_without_providers_impl,
          attrs = {
            'deps': attr.label_list(aspects=[my_aspect])
          },
          provides = []
        )

        def _rule_with_a_impl(ctx):
          return [prov_a()]

        rule_with_a = rule(
          implementation = _rule_with_a_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a]
        )

        def _rule_with_c_impl(ctx):
          return [prov_c()]

        rule_with_c = rule(
          implementation = _rule_with_c_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_c]
        )

        def _rule_with_ab_impl(ctx):
          return [prov_a(), prov_b()]

        rule_with_ab = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = [prov_a, prov_b]
        )

        rule_with_ab_not_advertised = rule(
          implementation = _rule_with_ab_impl,
          attrs = {
            'deps': attr.label_list()
          },
          provides = []
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_without_providers', 'rule_with_a', 'rule_with_c',
                                'rule_with_ab', 'rule_with_ab_not_advertised')
        rule_without_providers(
          name = 'main',
          deps = [':target_without_providers', ':target_with_a', ':target_with_c',
                  ':target_with_ab', 'target_with_ab_not_advertised'],
        )
        rule_without_providers(
          name = 'target_without_providers',
        )
        rule_with_a(
          name = 'target_with_a',
          deps = [':target_with_c_indeps_not_reached'],
        )
        rule_with_c(
          name = 'target_with_c',
        )
        rule_with_c(
          name = 'target_with_c_indeps_reached',
        )
        rule_with_c(
          name = 'target_with_c_indeps_not_reached',
        )
        rule_with_ab(
          name = 'target_with_ab',
          deps = [':target_with_c_indeps_reached'],
        )
        rule_with_ab_not_advertised(
          name = 'target_with_ab_not_advertised',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // my_aspect will only be applied on target_with_ab, target_wtih_c and
        // target_with_c_indeps_reached because their rules (rule_with_ab and rule_with_c) are the only
        // rules advertising the aspect required providers
        // However, my_aspect cannot be propagated to target_with_c_indeps_not_reached because it was
        // not applied to its parent (target_with_a)
        val expected: MutableList<String?> = java.util.ArrayList<String?>()
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_ab)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_c)")
        expected.add("//test:defs.bzl%my_aspect(@@//test:target_with_c_indeps_reached)")
        Truth.assertThat(getLabelsToBuild(analysisResult)).containsExactly("//test:main")
        val target: ConfiguredTarget = analysisResult.getTargetsToBuild().iterator().next()
        val ruleDepsUnchecked: Any? = getStarlarkProvider(target, "RuleInfo").getValue("rule_deps")
        Truth.assertThat(ruleDepsUnchecked).isInstanceOf(StarlarkList::class.java)
        val ruleDeps: StarlarkList<*>? = ruleDepsUnchecked as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleDeps)).containsExactlyElementsIn(expected)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredByMultipleAspects_inheritsAttrAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {}'.format(target.label)]
          return [prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
        )

        def _aspect_b_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep_b:
            res += ctx.rule.attr.dep_b[prov_b].val
          return [prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep_b'],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep_a:
            res += ctx.rule.attr.dep_a[prov_a].val
          return [prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep_a'],
          requires = [aspect_c],
        )

        def _my_rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep_a': attr.label(),
            'dep_b': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep_a = ':dep_target_a',
          dep_b = ':dep_target_b',
        )
        my_rule(
          name = 'dep_target_a',
        )
        my_rule(
          name = 'dep_target_b',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        // aspect_a should run on main_target and dep_target_a and can retrieve aspect_c provider value
        // on both of them
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aResult: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a")
        val aResultProvider: StructImpl = aspectA.get(aResult) as StructImpl
        Truth.assertThat(aResultProvider.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_target_a",
                "aspect_a runs on target @@//test:dep_target_a",
                "aspect_c runs on target @@//test:main_target",
                "aspect_a runs on target @@//test:main_target"
            )

        // aspect_b should run on main_target and dep_target_b and can retrieve aspect_c provider value
        // on both of them
        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectA).isNotNull()
        val bResult: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_b")
        val bResultProvider: StructImpl = aspectB.get(bResult) as StructImpl
        Truth.assertThat(bResultProvider.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_target_b",
                "aspect_b runs on target @@//test:dep_target_b",
                "aspect_c runs on target @@//test:main_target",
                "aspect_b runs on target @@//test:main_target"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredByMultipleAspects_inheritsRequiredProviders() {
        scratch.file(
            "test/defs.bzl",
            """
        aspect_prov_a = provider()
        aspect_prov_b = provider()
        aspect_prov_c = provider()
        rule_prov_a = provider()
        rule_prov_b = provider()
        rule_prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {}'.format(target.label)]
          return [aspect_prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          required_providers = [rule_prov_c],
        )

        def _aspect_b_impl(target, ctx):
          res = []
          if aspect_prov_c in target:
            res += target[aspect_prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              if aspect_prov_b in dep:
                res += dep[aspect_prov_b].val
          return [aspect_prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['deps'],
          required_providers = [[rule_prov_b], [rule_prov_c]],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          if aspect_prov_c in target:
            res += target[aspect_prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              if aspect_prov_a in dep:
                res += dep[aspect_prov_a].val
          return [aspect_prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['deps'],
          required_providers = [[rule_prov_a], [rule_prov_c]],
          requires = [aspect_c],
        )

        def _my_rule_impl(ctx):
          return [rule_prov_a(), rule_prov_b()]

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a, rule_prov_b]
        )

        def _rule_with_prov_a_impl(ctx):
          return [rule_prov_a()]

        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a]
        )

        def _rule_with_prov_b_impl(ctx):
          return [rule_prov_b()]

        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_b]
        )

        def _rule_with_prov_c_impl(ctx):
          return [rule_prov_c()]
        rule_with_prov_c = rule(
          implementation = _rule_with_prov_c_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_c]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule', 'rule_with_prov_a',
                                'rule_with_prov_b', 'rule_with_prov_c')
        my_rule(
          name = 'main_target',
          deps = [':dep_target_with_prov_a', ':dep_target_with_prov_b']
        )
        rule_with_prov_a(
          name = 'dep_target_with_prov_a',
          deps = [':dep_target_with_prov_c'],
        )
        rule_with_prov_b(
          name = 'dep_target_with_prov_b',
          deps = [':dep_target_with_prov_c'],
        )
        rule_with_prov_c(
          name = 'dep_target_with_prov_c',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        // aspect_a runs on main_target, dep_target_with_prov_a and dep_target_with_prov_c and it can
        // only retrieve aspect_c provider value on dep_target_with_prov_c
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aResult: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "aspect_prov_a"
            )
        val aResultProvider: StructImpl = aspectA.get(aResult) as StructImpl
        Truth.assertThat(aResultProvider.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_target_with_prov_c",
                "aspect_a runs on target @@//test:dep_target_with_prov_c",
                "aspect_a runs on target @@//test:dep_target_with_prov_a",
                "aspect_a runs on target @@//test:main_target"
            )

        // aspect_b runs on main_target, dep_target_with_prov_b and dep_target_with_prov_c and it can
        // only retrieve aspect_c provider value on dep_target_with_prov_c
        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectA).isNotNull()
        val bResult: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "aspect_prov_b"
            )
        val bResultProvider: StructImpl = aspectB.get(bResult) as StructImpl
        Truth.assertThat(bResultProvider.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_target_with_prov_c",
                "aspect_b runs on target @@//test:dep_target_with_prov_c",
                "aspect_b runs on target @@//test:dep_target_with_prov_b",
                "aspect_b runs on target @@//test:main_target"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiredByMultipleAspects_withDifferentParametersValues() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        prov_c = provider()

        def _aspect_c_impl(target, ctx):
          res = ['aspect_c runs on target {} and param = {}'.format(target.label, ctx.attr.p)]
          return [prov_c(val = res)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          attrs = {
            'p': attr.string(values=['rule_1_val', 'rule_2_val']),
          },
        )

        def _aspect_b_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_b runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep:
            res += ctx.rule.attr.dep[prov_b].val
          return [prov_b(val = res)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          requires = [aspect_c],
        )

        def _aspect_a_impl(target, ctx):
          res = []
          res += target[prov_c].val
          res += ['aspect_a runs on target {}'.format(target.label)]
          if ctx.rule.attr.dep:
            res += ctx.rule.attr.dep[prov_a].val
          return [prov_a(val = res)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          requires = [aspect_c],
        )

        def _rule_1_impl(ctx):
          return ctx.attr.dep[prov_a]

        rule_1 = rule(
          implementation = _rule_1_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p': attr.string(values = ['rule_1_val', 'rule_2_val'])
          },
        )

        def _rule_2_impl(ctx):
          return ctx.attr.dep[prov_b]

        rule_2 = rule(
          implementation = _rule_2_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_b]),
            'p': attr.string(values = ['rule_1_val', 'rule_2_val'])
          },
        )

        def _rule_3_impl(ctx):
          pass

        rule_3 = rule(
          implementation = _rule_3_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_1', 'rule_2', 'rule_3')
        rule_1(
          name = 'target_1',
          dep = ':dep_1',
          p = 'rule_1_val'
        )
        rule_2(
          name = 'target_2',
          dep = ':dep_2',
          p = 'rule_2_val'
        )
        rule_3(
          name = 'dep_1',
          dep = ':dep_3',
        )
        rule_3(
          name = 'dep_2',
          dep = ':dep_3',
        )
        rule_3(
          name = 'dep_3',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:target_1", "//test:target_2")

        val it: MutableIterator<ConfiguredTarget> = analysisResult.getTargetsToBuild().iterator()
        // aspect_a runs on dep_1 and dep_3 and it can retrieve aspect_c provider value on them
        // aspect_c here should get its parameter value from rule_2
        val target1: ConfiguredTarget = it.next()
        val provAkey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a")
        val provA: StructImpl = target1.get(provAkey) as StructImpl
        Truth.assertThat(provA.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_1 and param = rule_1_val",
                "aspect_a runs on target @@//test:dep_1",
                "aspect_c runs on target @@//test:dep_3 and param = rule_1_val",
                "aspect_a runs on target @@//test:dep_3"
            )

        // aspect_b runs on dep_2 and dep_3 and it can retrieve aspect_c provider value on them.
        // aspect_c here should get its parameter value from rule_2
        val target2: ConfiguredTarget = it.next()
        val provBkey: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_b")
        val provB: StructImpl = target2.get(provBkey) as StructImpl
        Truth.assertThat(provB.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c runs on target @@//test:dep_2 and param = rule_2_val",
                "aspect_b runs on target @@//test:dep_2",
                "aspect_c runs on target @@//test:dep_3 and param = rule_2_val",
                "aspect_b runs on target @@//test:dep_3"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_requireNativeAspect() {
        exposeNativeAspectToStarlark()
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        def _impl(target, ctx):
          res = 'aspect_a on target {} '.format(target.label)
          if hasattr(target, 'native_aspect_prov'):
            res += 'can see native aspect provider'
          else:
            res += 'cannot see native aspect provider'
          complete_res = [res]
          if hasattr(ctx.rule.attr, 'dep'):
            complete_res += ctx.rule.attr.dep[prov_a].val
          return [prov_a(val = complete_res)]
        aspect_a = aspect(implementation = _impl,
                          requires = [starlark_native_aspect],
                          attr_aspects = ['dep'],)

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(implementation = _my_rule_impl,
                       attrs = {'dep': attr.label()})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_1',
        )
        my_rule(
          name = 'dep_1',
          dep = ':dep_2',
        )
        honest(
          name = 'dep_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a"), "//test:main_target")

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        // aspect_a runs on main_target, dep_1 and dep_2 but it can only see the required native aspect
        // run on dep_2 because its rule satisfies its required provider.
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aResult: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "prov_a")
        val aResultProvider: StructImpl = aspectA.get(aResult) as StructImpl
        Truth.assertThat(aResultProvider.getValue("val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_a on target @@//test:main_target cannot see native aspect provider",
                "aspect_a on target @@//test:dep_1 cannot see native aspect provider",
                "aspect_a on target @@//test:dep_2 can see native aspect provider"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_aspectsParameters() {
        scratch.file(
            "test/defs.bzl",
            """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p1_val = 'In required_aspect, p1 = {} on target {}'.format(ctx.attr.p1, target.label)
          p2_val = 'invalid value'
          if not hasattr(ctx.attr, 'p2'):
            p2_val = 'In required_aspect, p2 not found on target {}'.format(target.label)
          return [RequiredAspectProv(p1_val = p1_val, p2_val = p2_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
          attr_aspects = ['dep'],
          attrs = {'p1' : attr.string(values = ['p1_v1', 'p1_v2'])}
        )

        def _base_aspect_impl(target, ctx):
          p2_val = 'In base_aspect, p2 = {} on target {}'.format(ctx.attr.p2, target.label)
          p1_val = 'invalid value'
          if not hasattr(ctx.attr, 'p1'):
            p1_val = 'In base_aspect, p1 not found on target {}'.format(target.label)
          return [BaseAspectProv(p1_val = p1_val, p2_val = p2_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          attrs = {'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
          requires = [required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[RequiredAspectProv], ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
            'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
            'p2' : attr.string(values = ['p2_v1', 'p2_v2'])
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
          p1 = 'p1_v1',
          p2 = 'p2_v1'
        )
        dep_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // Both base_aspect and required_aspect can get their parameters values from the base rule
        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val requiredAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv"
            )
        val requiredAspectProvider: StructImpl = configuredTarget.get(requiredAspectProv) as StructImpl
        assertThat(requiredAspectProvider.getValue("p1_val"))
            .isEqualTo("In required_aspect, p1 = p1_v1 on target @@//test:dep_target")
        assertThat(requiredAspectProvider.getValue("p2_val"))
            .isEqualTo("In required_aspect, p2 not found on target @@//test:dep_target")

        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv"
            )
        val baseAspectProvider: StructImpl = configuredTarget.get(baseAspectProv) as StructImpl
        assertThat(baseAspectProvider.getValue("p1_val"))
            .isEqualTo("In base_aspect, p1 not found on target @@//test:dep_target")
        assertThat(baseAspectProvider.getValue("p2_val"))
            .isEqualTo("In base_aspect, p2 = p2_v1 on target @@//test:dep_target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_ruleAttributes() {
        scratch.file(
            "test/defs.bzl",
            """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p_val = 'In required_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)
          return [RequiredAspectProv(p_val = p_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          p_val = 'In base_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)
          return [BaseAspectProv(p_val = p_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[RequiredAspectProv], ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
          attrs = {
            'p' : attr.string(values = ['p_v1', 'p_v2']),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
          p = 'p_v2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // Both base_aspect and required_aspect can see the attributes of the rule they run on
        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val requiredAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv"
            )
        val requiredAspectProvider: StructImpl = configuredTarget.get(requiredAspectProv) as StructImpl
        assertThat(requiredAspectProvider.getValue("p_val"))
            .isEqualTo("In required_aspect, p = p_v2 on target @@//test:dep_target")

        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv"
            )
        val baseAspectProvider: StructImpl = configuredTarget.get(baseAspectProv) as StructImpl
        assertThat(baseAspectProvider.getValue("p_val"))
            .isEqualTo("In base_aspect, p = p_v2 on target @@//test:dep_target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_inheritPropagationAttributes() {
        // base_aspect propagates over base_dep attribute and requires first_required_aspect which
        // propagates over first_dep attribute and requires second_required aspect which propagates
        // over second_dep attribute
        scratch.file(
            "test/defs.bzl",
            """
        BaseAspectProv = provider()
        FirstRequiredAspectProv = provider()
        SecondRequiredAspectProv = provider()

        def _second_required_aspect_impl(target, ctx):
          result = []
          if getattr(ctx.rule.attr, 'second_dep'):
            result += getattr(ctx.rule.attr, 'second_dep')[SecondRequiredAspectProv].result
          result += ['second_required_aspect run on target {}'.format(target.label)]
          return [SecondRequiredAspectProv(result = result)]
        second_required_aspect = aspect(
          implementation = _second_required_aspect_impl,
          attr_aspects = ['second_dep'],
        )

        def _first_required_aspect_impl(target, ctx):
          result = []
          result += target[SecondRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'first_dep'):
            result += getattr(ctx.rule.attr, 'first_dep')[FirstRequiredAspectProv].result
          result += ['first_required_aspect run on target {}'.format(target.label)]
          return [FirstRequiredAspectProv(result = result)]
        first_required_aspect = aspect(
          implementation = _first_required_aspect_impl,
          attr_aspects = ['first_dep'],
          requires = [second_required_aspect],
        )

        def _base_aspect_impl(target, ctx):
          result = []
          result += target[FirstRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'base_dep'):
            result += getattr(ctx.rule.attr, 'base_dep')[BaseAspectProv].result
          result += ['base_aspect run on target {}'.format(target.label)]
          return [BaseAspectProv(result = result)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['base_dep'],
          requires = [first_required_aspect],
        )

        def _main_rule_impl(ctx):
          return [ctx.attr.dep[BaseAspectProv]]
        def _dep_rule_impl(ctx):
          pass

        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects=[base_aspect]),
          },
        )

        dep_rule = rule(
          implementation = _dep_rule_impl,
          attrs = {
            'base_dep': attr.label(),
            'first_dep': attr.label(),
            'second_dep': attr.label()
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
          base_dep = ':base_dep_target',
          first_dep = ':first_dep_target',
          second_dep = ':second_dep_target',
        )
        dep_rule(
          name = 'base_dep_target',
        )
        dep_rule(
          name = 'first_dep_target',
        )
        dep_rule(
          name = 'second_dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // base_aspect should propagate only along its attr_aspects: 'base_dep'
        // first_required_aspect should propagate along 'base_dep' and 'first_dep'
        // second_required_aspect should propagate along 'base_dep', 'first_dep' and `second_dep`
        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv"
            )
        val baseAspectProvider: StructImpl = configuredTarget.get(baseAspectProv) as StructImpl
        Truth.assertThat(baseAspectProvider.getValue("result") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "second_required_aspect run on target @@//test:second_dep_target",
                "second_required_aspect run on target @@//test:dep_target",
                "second_required_aspect run on target @@//test:first_dep_target",
                "first_required_aspect run on target @@//test:first_dep_target",
                "first_required_aspect run on target @@//test:dep_target",
                "second_required_aspect run on target @@//test:base_dep_target",
                "first_required_aspect run on target @@//test:base_dep_target",
                "base_aspect run on target @@//test:base_dep_target",
                "base_aspect run on target @@//test:dep_target"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_inheritRequiredProviders() {
        // aspect_a requires provider Prov_A and requires aspect_b which requires
        // provider Prov_B and requires aspect_c which requires provider Prov_C
        scratch.file(
            "test/defs.bzl",
            "Prov_A = provider()",
            "Prov_B = provider()",
            "Prov_C = provider()",
            "",
            "CollectorProv = provider()",
            "",
            "def _aspect_c_impl(target, ctx):",
            "  collector_result = ['aspect_c run on target {} and value of Prov_C ="
                    + " {}'.format(target.label, target[Prov_C].val)]",
            "  return [CollectorProv(result = collector_result)]",
            "aspect_c = aspect(",
            "  implementation = _aspect_c_impl,",
            "  required_providers = [Prov_C],",
            "  attr_aspects = ['dep'],",
            ")",
            "",
            "def _aspect_b_impl(target, ctx):",
            "  collector_result = []",
            "  collector_result += ctx.rule.attr.dep[CollectorProv].result",
            "  collector_result += ['aspect_b run on target {} and value of Prov_B ="
                    + " {}'.format(target.label, target[Prov_B].val)]",
            "  return [ CollectorProv(result = collector_result)]",
            "aspect_b = aspect(",
            "  implementation = _aspect_b_impl,",
            "  required_providers = [Prov_B],",
            "  requires = [aspect_c],",
            "  attr_aspects = ['dep'],",
            ")",
            "",
            "def _aspect_a_impl(target, ctx):",
            "  collector_result = []",
            "  collector_result += ctx.rule.attr.dep[CollectorProv].result",
            "  collector_result += ['aspect_a run on target {} and value of Prov_A ="
                    + " {}'.format(target.label, target[Prov_A].val)]",
            "  return [CollectorProv(result = collector_result)]",
            "aspect_a = aspect(",
            "  implementation = _aspect_a_impl,",
            "  attr_aspects = ['dep'],",
            "  required_providers = [Prov_A],",
            "  requires = [aspect_b],",
            ")",
            "",
            "def _main_rule_impl(ctx):",
            "  return [ctx.attr.dep[CollectorProv]]",
            "main_rule = rule(",
            "  implementation = _main_rule_impl,",
            "  attrs = {",
            "    'dep': attr.label(aspects = [aspect_a]),",
            "  },",
            ")",
            "",
            "def _rule_with_prov_a_impl(ctx):",
            "  return [Prov_A(val='val_a')]",
            "rule_with_prov_a = rule(",
            "  implementation = _rule_with_prov_a_impl,",
            "  attrs = {",
            "    'dep': attr.label(),",
            "  },",
            "  provides = [Prov_A]",
            ")",
            "",
            "def _rule_with_prov_b_impl(ctx):",
            "  return [Prov_B(val = 'val_b')]",
            "rule_with_prov_b = rule(",
            "  implementation = _rule_with_prov_b_impl,",
            "  attrs = {",
            "    'dep': attr.label(),",
            "  },",
            "  provides = [Prov_B]",
            ")",
            "",
            "def _rule_with_prov_c_impl(ctx):",
            "  return [Prov_C(val = 'val_c')]",
            "rule_with_prov_c = rule(",
            "  implementation = _rule_with_prov_c_impl,",
            "  provides = [Prov_C]",
            ")"
        )
        scratch.file(
            "test/BUILD",
            "load('//test:defs.bzl', 'main_rule', 'rule_with_prov_a', 'rule_with_prov_b',"
                    + " 'rule_with_prov_c')",
            "main_rule(",
            "  name = 'main',",
            "  dep = ':target_with_prov_a',",
            ")",
            "rule_with_prov_a(",
            "  name = 'target_with_prov_a',",
            "  dep = ':target_with_prov_b'",
            ")",
            "rule_with_prov_b(",
            "  name = 'target_with_prov_b',",
            "  dep = ':target_with_prov_c'",
            ")",
            "rule_with_prov_c(",
            "  name = 'target_with_prov_c'",
            ")"
        )

        val analysisResult: AnalysisResult = update("//test:main")

        // aspect_a should only run on target_with_prov_a, aspect_b should only run on
        // target_with_prov_b and aspect_c should only run on target_with_prov_c.
        // aspect_c will reach target target_with_prov_c because it inherits the required_providers of
        // aspect_b otherwise it would have stopped propagating after target_with_prov_b.
        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val collectorProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "CollectorProv"
            )
        val collectorProvider: StructImpl = configuredTarget.get(collectorProv) as StructImpl
        Truth.assertThat(collectorProvider.getValue("result") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c run on target @@//test:target_with_prov_c and value of Prov_C = val_c",
                "aspect_b run on target @@//test:target_with_prov_b and value of Prov_B = val_b",
                "aspect_a run on target @@//test:target_with_prov_a and value of Prov_A = val_a"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_inspectRequiredAspectActions() {
        scratch.file(
            "test/defs.bzl",
            """
        def _required_aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > ${'$'}(location f)',
                                mnemonic='RequiredAspectAction')
          return []
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        AspectInfo = provider()
        def _base_aspect_impl(target, ctx):
          required_aspect_action = None
          for action in target.actions:
            if action.mnemonic == 'RequiredAspectAction':
              required_aspect_action = action
          if required_aspect_action:
            return AspectInfo(result = 'base_aspect can see required_aspect action')
          else:
            return AspectInfo(result = 'base_aspect cannot see required_aspect action')
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          return RuleInfo(result = ctx.attr.dep[AspectInfo].result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [base_aspect]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val result = getStarlarkProvider(configuredTarget, "RuleInfo").getValue("result") as String?
        Truth.assertThat(result).isEqualTo("base_aspect can see required_aspect action")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_inspectRequiredAspectGeneratedFiles() {
        scratch.file(
            "test/defs.bzl",
            "def _required_aspect_impl(target, ctx):",
            "  file = ctx.actions.declare_file('required_aspect_file')",
            "  ctx.actions.write(file, 'data')",
            "  return [OutputGroupInfo(out = [file])]",
            "required_aspect = aspect(",
            "  implementation = _required_aspect_impl,",
            ")",
            "",
            "AspectInfo = provider()",
            "def _base_aspect_impl(target, ctx):",
            "  files = ['base_aspect can see file ' + f.path.split('/')[-1] for f in"
                    + " target[OutputGroupInfo].out.to_list()]",
            "  return AspectInfo(my_files = files)",
            "base_aspect = aspect(",
            "  implementation = _base_aspect_impl,",
            "  attr_aspects = ['dep'],",
            "  requires = [required_aspect]",
            ")",
            "",
            "RuleInfo = provider()",
            "def _main_rule_impl(ctx):",
            "  return RuleInfo(my_files = ctx.attr.dep[AspectInfo].my_files)",
            "main_rule = rule(",
            "  implementation = _main_rule_impl,",
            "  attrs = {",
            "    'dep': attr.label(aspects = [base_aspect]),",
            "  },",
            ")",
            "",
            "def _dep_rule_impl(ctx):",
            "  pass",
            "dep_rule = rule(",
            "  implementation = _dep_rule_impl,",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val files: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_files") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(files))
            .containsExactly("base_aspect can see file required_aspect_file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_withRequiredAspectProvidersSatisfied() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        prov_b_forwarded = provider()
        AspectAInfo = provider()
        AspectBInfo = provider()
        RuleInfo = provider()
        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
            return [AspectBInfo(aspect_b_result = result),
                    prov_b_forwarded(val = target[prov_b].val)]
          else:
            result += 'cannot find prov_b'
            return [AspectBInfo(aspect_b_result = result)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          if prov_b_forwarded in target:
            result += ' and found prov_b = {}'.format(target[prov_b_forwarded].val)
          else:
            result += ' but cannot find prov_b'
          return AspectAInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _aspect_with_prov_b_impl(target, ctx):
          return [prov_b(val = 'b1')]
        aspect_with_prov_b = aspect(
          implementation = _aspect_with_prov_b_impl,
          provides = [prov_b],
          attr_aspects = ['dep'],
        )

        def _main_rule_impl(ctx):
          return RuleInfo(aspect_a_result = ctx.attr.dep[AspectAInfo].aspect_a_result,
                        aspect_b_result = ctx.attr.dep[AspectBInfo].aspect_b_result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_with_prov_a, aspect_with_prov_b, aspect_a]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val targetInfo: StarlarkInfo = getStarlarkProvider(configuredTarget, "RuleInfo")
        val aspectAResult: String? = targetInfo.getValue("aspect_a_result", String::class.java)
        Truth.assertThat(aspectAResult)
            .isEqualTo(
                "aspect_a on target @@//test:dep_target found prov_a = a1 and found prov_b = b1"
            )

        val aspectBResult: String? = targetInfo.getValue("aspect_b_result", String::class.java)
        Truth.assertThat(aspectBResult).isEqualTo("aspect_b on target @@//test:dep_target found prov_b = b1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRequiresAspect_withRequiredAspectProvidersNotFound() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        AspectAInfo = provider()
        AspectBInfo = provider()
        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
          else:
            result += 'cannot find prov_b'
          return AspectBInfo(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          return AspectAInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          return RuleInfo(aspect_a_result = ctx.attr.dep[AspectAInfo].aspect_a_result,
                        aspect_b_result = ctx.attr.dep[AspectBInfo].aspect_b_result)
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_with_prov_a, aspect_a]),
          },
        )

        def _dep_rule_impl(ctx):
          pass
        dep_rule = rule(
          implementation = _dep_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'dep_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        dep_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleInfo: StarlarkInfo = getStarlarkProvider(configuredTarget, "RuleInfo")
        val aspectAResult: String? = ruleInfo.getValue("aspect_a_result", String::class.java)
        Truth.assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target found prov_a = a1")

        val aspectBResult: String? = ruleInfo.getValue("aspect_b_result", String::class.java)
        Truth.assertThat(aspectBResult)
            .isEqualTo("aspect_b on target @@//test:dep_target cannot find prov_b")
    }

    /**
     * --aspects = a3, a2, a1: aspect a1 requires provider a1p, aspect a2 requires provider a2p and
     * provides a1p and aspect a3 provides a2p. The three aspects will propagate together but aspect
     * a1 will only see a1p and aspect a2 will only see a2p.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_stackOfAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a2p = provider()
        a1_result = provider()
        a2_result = provider()
        a3_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a2_result].value + [result]
          else:
            complete_result = [result]
          return [a2_result(value = complete_result), a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
          required_aspect_providers = [a2p],
        )

        def _a3_impl(target, ctx):
          result = 'aspect a3 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a3_result].value + [result]
          else:
            complete_result = [result]
          return [a3_result(value = complete_result), a2p(value = 'a2p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a2p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a3",
                    "test/defs.bzl%a2",
                    "test/defs.bzl%a1"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a3: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a3")
        assertThat(a3).isNotNull()
        val a3Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a3_result")
        val a3ResultProvider: StructImpl = a3.get(a3Result) as StructImpl
        Truth.assertThat(a3ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a3 on target @@//test:dep_target cannot see a1p and cannot see a2p",
                "aspect a3 on target @@//test:main cannot see a1p and cannot see a2p"
            )

        val a2: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a2")
        assertThat(a2).isNotNull()
        val a2Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result")
        val a2ResultProvider: StructImpl = a2.get(a2Result) as StructImpl
        Truth.assertThat(a2ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a2 on target @@//test:dep_target cannot see a1p and sees a2p = a2p_val",
                "aspect a2 on target @@//test:main cannot see a1p and sees a2p = a2p_val"
            )

        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a1p = a1p_val and cannot see a2p",
                "aspect a1 on target @@//test:main sees a1p = a1p_val and cannot see a2p"
            )
    }

    /**
     * --aspects = a3, a2, a1: aspect a1 requires provider a1p, aspect a2 and aspect a3 provides a1p.
     * This should fail because provider a1p is provided twice.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_requiredProviderProvidedTwiceFailed() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_a2_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _a3_impl(target, ctx):
          return [a1p(value = 'a1p_a3_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // The call to `update` does not throw an exception when "--keep_going" is passed in the
        // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
        if (keepGoing()) {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "test/defs.bzl%a3",
                        "test/defs.bzl%a2",
                        "test/defs.bzl%a1"
                    ),
                    "//test:main"
                )
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "test/defs.bzl%a3",
                            "test/defs.bzl%a2",
                            "test/defs.bzl%a1"
                        ),
                        "//test:main"
                    )
                })
        }
        assertContainsEvent("ERROR /workspace/test/BUILD:2:12: Provider a1p provided twice")
    }

    /**
     * --aspects = a3, a1, a2: aspect a1 requires provider a1p, aspect a2 and aspect a3 provide a1p.
     * a1 should see the value provided by a3 because a3 is listed before a1.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_requiredProviderProvidedTwicePassed() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_a2_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _a3_impl(target, ctx):
          return [a1p(value = 'a1p_a3_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a3",
                    "test/defs.bzl%a1",
                    "test/defs.bzl%a2"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a1p = a1p_a3_val",
                "aspect a1 on target @@//test:main sees a1p = a1p_a3_val"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_requiredProviderNotProvided() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a2p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a2", "test/defs.bzl%a1"),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target cannot see a1p",
                "aspect a1 on target @@//test:main cannot see a1p"
            )
    }

    /**
     * --aspects = a1, a2: aspect a1 requires provider a1p, aspect a2 provides a1p but it was listed
     * after a1 so aspect a1 cannot see a1p value.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_requiredProviderProvidedAfterTheAspect() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a1", "test/defs.bzl%a2"),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target cannot see a1p",
                "aspect a1 on target @@//test:main cannot see a1p"
            )
    }

    /**
     * --aspects = a2, a1: aspect a1 requires provider a1p, aspect a2 provides a1p. But aspect a2
     * propagates along different attr_aspects from a1 so a1 cannot get a1p on all dependency targets.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_differentAttrAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result += ctx.rule.attr.dep[a1_result].value
          if ctx.rule.attr.extra_dep:
            complete_result += ctx.rule.attr.extra_dep[a1_result].value
          complete_result += [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep', 'extra_dep'],
          required_aspect_providers = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          provides = [a1p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
            'extra_dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
          extra_dep = ':extra_dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        simple_rule(
          name = 'extra_dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a2", "test/defs.bzl%a1"),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a1p = a1p_val",
                "aspect a1 on target @@//test:extra_dep_target cannot see a1p",
                "aspect a1 on target @@//test:main sees a1p = a1p_val"
            )
    }

    /**
     * --aspects = a2, a1: aspect a1 requires provider a1p, aspect a2 provides a1p. But aspect a2
     * propagates along different required_providers from a1 so a1 cannot get a1p on all dependency
     * targets.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_differentRequiredRuleProviders() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a1_result = provider()
        rule_prov_a = provider()
        rule_prov_b = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          complete_result = []
          if hasattr(ctx.rule.attr, 'deps'):
            for dep in ctx.rule.attr.deps:
              complete_result += dep[a1_result].value
          complete_result += [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a1p],
          required_providers = [[rule_prov_a], [rule_prov_b]],
        )

        def _a2_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a1p],
          required_providers = [rule_prov_a],
        )

        def _main_rule_impl(ctx):
          return [rule_prov_a(), rule_prov_b()]
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [rule_prov_a, rule_prov_b],
        )

        def _rule_with_prov_a_impl(ctx):
          return [rule_prov_a()]
        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          provides = [rule_prov_a]
        )

        def _rule_with_prov_b_impl(ctx):
          return [rule_prov_b()]
        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          provides = [rule_prov_b]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule', 'rule_with_prov_a', 'rule_with_prov_b')
        main_rule(
          name = 'main',
          deps = [':target_with_prov_a', ':target_with_prov_b'],
        )
        rule_with_prov_a(
          name = 'target_with_prov_a',
        )
        rule_with_prov_b(
          name = 'target_with_prov_b',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a2", "test/defs.bzl%a1"),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:target_with_prov_a sees a1p = a1p_val",
                "aspect a1 on target @@//test:target_with_prov_b cannot see a1p",
                "aspect a1 on target @@//test:main sees a1p = a1p_val"
            )
    }

    /**
     * --aspects = a3, a2, a1: both aspects a1 and a2 require provider a3p, aspect a3 provides a3p. a1
     * and a2 should be able to read a3p.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_providerRequiredByMultipleAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        a3p = provider()
        a1_result = provider()
        a2_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a1_result].value + [result]
          else:
            complete_result = [result]
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a3p]
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.dep:
            complete_result = ctx.rule.attr.dep[a2_result].value + [result]
          else:
            complete_result = [result]
          return [a2_result(value = complete_result)]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['dep'],
          required_aspect_providers = [a3p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['dep'],
          provides = [a3p],
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          dep = ':dep_target',
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a3",
                    "test/defs.bzl%a2",
                    "test/defs.bzl%a1"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a2: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a2")
        assertThat(a2).isNotNull()
        val a2Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result")
        val a2ResultProvider: StructImpl = a2.get(a2Result) as StructImpl
        Truth.assertThat(a2ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a2 on target @@//test:dep_target sees a3p = a3p_val",
                "aspect a2 on target @@//test:main sees a3p = a3p_val"
            )

        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a3p = a3p_val",
                "aspect a1 on target @@//test:main sees a3p = a3p_val"
            )
    }

    /**
     * --aspects = a1, a2, a3: aspect a3 requires a1p and a2p, a1 provides a1p and a2 provides a2p.
     * 
     * 
     * top level target (main) has two dependencies t1 and t2. Aspects a1 and a3 can propagate to
     * t1 and aspects a2 and a3 can propagate to t2. Both t1 and t2 have t0 as dependency, aspect a3
     * will run twice on t0 once with aspects path (a1, a3) and the other with (a2, a3).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_diamondCase() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a2p = provider()
        a3_result = provider()

        r1p = provider()
        r2p = provider()

        def _a1_impl(target, ctx):
          return [a1p(value = 'a1p_val')]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_providers = [r1p],
          provides = [a1p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          required_providers = [r2p],
          provides = [a2p]
        )

        def _a3_impl(target, ctx):
          result = 'aspect a3 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          if a2p in target:
            result += ' and sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' and cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a3_result].value)
          complete_result.append(result)
          return [a3_result(value = complete_result)]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a1p], [a2p]],
        )

        def _r0_impl(ctx):
          return [r1p(), r2p()]
        r0 = rule(
          implementation = _r0_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r1p, r2p]
        )
        def _r1_impl(ctx):
          return [r1p()]
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r1p]
        )
        def _r2_impl(ctx):
          return [r2p()]
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(),
          },
          provides = [r2p]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r0', 'r1', 'r2')
        r0(
          name = 'main',
          deps = [':t1', ':t2'],
        )
        r1(
          name = 't1',
          deps = [':t0'],
        )
        r2(
          name = 't2',
          deps = [':t0'],
        )
        r0(
          name = 't0',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a1",
                    "test/defs.bzl%a2",
                    "test/defs.bzl%a3"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a3: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a3")
        assertThat(a3).isNotNull()
        val a3Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a3_result")
        val a3ResultProvider: StructImpl = a3.get(a3Result) as StructImpl
        Truth.assertThat(a3ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a3 on target @@//test:t0 sees a1p = a1p_val and cannot see a2p",
                "aspect a3 on target @@//test:t0 cannot see a1p and sees a2p = a2p_val",
                "aspect a3 on target @@//test:t1 sees a1p = a1p_val and cannot see a2p",
                "aspect a3 on target @@//test:t2 cannot see a1p and sees a2p = a2p_val",
                "aspect a3 on target @@//test:main sees a1p = a1p_val and sees a2p = a2p_val"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_duplicateAspectsNotAllowed() {
        scratch.file(
            "test/defs.bzl",
            """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // The call to `update` does not throw an exception when "--keep_going" is passed in the
        // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
        if (keepGoing()) {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "test/defs.bzl%a1",
                        "test/defs.bzl%a2",
                        "test/defs.bzl%a1"
                    ),
                    "//test:main"
                )
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "test/defs.bzl%a1",
                            "test/defs.bzl%a2",
                            "test/defs.bzl%a1"
                        ),
                        "//test:main"
                    )
                })
        }
        assertContainsEvent("aspect //test:defs.bzl%a1 added more than once")
    }

    /**
     * --aspects = a1 requires provider a2p provided by aspect a2. a1 is applied on top level target
     * `main` whose rule propagates aspect a2 to its `deps`. So a1 on `main` cannot see a2p but it can
     * see a2p on `main` deps.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_requiredAspectProviderOnlyAvailableOnDep() {
        scratch.file(
            "test/defs.bzl",
            """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p]
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(aspects=[a2]),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a1"), "//test:main")

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a2p = a2p_val",
                "aspect a1 on target @@//test:main cannot see a2p"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_multipleTopLevelTargets() {
        scratch.file(
            "test/defs.bzl",
            """
        a2p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [a2p],
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 't1',
        )
        simple_rule(
          name = 't2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a2", "test/defs.bzl%a1"),
                "//test:t2",
                "//test:t1"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1Ont1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1", "t1")
        assertThat(a1Ont1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        var a1ResultProvider: StructImpl = a1Ont1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly("aspect a1 on target @@//test:t1 sees a2p = a2p_val")

        val a1Ont2: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1", "t2")
        assertThat(a1Ont2).isNotNull()
        a1ResultProvider = a1Ont2.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly("aspect a1 on target @@//test:t2 sees a2p = a2p_val")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_multipleRequiredProviders() {
        scratch.file(
            "test/defs.bzl",
            """
        a2p = provider()
        a3p = provider()
        a1_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          if a3p in target:
            result += ' and sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' and cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a2p], [a3p]],
        )

        def _a2_impl(target, ctx):
          return [a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          provides = [a3p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a3",
                    "test/defs.bzl%a2",
                    "test/defs.bzl%a1"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a2p = a2p_val and sees a3p = a3p_val",
                "aspect a1 on target @@//test:main sees a2p = a2p_val and sees a3p = a3p_val"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectOnAspect_multipleRequiredProviders2() {
        scratch.file(
            "test/defs.bzl",
            """
        a2p = provider()
        a3p = provider()
        a1_result = provider()
        a2_result = provider()

        def _a1_impl(target, ctx):
          result = 'aspect a1 on target {}'.format(target.label)
          if a2p in target:
            result += ' sees a2p = {}'.format(target[a2p].value)
          else:
            result += ' cannot see a2p'
          if a3p in target:
            result += ' and sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' and cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a1_result].value)
          complete_result.append(result)
          return [a1_result(value = complete_result)]
        a1 = aspect(
          implementation = _a1_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[a2p], [a3p]],
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a3p in target:
            result += ' sees a3p = {}'.format(target[a3p].value)
          else:
            result += ' cannot see a3p'
          complete_result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              complete_result.extend(dep[a2_result].value)
          complete_result.append(result)
          return [a2_result(value = complete_result), a2p(value = 'a2p_val')]
        a2 = aspect(
          implementation = _a2_impl,
          attr_aspects = ['deps'],
          provides = [a2p],
          required_aspect_providers = [a3p]
        )

        def _a3_impl(target, ctx):
          return [a3p(value = 'a3p_val')]
        a3 = aspect(
          implementation = _a3_impl,
          attr_aspects = ['deps'],
          provides = [a3p]
        )

        def _simple_rule_impl(ctx):
          pass
        simple_rule = rule(
          implementation = _simple_rule_impl,
          attrs = {
            'deps': attr.label_list(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'simple_rule')
        simple_rule(
          name = 'main',
          deps = [':dep_target'],
        )
        simple_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%a3",
                    "test/defs.bzl%a2",
                    "test/defs.bzl%a1"
                ),
                "//test:main"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val a1: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a1")
        assertThat(a1).isNotNull()
        val a1Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a1_result")
        val a1ResultProvider: StructImpl = a1.get(a1Result) as StructImpl
        Truth.assertThat(a1ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a1 on target @@//test:dep_target sees a2p = a2p_val and sees a3p = a3p_val",
                "aspect a1 on target @@//test:main sees a2p = a2p_val and sees a3p = a3p_val"
            )

        val a2: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a2")
        assertThat(a2).isNotNull()
        val a2Result: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "a2_result")
        val a2ResultProvider: StructImpl = a2.get(a2Result) as StructImpl
        Truth.assertThat(a2ResultProvider.getValue("value") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect a2 on target @@//test:dep_target sees a3p = a3p_val",
                "aspect a2 on target @@//test:main sees a3p = a3p_val"
            )
    }

    /**
     * aspects = a1, a2; aspect a1 provides a1p provider and aspect a2 requires a1p provider. These
     * top-level aspects are applied on top-level target `main` whose rule also provides a1p.
     * 
     * 
     * By default, the dependency between a1 and a2 will be established, the build will fail since
     * a2 will receive provider a1p twice (from a1 applied on `main` and from `main` target itself).
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspects_duplicateRuleProviderError() {
        scratch.file(
            "test/defs.bzl",
            """
        a1p = provider()
        a2p = provider()

        def _a1_impl(target, ctx):
          return [a1p(value = 'aspect_a1p_val')]
        a1 = aspect(
          implementation = _a1_impl,
          provides = [a1p],
        )

        def _a2_impl(target, ctx):
          result = 'aspect a2 on target {}'.format(target.label)
          if a1p in target:
            result += ' sees a1p = {}'.format(target[a1p].value)
          else:
            result += ' cannot see a1p'
          return [a2p(value = result)]
        a2 = aspect(
          implementation = _a2_impl,
          provides = [a2p],
          required_aspect_providers = [a1p]
        )

        def _my_rule_impl(ctx):
          return [a1p(value = 'rule_a1p_val')]
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(name = 'main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // The call to `update` does not throw an exception when "--keep_going" is passed in the
        // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
        if (keepGoing()) {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%a1", "test/defs.bzl%a2"),
                    "//test:main"
                )
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "test/defs.bzl%a1",
                            "test/defs.bzl%a2"
                        ), "//test:main"
                    )
                })
        }
        assertContainsEvent("Provider a1p provided twice")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_stackOfRequiredAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           return []
        aspect_c = aspect(implementation = _impl)
        aspect_b = aspect(implementation = _impl, requires = [aspect_c])
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'main_target')"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a"), "//test:main_target")

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?>? = analysisResult.getAspectsMap()
        Truth.assertThat(configuredAspects).hasSize(3)
        assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_aspectRequiredByMultipleAspects() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           return []
        aspect_c = aspect(implementation = _impl)
        aspect_b = aspect(implementation = _impl, requires = [aspect_c])
        aspect_a = aspect(implementation = _impl, requires = [aspect_c])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'main_target')"
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a", "test/defs.bzl%aspect_b"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?>? = analysisResult.getAspectsMap()
        Truth.assertThat(configuredAspects).hasSize(3)
        assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_aspectRequiredByMultipleAspects2() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           return []
        aspect_d = aspect(implementation = _impl)
        aspect_c = aspect(implementation = _impl, requires = [aspect_d])
        aspect_b = aspect(implementation = _impl, requires = [aspect_d])
        aspect_a = aspect(implementation = _impl, requires = [aspect_b, aspect_c])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'main_target')"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a"), "//test:main_target")

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?>? = analysisResult.getAspectsMap()
        Truth.assertThat(configuredAspects).hasSize(4)
        assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_c")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_d")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_requireExistingAspect_passed() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           return []
        aspect_b = aspect(implementation = _impl)
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'main_target')"
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_b", "test/defs.bzl%aspect_a"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?>? = analysisResult.getAspectsMap()
        Truth.assertThat(configuredAspects).hasSize(2)
        assertThat(getConfiguredAspect(configuredAspects, "aspect_a")).isNotNull()
        assertThat(getConfiguredAspect(configuredAspects, "aspect_b")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_requireExistingAspect_failed() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           return []
        aspect_b = aspect(implementation = _impl)
        aspect_a = aspect(implementation = _impl, requires = [aspect_b])
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'main_target')"
        )
        reporter.removeHandler(failFastHandler)

        // The call to `update` does not throw an exception when "--keep_going" is passed in the
        // WithKeepGoing test suite. Otherwise, it throws ViewCreationFailedException.
        if (keepGoing()) {
            val result: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "test/defs.bzl%aspect_a",
                        "test/defs.bzl%aspect_b"
                    ),
                    "//test:main_target"
                )
            assertThat(result.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "test/defs.bzl%aspect_a",
                            "test/defs.bzl%aspect_b"
                        ),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "aspect //test:defs.bzl%aspect_b was added before as a required"
                    + " aspect of aspect //test:defs.bzl%aspect_a"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_ruleAttributes() {
        scratch.file(
            "test/defs.bzl",
            """
        RequiredAspectProv = provider()
        BaseAspectProv = provider()

        def _required_aspect_impl(target, ctx):
          p_val = ['In required_aspect, p = {} on target {}'
                      .format(ctx.rule.attr.p, target.label)]
          if ctx.rule.attr.dep and RequiredAspectProv in ctx.rule.attr.dep:
            p_val += ctx.rule.attr.dep[RequiredAspectProv].p_val
          return [RequiredAspectProv(p_val = p_val)]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          p_val = []
          p_val += target[RequiredAspectProv].p_val
          p_val += ['In base_aspect, p = {} on target {}'.format(ctx.rule.attr.p, target.label)]
          if ctx.rule.attr.dep:
            p_val += ctx.rule.attr.dep[BaseAspectProv].p_val
          return [BaseAspectProv(p_val = p_val)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect],
        )

        def _rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
            'p' : attr.string(values = ['main_val', 'dep_val']),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target',
          p = 'main_val',
        )
        my_rule(
          name = 'dep_target',
          p = 'dep_val',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%base_aspect"),
                "//test:main_target"
            )

        // required_aspect can only run on main_target when propagated alone since its attr_aspects is
        // empty.
        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val requiredAspect: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "required_aspect")
        assertThat(requiredAspect).isNotNull()
        val requiredAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "RequiredAspectProv"
            )
        val requiredAspectProvider: StructImpl = requiredAspect.get(requiredAspectProv) as StructImpl
        Truth.assertThat(requiredAspectProvider.getValue("p_val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly("In required_aspect, p = main_val on target @@//test:main_target")

        // base_aspect can run on main_target and dep_target and it can also see the providers created
        // by running required_target on them.
        val baseAspect: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "base_aspect")
        assertThat(baseAspect).isNotNull()
        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv"
            )
        val baseAspectProvider: StructImpl = baseAspect.get(baseAspectProv) as StructImpl
        Truth.assertThat(baseAspectProvider.getValue("p_val") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "In base_aspect, p = dep_val on target @@//test:dep_target",
                "In base_aspect, p = main_val on target @@//test:main_target",
                "In required_aspect, p = dep_val on target @@//test:dep_target",
                "In required_aspect, p = main_val on target @@//test:main_target"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_inheritPropagationAttributes() {
        // base_aspect propagates over base_dep attribute and requires first_required_aspect which
        // propagates over first_dep attribute and requires second_required_aspect which propagates over
        // second_dep attribute
        scratch.file(
            "test/defs.bzl",
            """
        BaseAspectProv = provider()
        FirstRequiredAspectProv = provider()
        SecondRequiredAspectProv = provider()

        def _second_required_aspect_impl(target, ctx):
          result = []
          if getattr(ctx.rule.attr, 'second_dep'):
            result += getattr(ctx.rule.attr, 'second_dep')[SecondRequiredAspectProv].result
          result += ['second_required_aspect run on target {}'.format(target.label)]
          return [SecondRequiredAspectProv(result = result)]
        second_required_aspect = aspect(
          implementation = _second_required_aspect_impl,
          attr_aspects = ['second_dep'],
        )

        def _first_required_aspect_impl(target, ctx):
          result = []
          result += target[SecondRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'first_dep'):
            result += getattr(ctx.rule.attr, 'first_dep')[FirstRequiredAspectProv].result
          result += ['first_required_aspect run on target {}'.format(target.label)]
          return [FirstRequiredAspectProv(result = result)]
        first_required_aspect = aspect(
          implementation = _first_required_aspect_impl,
          attr_aspects = ['first_dep'],
          requires = [second_required_aspect],
        )

        def _base_aspect_impl(target, ctx):
          result = []
          result += target[FirstRequiredAspectProv].result
          if getattr(ctx.rule.attr, 'base_dep'):
            result += getattr(ctx.rule.attr, 'base_dep')[BaseAspectProv].result
          result += ['base_aspect run on target {}'.format(target.label)]
          return [BaseAspectProv(result = result)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['base_dep'],
          requires = [first_required_aspect],
        )

        def _my_rule_impl(ctx):
          pass

        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'base_dep': attr.label(),
            'first_dep': attr.label(),
            'second_dep': attr.label()
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          base_dep = ':base_dep_target',
          first_dep = ':first_dep_target',
          second_dep = ':second_dep_target',
        )
        my_rule(
          name = 'base_dep_target',
        )
        my_rule(
          name = 'first_dep_target',
        )
        my_rule(
          name = 'second_dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%base_aspect"),
                "//test:main_target"
            )

        // base_aspect should propagate only along its attr_aspects: 'base_dep'
        // first_required_aspect should propagate along 'base_dep' and 'first_dep'
        // second_required_aspect should propagate along 'base_dep', 'first_dep' and 'second_dep'
        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val baseAspect: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "base_aspect")
        assertThat(baseAspect).isNotNull()
        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProv"
            )
        val baseAspectProvider: StructImpl = baseAspect.get(baseAspectProv) as StructImpl
        Truth.assertThat(baseAspectProvider.getValue("result") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "second_required_aspect run on target @@//test:second_dep_target",
                "second_required_aspect run on target @@//test:main_target",
                "second_required_aspect run on target @@//test:first_dep_target",
                "second_required_aspect run on target @@//test:base_dep_target",
                "first_required_aspect run on target @@//test:first_dep_target",
                "first_required_aspect run on target @@//test:main_target",
                "first_required_aspect run on target @@//test:base_dep_target",
                "base_aspect run on target @@//test:base_dep_target",
                "base_aspect run on target @@//test:main_target"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_inheritRequiredProviders() {
        // aspect_a requires provider Prov_A and requires aspect_b which requires
        // provider Prov_B and requires aspect_c which requires provider Prov_C
        scratch.file(
            "test/defs.bzl",
            """
        Prov_A = provider()
        Prov_B = provider()
        Prov_C = provider()

        CollectorProv = provider()

        def _aspect_c_impl(target, ctx):
          collector_result = ['aspect_c run on target {} and value of Prov_C = {}'
                                        .format(target.label, target[Prov_C].val)]
          return [CollectorProv(result = collector_result)]
        aspect_c = aspect(
          implementation = _aspect_c_impl,
          required_providers = [Prov_C],
          attr_aspects = ['dep'],
        )

        def _aspect_b_impl(target, ctx):
          collector_result = []
          collector_result += ctx.rule.attr.dep[CollectorProv].result
          collector_result += ['aspect_b run on target {} and value of Prov_B = {}'
                                         .format(target.label, target[Prov_B].val)]
          return [CollectorProv(result = collector_result)]
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_providers = [Prov_B],
          requires = [aspect_c],
          attr_aspects = ['dep'],
        )

        def _aspect_a_impl(target, ctx):
          collector_result = []
          collector_result += ctx.rule.attr.dep[CollectorProv].result
          collector_result += ['aspect_a run on target {} and value of Prov_A = {}'
                                         .format(target.label, target[Prov_A].val)]
          return [CollectorProv(result = collector_result)]
        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          required_providers = [Prov_A],
          requires = [aspect_b],
        )

        def _my_rule_impl(ctx):
          return [Prov_A(val='main_val_a')]
        my_rule = rule(
          implementation = _my_rule_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_A]
        )

        def _rule_with_prov_a_impl(ctx):
          return [Prov_A(val='val_a')]
        rule_with_prov_a = rule(
          implementation = _rule_with_prov_a_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_A]
        )

        def _rule_with_prov_b_impl(ctx):
          return [Prov_B(val = 'val_b')]
        rule_with_prov_b = rule(
          implementation = _rule_with_prov_b_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [Prov_B]
        )

        def _rule_with_prov_c_impl(ctx):
          return [Prov_C(val = 'val_c')]
        rule_with_prov_c = rule(
          implementation = _rule_with_prov_c_impl,
          provides = [Prov_C]
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('//test:defs.bzl', 'my_rule', 'rule_with_prov_a', 'rule_with_prov_b',"
                    + " 'rule_with_prov_c')",
            "my_rule(",
            "  name = 'main_target',",
            "  dep = ':target_with_prov_a',",
            ")",
            "rule_with_prov_a(",
            "  name = 'target_with_prov_a',",
            "  dep = ':target_with_prov_b'",
            ")",
            "rule_with_prov_b(",
            "  name = 'target_with_prov_b',",
            "  dep = ':target_with_prov_c'",
            ")",
            "rule_with_prov_c(",
            "  name = 'target_with_prov_c'",
            ")"
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%aspect_a"), "//test:main_target")

        // aspect_a should run on main_target and target_with_prov_a
        // aspect_b can reach target_with_prov_b because it inherits the required_providers of aspect_a
        // aspect_c can reach target_with_prov_c because it inherits the required_providers of aspect_a
        // and aspect_b
        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val collectorProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "CollectorProv"
            )
        val collectorProvider: StructImpl = aspectA.get(collectorProv) as StructImpl
        Truth.assertThat(collectorProvider.getValue("result") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly(
                "aspect_c run on target @@//test:target_with_prov_c and value of Prov_C = val_c",
                "aspect_b run on target @@//test:target_with_prov_b and value of Prov_B = val_b",
                "aspect_a run on target @@//test:target_with_prov_a and value of Prov_A = val_a",
                "aspect_a run on target @@//test:main_target and value of Prov_A = main_val_a"
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_inspectRequiredAspectActions() {
        scratch.file(
            "test/defs.bzl",
            """
        BaseAspectProvider = provider()
        def _required_aspect_impl(target, ctx):
          f = ctx.actions.declare_file('dummy.txt')
          ctx.actions.run_shell(outputs = [f], command='echo xxx > ${'$'}(location f)',
                                mnemonic='RequiredAspectAction')
          return []
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          required_aspect_action = None
          for action in target.actions:
            if action.mnemonic == 'RequiredAspectAction':
              required_aspect_action = action
          if required_aspect_action:
            return [BaseAspectProvider(result = 'base_aspect can see required_aspect action')]
          else:
            return [BaseAspectProvider(result = 'base_aspect cannot see required_aspect action')]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%base_aspect"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val baseAspect: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "base_aspect")
        assertThat(baseAspect).isNotNull()
        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProvider"
            )
        val baseAspectProvider: StructImpl = baseAspect.get(baseAspectProv) as StructImpl
        assertThat(baseAspectProvider.getValue("result"))
            .isEqualTo("base_aspect can see required_aspect action")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_inspectRequiredAspectGeneratedFiles() {
        scratch.file(
            "test/defs.bzl",
            """
        BaseAspectProvider = provider()
        def _required_aspect_impl(target, ctx):
          file = ctx.actions.declare_file('required_aspect_file')
          ctx.actions.write(file, 'data')
          return [OutputGroupInfo(out = [file])]
        required_aspect = aspect(
          implementation = _required_aspect_impl,
        )

        def _base_aspect_impl(target, ctx):
          files = ['base_aspect can see file ' + f.path.split('/')[-1]
                       for f in target[OutputGroupInfo].out.to_list()]
          return [BaseAspectProvider(my_files = files)]
        base_aspect = aspect(
          implementation = _base_aspect_impl,
          attr_aspects = ['dep'],
          requires = [required_aspect]
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/defs.bzl%base_aspect"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val baseAspect: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "base_aspect")
        assertThat(baseAspect).isNotNull()
        val baseAspectProv: StarlarkProvider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BaseAspectProvider"
            )
        val baseAspectProvider: StructImpl = baseAspect.get(baseAspectProv) as StructImpl
        Truth.assertThat(baseAspectProvider.getValue("my_files") as net.starlark.java.eval.Sequence<*>?)
            .containsExactly("base_aspect can see file required_aspect_file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_withRequiredAspectProvidersSatisfied() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        prov_b_forwarded = provider()
        AspectInfo = provider()

        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
            return [
              AspectInfo(aspect_b_result = result),
              prov_b_forwarded(val = target[prov_b].val)
             ]
          else:
            result += 'cannot find prov_b'
            return AspectInfo(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          if prov_b_forwarded in target:
            result += ' and found prov_b = {}'.format(target[prov_b_forwarded].val)
          else:
            result += ' but cannot find prov_b'
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _aspect_with_prov_b_impl(target, ctx):
          return [prov_b(val = 'b1')]
        aspect_with_prov_b = aspect(
          implementation = _aspect_with_prov_b_impl,
          provides = [prov_b],
          attr_aspects = ['dep'],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%aspect_with_prov_a",
                    "test/defs.bzl%aspect_with_prov_b", "test/defs.bzl%aspect_a"
                ),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: String? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", String::class.java)
        Truth.assertThat(aspectAResult)
            .isEqualTo(
                "aspect_a on target @@//test:main_target found prov_a = a1 and found prov_b = b1"
            )

        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectB).isNotNull()
        val aspectBResult: String? =
            getStarlarkProvider(aspectB, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_b_result", String::class.java)
        Truth.assertThat(aspectBResult)
            .isEqualTo("aspect_b on target @@//test:main_target found prov_b = b1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectRequiresAspect_withRequiredAspectProvidersNotFound() {
        scratch.file(
            "test/defs.bzl",
            """
        prov_a = provider()
        prov_b = provider()
        AspectInfo = provider()

        def _aspect_b_impl(target, ctx):
          result = 'aspect_b on target {} '.format(target.label)
          if prov_b in target:
            result += 'found prov_b = {}'.format(target[prov_b].val)
          else:
            result += 'cannot find prov_b'
          return AspectInfo(aspect_b_result = result)
        aspect_b = aspect(
          implementation = _aspect_b_impl,
          required_aspect_providers = [prov_b]
        )

        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {} '.format(target.label)
          if prov_a in target:
            result += 'found prov_a = {}'.format(target[prov_a].val)
          else:
            result += 'cannot find prov_a'
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          required_aspect_providers = [prov_a],
          attr_aspects = ['dep'],
          requires = [aspect_b]
        )

        def _aspect_with_prov_a_impl(target, ctx):
          return [prov_a(val = 'a1')]
        aspect_with_prov_a = aspect(
          implementation = _aspect_with_prov_a_impl,
          provides = [prov_a],
          attr_aspects = ['dep'],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%aspect_with_prov_a",
                    "test/defs.bzl%aspect_a"
                ),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: String? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", String::class.java)
        Truth.assertThat(aspectAResult)
            .isEqualTo("aspect_a on target @@//test:main_target found prov_a = a1")

        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectB).isNotNull()
        val aspectBResult: String? =
            getStarlarkProvider(aspectB, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_b_result", String::class.java)
        Truth.assertThat(aspectBResult)
            .isEqualTo("aspect_b on target @@//test:main_target cannot find prov_b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependentAspectWithNonExecutableTool_doesNotCrash() {
        scratch.file(
            "test/BUILD",
            """
        load('//test_defs:foo_binary.bzl', 'foo_binary')
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_binary(name='bin', srcs=['bin.sh'])
        foo_library(name='lib')
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            "AInfo = provider()",
            "BInfo = provider()",
            "def _aspect_a(target, ctx):",
            "  return [AInfo(value=str(ctx.attr._attr.label))]",
            "aspect_a = aspect(",
            "  implementation = _aspect_a,",
            "  provides=[AInfo],",
            "  attrs = {'_attr':" + " attr.label(default=':lib')},",
            ")",
            "def _aspect_b(target, ctx):",
            "  return [BInfo(value=str(ctx.executable._attr.path.split('/')[-1]))]",
            "aspect_b = aspect(",
            "  implementation = _aspect_b,",
            "  required_aspect_providers = [AInfo],",
            "  attrs = {'_attr': attr.label(default=':bin', executable=True, cfg='exec')},",
            ")"
        )
        scratch.file("test/bin.sh").setExecutable(true)

        val result: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "test/defs.bzl%aspect_a",
                    "test/defs.bzl%aspect_b"
                ), "//test:bin"
            )

        val aspectB: ConfiguredAspect =
            result.getAspectsMap().entrySet().stream()
                .filter({ a -> a.getKey().getAspectName().endsWith("aspect_b") })
                .map({ java.util.Map.Entry.value })
                .findFirst()
                .orElse(null)
        assertThat(aspectB).isNotNull()

        val provB: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "BInfo")
        assertThat((aspectB.get(provB) as StructImpl).getValue("value")).isEqualTo("bin")

        val aspectA: ConfiguredAspect =
            result.getAspectsMap().entrySet().stream()
                .filter({ a -> a.getKey().getAspectName().endsWith("aspect_a") })
                .map({ java.util.Map.Entry.value })
                .findFirst()
                .orElse(null)
        assertThat(aspectA).isNotNull()

        val provA: StarlarkProvider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "AInfo")
        assertThat((aspectA.get(provA) as StructImpl).getValue("value")).isEqualTo("@@//test:lib")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and a_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.a_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'a_p' : attr.string(values = ['a_p_v1', 'a_p_v2'])},
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and b_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.b_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_b_result
          return AspectInfo(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'b_p' : attr.string(values = ['b_p_v1', 'b_p_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>(
                    "//test:defs.bzl%aspect_a",
                    "//test:defs.bzl%aspect_b"
                ),
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "p1",
                    "p1_v1",
                    "a_p",
                    "a_p_v1",
                    "b_p",
                    "b_p_v1"
                ),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p1 = p1_v1 and a_p = a_p_v1",
                "aspect_a on target @@//test:dep_target_1, p1 = p1_v1 and a_p = a_p_v1",
                "aspect_a on target @@//test:dep_target_2, p1 = p1_v1 and a_p = a_p_v1"
            )

        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectB).isNotNull()
        val aspectBResult: StarlarkList<*>? =
            getStarlarkProvider(aspectB, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_b_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectBResult))
            .containsExactly(
                "aspect_b on target @@//test:main_target, p1 = p1_v1 and b_p = b_p_v1",
                "aspect_b on target @@//test:dep_target_1, p1 = p1_v1 and b_p = b_p_v1",
                "aspect_b on target @@//test:dep_target_2, p1 = p1_v1 and b_p = b_p_v1"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_differentAllowedValues() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']) },
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v2', 'p1_v3']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//test:defs.bzl%aspect_a",
                        "//test:defs.bzl%aspect_b"
                    ),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p1", "p1_v1"),
                    "//test:main_target"
                )
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "//test:defs.bzl%aspect_a",
                            "//test:defs.bzl%aspect_b"
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p1", "p1_v1"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "//test:defs.bzl%aspect_b: invalid value in 'p1' attribute: has to be one of 'p1_v2' or"
                    + " 'p1_v3' instead of 'p1_v1'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_useDefaultValue() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2'], default = 'p1_v1'),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'], default = 'p2_v1')},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("p1", "p1_v2"),
                "//test:main_target"
            )

        val configuredAspects: MutableMap<AspectKey?, ConfiguredAspect?> = analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p1 = p1_v2 and p2 = p2_v1",
                "aspect_a on target @@//test:dep_target_1, p1 = p1_v2 and p2 = p2_v1",
                "aspect_a on target @@//test:dep_target_2, p1 = p1_v2 and p2 = p2_v1"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_passParametersToRequiredAspect() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectAInfo = provider()
        AspectBInfo = provider()
        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and p3 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p3)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectBInfo].aspect_b_result
          return AspectBInfo(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'p3' : attr.string(values = ['p3_v1', 'p3_v2', 'p3_v3'])},
        )

        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and p2 = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.p2)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectAInfo].aspect_a_result
          return AspectAInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
          requires = [aspect_b],
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                com.google.common.collect.ImmutableMap.of<String?, String?>(
                    "p1",
                    "p1_v1",
                    "p2",
                    "p2_v2",
                    "p3",
                    "p3_v3"
                ),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectAInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p1 = p1_v1 and p2 = p2_v2",
                "aspect_a on target @@//test:dep_target_1, p1 = p1_v1 and p2 = p2_v2",
                "aspect_a on target @@//test:dep_target_2, p1 = p1_v1 and p2 = p2_v2"
            )

        val aspectB: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_b")
        assertThat(aspectB).isNotNull()
        val aspectBResult: StarlarkList<*>? =
            getStarlarkProvider(aspectB, "//test:defs.bzl", "AspectBInfo")
                .getValue("aspect_b_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectBResult))
            .containsExactly(
                "aspect_b on target @@//test:main_target, p1 = p1_v1 and p3 = p3_v3",
                "aspect_b on target @@//test:dep_target_1, p1 = p1_v1 and p3 = p3_v3",
                "aspect_b on target @@//test:dep_target_2, p1 = p1_v1 and p3 = p3_v3"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_invalidParameterValue() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(values = ['p_v1', 'p_v2']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p", "p_v"),
                    "//test:main_target"
                )
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p", "p_v"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of 'p_v1' or"
                    + " 'p_v2' instead of 'p_v'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_missingMandatoryParameter() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {}'.
                                            format(target.label, ctx.attr.p1)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(mandatory = True, default = 'p1_v1',
                                       values = ['p1_v1', 'p1_v2']),
                    'p2' : attr.string(values = ['p2_v1', 'p2_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p2", "p2_v1"),
                    "//test:main_target"
                )

            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p2", "p2_v1"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent("Missing mandatory attribute 'p1' for aspect '//test:defs.bzl%aspect_a'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_unusedParameter() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p1 = {} and a_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.a_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'a_p' : attr.string(values = ['a_p_v1', 'a_p_v2'])},
        )

        def _aspect_b_impl(target, ctx):
          result = ['aspect_b on target {}, p1 = {} and b_p = {}'.
                                            format(target.label, ctx.attr.p1, ctx.attr.b_p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_b_result
          return struct(aspect_b_result = result)

        aspect_b = aspect(
          implementation = _aspect_b_impl,
          attr_aspects = ['dep'],
          attrs = { 'p1' : attr.string(values = ['p1_v1', 'p1_v2']),
                    'b_p' : attr.string(values = ['b_p_v1', 'b_p_v2'])},
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>(
                        "//test:defs.bzl%aspect_a",
                        "//test:defs.bzl%aspect_b"
                    ),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p2", "p2_v1", "b_p", "b_p_v1"),
                    "//test:main_target"
                )

            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(
                            "//test:defs.bzl%aspect_a",
                            "//test:defs.bzl%aspect_b"
                        ),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p2", "p2_v1", "b_p", "b_p_v1"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "Parameters '[p2]' are not parameters of any of the top-level aspects but they are"
                    + " specified in --aspects_parameters."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_invalidDefaultParameterValue() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(values = ['p_v1', 'p_v2']) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                    "//test:main_target"
                )
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of 'p_v1' or"
                    + " 'p_v2' instead of ''"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_noNeedForAllowedValues() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default='val') },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("p", "p_v"),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p = p_v",
                "aspect_a on target @@//test:dep_target_1, p = p_v",
                "aspect_a on target @@//test:dep_target_2, p = p_v"
            )
    }

    /**
     * Aspect parameter has to require set of values only if the aspect is used in a rule attribute.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrAspectParameterMissingRequiredValues() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           pass
        my_aspect = aspect(_impl,
           attrs = { 'param' : attr.string(default = 'c') }
        )
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs={'dep': attr.label(aspects = [my_aspect])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 'main_target')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult = update("//test:main_target")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:main_target") })
        }
        assertContainsEvent(
            "Aspect //test:defs.bzl%my_aspect: Aspect parameter attribute 'param' must use the 'values'"
                    + " restriction."
        )
    }

    /**
     * Aspect parameter has to require set of values only if the aspect is used in a rule attribute.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttrRequiredAspectParameterMissingRequiredValues() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(target, ctx):
           pass
        required_aspect = aspect(_impl,
           attrs = { 'p1' : attr.string(default = 'b') }
        )
        my_aspect = aspect(_impl,
           attrs = { 'p2' : attr.string(default = 'c', values = ['c']) },
           requires = [required_aspect],
        )
        def _rule_impl(ctx):
           pass
        r1 = rule(_rule_impl, attrs={'dep': attr.label(aspects = [my_aspect])})
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 'main_target')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult = update("//test:main_target")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:main_target") })
        }
        assertContainsEvent(
            "Aspect //test:defs.bzl%required_aspect: Aspect parameter attribute 'p1' must use the"
                    + " 'values' restriction."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_mandatoryAttrNotCoveredByRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2], mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type int."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_mandatoryAttrWithWrongTypeInRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2], mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type int."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_attrWithoutDefaultNotCoveredByRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(values = [1, 2]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type int."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_attrWithoutDefaultWrongTypeInRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(values = [1, 2]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type int."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_missingValuesRestriction() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr' : attr.int() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered: Aspect parameter attribute 'my_attr' must use"
                    + " the 'values' restriction."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun integerAspectParameter_invalidDefault() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.int(default = 2, values = [0, 1]) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect parameter attribute 'my_attr' has a bad default value: has to be one of '0' or '1'"
                    + " instead of '2'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectIntegerParameter_withDefaultValue() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectIntegerParameter_valueOverwrittenByRuleDefault() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.my_aspect_result
          return [AspectInfo(my_aspect_result = result)]

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.int(default = 2) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectIntegerParameter_valueOverwrittenByTargetValue() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.int(default = 2) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
                my_attr = 3,
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = 3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithParameters_invalidIntegerParameterValue() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(values = [1, 2]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p", "3"),
                    "//test:main_target"
                )
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p", "3"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "//test:defs.bzl%aspect_a: invalid value in 'p' attribute: has to be one of '1' or"
                    + " '2' instead of '3'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithIntegerParameter() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(values = [1, 2, 3]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("p", "2"),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p = 2",
                "aspect_a on target @@//test:dep_target_1, p = 2",
                "aspect_a on target @@//test:dep_target_2, p = 2"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithIntegerParameter_useDefaultValue() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.int(default = 1, values = [1, 2, 3]) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p = 1",
                "aspect_a on target @@//test:dep_target_1, p = 1",
                "aspect_a on target @@//test:dep_target_2, p = 1"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun booleanAspectParameter_mandatoryAttrNotCoveredByRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool(default = True, mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type boolean."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun booleanAspectParameter_mandatoryAttrWithWrongTypeInRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool(default = True, mandatory = True) },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type boolean."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun booleanAspectParameter_attrWithoutDefaultNotCoveredByRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type boolean."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun booleanAspectParameter_attrWithoutDefaultWrongTypeInRule() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           return []
        def _rule_impl(ctx):
           return []
        MyAspectUncovered = aspect(
            implementation=_impl,
            attrs = { 'my_attr' : attr.bool() },
        )
        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects=[MyAspectUncovered]),
                      'my_attr': attr.string() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name ='main')
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>(),
                        "//test:main"
                    )
                })
        }

        assertContainsEvent(
            "Aspect //test:aspect.bzl%MyAspectUncovered requires rule my_rule to specify attribute "
                    + "'my_attr' with type boolean."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectBooleanParameter_withDefaultValue() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo")
                .getValue("my_rule_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = True")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectBooleanParameter_valueOverwrittenByRuleDefault() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.bool(default = False) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = False")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectBooleanParameter_valueOverwrittenByTargetValue() {
        scratch.file(
            "test/aspect.bzl",
            """
        AspectInfo = provider()
        def _aspect_impl(target, ctx):
          result = ['my_aspect on target {}, my_attr = {}'.
                                            format(target.label, ctx.attr.my_attr)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].my_aspect_result
          return AspectInfo(my_aspect_result = result)

        RuleInfo = provider()
        def _rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(my_rule_result = ctx.attr.dep[AspectInfo].my_aspect_result)
          pass

        MyAspect = aspect(
            implementation = _aspect_impl,
            attrs = { 'my_attr' : attr.bool(default = True) },
        )

        my_rule = rule(
            implementation=_rule_impl,
            attrs = { 'dep' : attr.label(aspects=[MyAspect]),
                      'my_attr': attr.bool(default = True) }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:aspect.bzl', 'my_rule')
        my_rule(name = 'main_target',
                dep = ':dep_target',
                my_attr = False,
        )
        my_rule(name = 'dep_target')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>(), "//test:main_target")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val ruleResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("my_rule_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(ruleResult))
            .containsExactly("my_aspect on target @@//test:dep_target, my_attr = False")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithBooleanParameter() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool() },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                com.google.common.collect.ImmutableMap.of<String?, String?>("p", "y"),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p = True",
                "aspect_a on target @@//test:dep_target_1, p = True",
                "aspect_a on target @@//test:dep_target_2, p = True"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithBooleanParameter_useDefaultValue() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep[AspectInfo].aspect_a_result
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool(default = False) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                "//test:main_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "aspect_a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result", StarlarkList::class.java)
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect_a on target @@//test:main_target, p = False",
                "aspect_a on target @@//test:dep_target_1, p = False",
                "aspect_a on target @@//test:dep_target_2, p = False"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectsWithBooleanParameter_invalidValue() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.bool() },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label() },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a ViewCreationFailedException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult =
                update(
                    com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                    com.google.common.collect.ImmutableMap.of<String?, String?>("p", "x"),
                    "//test:main_target"
                )
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable {
                    update(
                        com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%aspect_a"),
                        com.google.common.collect.ImmutableMap.of<String?, String?>("p", "x"),
                        "//test:main_target"
                    )
                })
        }
        assertContainsEvent(
            "//test:defs.bzl%aspect_a: expected value of type 'bool' for attribute 'p' but got 'x'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAspectWithMandatoryParameterNotProvided() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v', values = ['p_v'], mandatory = True) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label(aspects = [aspect_a]) },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult = update("//test:main_target")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:main_target") })
        }
        assertContainsEvent(
            "Aspect //test:defs.bzl%aspect_a requires rule my_rule to specify attribute 'p' with type"
                    + " string"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAspectWithMandatoryParameterProvidedWrongType() {
        scratch.file(
            "test/defs.bzl",
            """
        def _aspect_a_impl(target, ctx):
          result = ['aspect_a on target {}, p = {}'.
                                            format(target.label, ctx.attr.p)]
          if ctx.rule.attr.dep:
            result += ctx.rule.attr.dep.aspect_a_result
          return struct(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v', values = ['p_v'], mandatory = True) },
        )

        def _my_rule_impl(ctx):
          pass
        my_rule = rule(
          implementation = _my_rule_impl,
           attrs = { 'dep' : attr.label(aspects = [aspect_a]),
                     'p': attr.int() }
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'my_rule')
        my_rule(
          name = 'main_target',
          dep = ':dep_target_1',
        )
        my_rule(
          name = 'dep_target_1',
          dep = ':dep_target_2',
        )
        my_rule(
          name = 'dep_target_2',
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        // This call succeeds if "--keep_going" was passed, which it does in the WithKeepGoing test
        // suite. Otherwise, it fails and throws a TargetParsingException.
        if (keepGoing()) {
            val analysisResult: AnalysisResult = update("//test:main_target")
            assertThat(analysisResult.hasError()).isTrue()
        } else {
            org.junit.Assert.assertThrows<T?>(
                TargetParsingException::class.java,
                org.junit.function.ThrowingRunnable { update("//test:main_target") })
        }
        assertContainsEvent(
            "Aspect //test:defs.bzl%aspect_a requires rule my_rule to specify attribute 'p' with type"
                    + " string"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAspectWithMandatoryParameter_useRuleDefault() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {}, p = {}'.format(target.label, ctx.attr.p)
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v1', values = ['p_v1', 'p_v2'],
                                      mandatory = True) },
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(aspect_a_result = ctx.attr.dep[AspectInfo].aspect_a_result)
          pass
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p' : attr.string(default = 'p_v2'),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
        )
        main_rule(
          name = 'dep_target',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val aspectAResult: String? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_result", String::class.java)
        Truth.assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target, p = p_v2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleAspectWithMandatoryParameterProvided() {
        scratch.file(
            "test/defs.bzl",
            """
        AspectInfo = provider()
        def _aspect_a_impl(target, ctx):
          result = 'aspect_a on target {}, p = {}'.format(target.label, ctx.attr.p)
          return AspectInfo(aspect_a_result = result)

        aspect_a = aspect(
          implementation = _aspect_a_impl,
          attr_aspects = ['dep'],
          attrs = { 'p' : attr.string(default = 'p_v2', values = ['p_v1', 'p_v2'],
                                      mandatory = True) },
        )

        RuleInfo = provider()
        def _main_rule_impl(ctx):
          if ctx.attr.dep:
            return RuleInfo(aspect_a_result = ctx.attr.dep[AspectInfo].aspect_a_result)
          pass
        main_rule = rule(
          implementation = _main_rule_impl,
          attrs = {
            'dep': attr.label(aspects = [aspect_a]),
            'p' : attr.string(mandatory = True),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'main_rule')
        main_rule(
          name = 'main',
          dep = ':dep_target',
          p = 'p_v1',
        )
        main_rule(
          name = 'dep_target',
          p = 'p_v2',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:main")

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val aspectAResult =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_result") as String?
        Truth.assertThat(aspectAResult).isEqualTo("aspect_a on target @@//test:dep_target, p = p_v1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectLabelIsRepoMapped() {
        scratch.overwriteFile("MODULE.bazel", "module(name = 'my_repo')")
        scratch.file(
            "test/aspect.bzl",
            """
        load(':rule.bzl', 'MyInfo')
        def _impl(target, ctx):
           if MyInfo not in target:
               fail('Provider identity mismatch')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/rule.bzl",
            """
        MyInfo = provider()
        def _impl(ctx):
            return [MyInfo()]
        my_rule = rule(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(':rule.bzl', 'my_rule')
        my_rule(name = 'target')
        
        """.trimIndent()
        )

        val result: AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("@my_repo//test:aspect.bzl%MyAspect"),
                "//test:target"
            )
        assertThat(result.hasError()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectKeyCreatedOnlyOnceForSameBaseKeysInDiffOrder() {
        scratch.file(
            "test/defs.bzl",
            """
        a_provider = provider()
        b_provider = provider()
        c_provider = provider()
        RuleInfo = provider()

        def _a_impl(target, ctx):
          result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              result.extend(dep[a_provider].value)
          result.append('aspect a on target {} aspect_ids {}'.format(target.label,
                                                                        ctx.aspect_ids))
          return [a_provider(value = result)]
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[b_provider], [c_provider]],
        )

        def _b_impl(target, ctx):
          return [b_provider(value = ['aspect b on target {}'.format(target.label)])]
        b = aspect(
          implementation = _b_impl,
          attr_aspects = ['deps'],
          provides = [b_provider],
        )

        def _c_impl(target, ctx):
          return [c_provider(value = ['aspect c on target {}'.format(target.label)])]
        c = aspect(
          implementation = _c_impl,
          attr_aspects = ['deps'],
          provides = [c_provider]
        )

        def _r1_impl(ctx):
          result = []
          if ctx.attr.deps:
            for dep in ctx.attr.deps:
              result.extend(dep[a_provider].value)
          return RuleInfo(aspect_a_collected_result = result)
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(aspects = [c, b, a]),
          },
        )

        def _r2_impl(ctx):
          pass
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(aspects = [b]),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1', 'r2')
        r1(
          name = 't1',
        # base_keys of aspect a on t3 are [c, b]
          deps = [':t2', ':t3'],
        )
        r2(
          name = 't2',
          # aspects reaching t3 will be [b, c, b, a], after deduplicating aspects path, it will be
          # [b, c, a] and as a result the base_keys of aspect a will be [b, c]
          deps = [':t3'],
        )
        r2(
          name = 't3',
        )
        
        """.trimIndent()
        )

        update("//test:t1")

        // Aspect a should have a single AspectKey for its application on t3 and the baseKeys in it will
        // be sorted as [b, c]
        val keysForAspectAOnT3: com.google.common.collect.ImmutableList<AspectKey?> =
            getAspectKeys("//test:t3", "//test:defs.bzl%a")
        Truth.assertThat(keysForAspectAOnT3).hasSize(1)

        val baseKeys: com.google.common.collect.ImmutableList<AspectKey?> = keysForAspectAOnT3.get(0).baseKeys
        Truth.assertThat(baseKeys.stream().map<Any?> { k: AspectKey? -> k.getAspectClass().getName() })
            .containsExactly("//test:defs.bzl%b", "//test:defs.bzl%c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectRunsTwiceWithDiffBaseAspectsDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        a_provider = provider()
        b_provider = provider()
        c_provider = provider()

        def _a_impl(target, ctx):
          result = []
          if ctx.rule.attr.deps:
            for dep in ctx.rule.attr.deps:
              result.extend(dep[a_provider].value)
          if b_provider in target:
            result.append('aspect a on {} sees b_provider = {}'.format(target.label,
                                                                      target[b_provider].value))
          return [a_provider(value = result)]
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['deps'],
          required_aspect_providers = [[b_provider], [c_provider]],
        )

        def _b_impl(target, ctx):
          result = 'aspect b cannot see c_provider'
          if c_provider in target:
            result = 'aspect b can see c_provider'
          return [b_provider(value = result)]
        b = aspect(
          implementation = _b_impl,
          attr_aspects = ['deps'],
          provides = [b_provider],
          required_aspect_providers = [[c_provider]]
        )

        def _c_impl(target, ctx):
          return [c_provider(value = ['aspect c on target {}'.format(target.label)])]
        c = aspect(
          implementation = _c_impl,
          attr_aspects = ['deps'],
          provides = [c_provider]
        )

        RuleInfo = provider()
        def _r1_impl(ctx):
          result = []
          if ctx.attr.deps:
            for dep in ctx.attr.deps:
              result.extend(dep[a_provider].value)
          return RuleInfo(aspect_a_collected_result = result)
        r1 = rule(
          implementation = _r1_impl,
          attrs = {
            'deps': attr.label_list(aspects = [a]),
          },
        )

        def _r2_impl(ctx):
          pass
        r2 = rule(
          implementation = _r2_impl,
          attrs = {
            'deps': attr.label_list(aspects = [c, b]),
          },
        )

        def _r3_impl(ctx):
          pass
        r3 = rule(
          implementation = _r3_impl,
          attrs = {
            'deps': attr.label_list(aspects = [b, c]),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1', 'r2', 'r3')
        r1(
          name = 't1',
          # t1 propagate aspect (a) to targets (t2 and t3)
          deps = [':t2', ':t3'],
        )
        r2(
          name = 't2',
          # t2 propagates aspects (c, b) to target t4 and aspect a is propagated from the prev
          # level aspects path on t4 is [c, b, a], this means a can see b and b can see c
          deps = [':t4'],
        )
        r3(
          name = 't3',
        # t3 propagates aspects (b, c) to target t4 and aspect a is propagated from the prev level
        # aspects path on t4 is [b, c, a], this means a can see b but b cannot see c
         deps = [':t4'],
        )
        r1(
          name = 't4',
        )
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//test:t1")

        // Aspect a should have 2 AspectKeys for its application on t4, one where in the basekeys b can
        // see c and the other is where b cannot see c
        val keysForAspectAOnT4: com.google.common.collect.ImmutableList<AspectKey?> =
            getAspectKeys("//test:t4", "//test:defs.bzl%a")
        Truth.assertThat(keysForAspectAOnT4).hasSize(2)

        val configuredTarget: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getTargetsToBuild())
        val aspectAResult: StarlarkList<*>? =
            getStarlarkProvider(configuredTarget, "RuleInfo").getValue("aspect_a_collected_result") as StarlarkList<*>?
        Truth.assertThat(Starlark.toIterable(aspectAResult))
            .containsExactly(
                "aspect a on @@//test:t4 sees b_provider = aspect b can see c_provider",
                "aspect a on @@//test:t4 sees b_provider = aspect b cannot see c_provider"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectWithSameExplicitAttributeNameAsUnderlyingTarget() {
        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()
        def _a_impl(target, ctx):
          value = 'x from aspect = {}, x from target = {}'.format(ctx.attr.x, ctx.rule.attr.x)
          return MyInfo(aspect_result = value)
        a = aspect(
          implementation = _a_impl,
          attrs = {
            'x': attr.string(default = 'xyz')
          },
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'x': attr.int(default = 4)
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:t1")

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a")
        assertThat(aspectA).isNotNull()
        val aspectAResult: String? =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "MyInfo")
                .getValue("aspect_result", String::class.java)
        Truth.assertThat(aspectAResult).isEqualTo("x from aspect = xyz, x from target = 4")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectNotDependOnTargetDeps() {
        scratch.file(
            "test/defs.bzl",
            """
        def _a_impl(target, ctx):
          return []
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
          attrs = {
            '_tool': attr.label(default = '//test:tool'),
          },
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
            'another_dep': attr.label(),
            '_tool': attr.label(default = '//test:tool'),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', dep = ':t2', another_dep = 't4')
        r1(name = 't2', dep = ':t3')
        r1(name = 't3')
        r1(name = 't4')
        filegroup(name = 'tool')
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:t1")

        val key: AspectKey? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(analysisResult.getAspectsMap().keySet())
        val aspectNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(java.util.function.Predicate { k: SkyKey? -> k.equals(key) })
        assertThat(aspectNode).isNotNull()

        val configuredTargetsDeps: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.stream<ConfiguredTargetKey?>(
                com.google.common.collect.Iterables.< ConfiguredTargetKey > filter < ConfiguredTargetKey ? > (aspectNode.directDeps,
                ConfiguredTargetKey::class.java
            ))
        .map<Any?> { k: ConfiguredTargetKey? -> k.getLabel().toString() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        // aspect depends only on its target and its implicit dependencies not the dependencies of its
        // target
        Truth.assertThat(configuredTargetsDeps).containsAtLeast("//test:tool", "//test:t1")
        Truth.assertThat(configuredTargetsDeps).doesNotContain("//test:t2")
        Truth.assertThat(configuredTargetsDeps).doesNotContain("//test:t3")
        Truth.assertThat(configuredTargetsDeps).doesNotContain("//test:t4")

        val aspectsDeps: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.stream<AspectKey?>(
                com.google.common.collect.Iterables.< AspectKey > filter < AspectKey ? > (aspectNode.directDeps,
                AspectKey::class.java
            ))
        .map<Any?> { k: AspectKey? -> k.getLabel().toString() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        // aspect depends on the result of its application on the target deps if it propagates to them
        Truth.assertThat(aspectsDeps).containsExactly("//test:t2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelAspectNotDependsOnConfigeredTopLevelTarget() {
        scratch.file(
            "test/defs.bzl",
            """
        def _a_impl(target, ctx):
          return []
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', dep = ':t2')
        r1(name = 't2', dep = ':t3')
        r1(name = 't3')
        
        """.trimIndent()
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:t1")
        val topLevelAspectsNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(java.util.function.Predicate { key: SkyKey? -> key is TopLevelAspectsKey })
        assertThat(topLevelAspectsNode).isNotNull()
        // top level aspect should not depend on any configured target.
        val configuredTargetsDeps: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.stream<ConfiguredTargetKey?>(
                com.google.common.collect.Iterables.< ConfiguredTargetKey > filter < ConfiguredTargetKey ? > (topLevelAspectsNode.directDeps,
                ConfiguredTargetKey::class.java
            ))
        .map<Any?> { k: ConfiguredTargetKey? -> k.getLabel().toString() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(configuredTargetsDeps).isEmpty()

        // top level aspect should not even depend on any build configuration.
        val buildConfiguredTargetsDeps: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.stream<BuildConfigurationKey?>(
                com.google.common.collect.Iterables.< BuildConfigurationKey > filter < BuildConfigurationKey ? > (topLevelAspectsNode.directDeps,
                BuildConfigurationKey::class.java
            ))
        .map<Any?> { k: BuildConfigurationKey? -> k.getCanonicalName() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(buildConfiguredTargetsDeps).hasSize(0)

        // top level aspect should depend on the result of aspect's application on the top level target.
        val aspectsDeps: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.stream<AspectKey?>(
                com.google.common.collect.Iterables.< AspectKey > filter < AspectKey ? > (topLevelAspectsNode.directDeps,
                AspectKey::class.java
            ))
        .map<Any?> { k: AspectKey? -> k.getLabel().toString() }
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        Truth.assertThat(aspectsDeps).containsExactly("//test:t1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectRequiredProviderNotSatisfied_aspectKeyNotCreated() {
        scratch.file(
            "test/defs.bzl",
            """
        p1 = provider()
        def _a_impl(target, ctx):
          return []
        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
          required_providers = [p1],
        )

        def _rule_impl(ctx):
          pass
        r1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'r1')
        r1(name = 't1', dep = ':t2')
        r1(name = 't2')
        
        """.trimIndent()
        )

        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:t1")

        val topLevelAspectsNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(java.util.function.Predicate { key: SkyKey? -> key is AspectKey })

        // no aspect key should be requested since the aspect's required provider is not satisfied.
        assertThat(topLevelAspectsNode).isNull()
    }

    @Throws(java.lang.Exception::class)
    private fun writeAspectOnAliasTestFiles() {
        scratch.file(
            "test/config_setting/BUILD", "config_setting(name='defines', values={'define': 'foo=1'})"
        )

        scratch.file(
            "test/defs.bzl",
            """
        p1 = provider()
        AspectInfo = provider()

        def _a_impl(target, ctx):
          return [AspectInfo(aspect_a_result = 'target {}, p1.val = {}'.format(target.label,
              target[p1].val))]

        a = aspect(
          implementation = _a_impl,
          attr_aspects = ['dep'],
          required_providers = [p1],
        )

        def _rule_impl(ctx):
          return [p1(val = 'v1')]

        rule_with_p1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
          provides = [p1],
        )

        rule_without_p1 = rule(
          implementation = _rule_impl,
          attrs = {
            'dep': attr.label(),
          },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load('//test:defs.bzl', 'rule_with_p1', 'rule_without_p1')
        alias(
            name = 'alias_target',
            actual = select({
                              "//test/config_setting:defines": ":t1",
                              "//conditions:default": ":t2",
                              })
                )
        rule_with_p1(name = 't1')
        rule_without_p1(name = 't2')
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectOnAliasForConfiguredTarget_forwardsProvidersButNotActions() {
        scratch.file(
            "test/BUILD",
            """
        alias(
            name = 'alias_target',
            actual = ':actual',
        )

        genrule(
            name = 'actual',
            outs = ['actual.out'],
            cmd = 'touch ${'$'}@',
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/simple_label_writing_aspect.bzl",
            """
        def _simple_label_writing_aspect_impl(target, ctx):
            label_of_target_file = ctx.actions.declare_file(target.label.name + ".label")
            ctx.actions.write(
                output = label_of_target_file,
                content = target.label.name,
            )
            return [DefaultInfo(files = depset([label_of_target_file]))]

        simple_label_writing_aspect = aspect(
            implementation = _simple_label_writing_aspect_impl,
            attr_aspects = ["*"],
        )
        
        """.trimIndent()
        )

        val unused: @NotNull AnalysisResult =
            update(
                com.google.common.collect.ImmutableList.of<String?>("test/simple_label_writing_aspect.bzl%simple_label_writing_aspect"),
                "//test:alias_target"
            )

        // The aspect on the actual target has exactly one action.
        val aspectNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(
                java.util.function.Predicate { key: SkyKey? ->
                    key is AspectKey
                            && key
                        .getBaseConfiguredTargetKey()
                        .getLabel()
                        .toString()
                        .equals("//test:actual")
                })
        assertThat(aspectNode).isNotNull()
        val aspectValue: AspectValue = aspectNode.value as AspectValue
        assertThat(aspectValue.getActions()).hasSize(1)
        assertThat(aspectValue.getActions().get(0).getPrimaryOutput().getExecPathString())
            .endsWith("bin/test/actual.label")

        // The aspect on the alias target has no actions.
        val aspectOnAliasNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(
                java.util.function.Predicate { key: SkyKey? ->
                    key is AspectKey
                            && key
                        .getBaseConfiguredTargetKey()
                        .getLabel()
                        .toString()
                        .equals("//test:alias_target")
                })
        assertThat(aspectOnAliasNode).isNotNull()
        val aspectOnAliasValue: AspectValue = aspectOnAliasNode.value as AspectValue
        assertThat(AspectValue.isForAliasTarget(aspectOnAliasValue)).isTrue()
        assertThat(aspectOnAliasValue.getActions()).isEmpty()

        // But the providers should be the same.
        assertThat(aspectValue.getProviders()).isEqualTo(aspectOnAliasValue.getProviders())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectOnAliasTarget_requiredProviderSatisfied() {
        writeAspectOnAliasTestFiles()

        // this will select //test:t1 as the alias's actual target.
        useConfiguration("--define=foo=1")
        val analysisResult: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:alias_target")

        val topLevelAspectsNode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ n -> n.getKey() is AspectKey })
                .map({ n -> n.getKey() as AspectKey? })
                .map({ k -> k.getAspectName() + " on " + k.getLabel() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        // aspect required provider is satisfied by the alias's actual target.
        assertThat(topLevelAspectsNode)
            .containsExactly(
                "//test:defs.bzl%a on //test:t1", "//test:defs.bzl%a on //test:alias_target"
            )

        val configuredAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            analysisResult.getAspectsMap()
        val aspectA: ConfiguredAspect? = getConfiguredAspect(configuredAspects, "a")
        assertThat(aspectA).isNotNull()
        val aspectAResult =
            getStarlarkProvider(aspectA, "//test:defs.bzl", "AspectInfo")
                .getValue("aspect_a_result") as String?
        Truth.assertThat(aspectAResult).isEqualTo("target @@//test:t1, p1.val = v1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectOnAliasTarget_requiredProviderNotSatisfied() {
        writeAspectOnAliasTestFiles()

        // this will select the default //test:t2 as the alias's actual target.
        val unused: @NotNull AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("//test:defs.bzl%a"), "//test:alias_target")

        val topLevelAspectsNode: InMemoryNodeEntry? =
            findOnlyNodeEntry(java.util.function.Predicate { key: SkyKey? -> key is AspectKey })

        // no aspect key should be requested since the aspect's required provider is not satisfied by
        // the alias's actual target.
        assertThat(topLevelAspectsNode).isNull()
    }

    private fun getAspectKeys(
        targetLabel: String?,
        aspectLabel: String?
    ): com.google.common.collect.ImmutableList<AspectKey?> {
        return skyframeExecutor.getEvaluator().getDoneValues().entrySet().stream()
            .filter(
                { entry ->
                    entry.getKey() is AspectKey
                            && aspectKey.getAspectClass().getName().equals(aspectLabel)
                            && aspectKey.getLabel().toString().equals(targetLabel)
                })
            .map({ e -> e.getKey() as AspectKey? })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    private fun getConfiguredAspect(
        aspectsMap: MutableMap<AspectKey?, ConfiguredAspect?>, aspectName: String?
    ): ConfiguredAspect? {
        for (entry in aspectsMap.entries) {
            val aspectClass: AspectClass? = entry.key.getAspectClass()
            if (aspectClass is StarlarkAspectClass) {
                val aspectExportedName: String = aspectClass.exportedName
                if (aspectExportedName == aspectName) {
                    return entry.value
                }
            }
        }
        return null
    }

    private fun getConfiguredAspect(
        aspectsMap: MutableMap<AspectKey?, ConfiguredAspect?>, aspectName: String?, targetName: String?
    ): ConfiguredAspect? {
        for (entry in aspectsMap.entries) {
            val aspectClass: AspectClass? = entry.key.getAspectClass()
            if (aspectClass is StarlarkAspectClass) {
                val aspectExportedName: String = aspectClass.exportedName
                val target: String = entry.key.getLabel().name
                if (aspectExportedName == aspectName && target == targetName) {
                    return entry.value
                }
            }
        }
        return null
    }

    @Throws(java.lang.Exception::class)
    private fun exposeNativeAspectToStarlark() {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addBzlToplevel(
            "starlark_native_aspect", TestAspects.STARLARK_NATIVE_ASPECT_WITH_PROVIDER
        )
        builder.addBzlToplevel(
            "parametrized_native_aspect",
            TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER
        )
        builder.addNativeAspectClass(TestAspects.STARLARK_NATIVE_ASPECT_WITH_PROVIDER)
        builder.addNativeAspectClass(TestAspects.PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER)
        builder.addRuleDefinition(TestAspects.BASE_RULE)
        builder.addRuleDefinition(TestAspects.HONEST_RULE)
        useRuleClassProvider(builder.build())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkDefinedAspectCodec() {
        scratch.file(
            "test/aspect.bzl",
            """
        def _impl(target, ctx):
           print('This aspect does nothing')
           return []
        MyAspect = aspect(implementation=_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'xxx',)"
        )

        // Runs a basic analysis to prime test/aspect.bzl in Skyframe.
        val unusedAnalysisResult: AnalysisResult =
            update(com.google.common.collect.ImmutableList.of<String?>("test/aspect.bzl%MyAspect"), "//test:xxx")

        // Pulls MyAspect's value out of Skyframe from its BzlLoadValue.
        val aspectBzl: BzlLoadValue =
            getDoneValue(keyForBuild(Label.parseCanonical("//test:aspect.bzl"))) as BzlLoadValue
        val myAspect: StarlarkDefinedAspect =
            com.google.common.base.Preconditions.checkNotNull<T?>(
                aspectBzl.getModule().getGlobal("MyAspect")
            ) as StarlarkDefinedAspect

        val deserialized: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            RoundTripping.roundTripWithSkyframe({ key: SkyKey? -> this.getDoneValue(key) }, myAspect)
        assertThat(myAspect).isSameInstanceAs(deserialized)
    }

    private fun getDoneValue(key: SkyKey?): SkyValue {
        try {
            return skyframeExecutor.getDoneSkyValueForIntrospection(key)
        } catch (e: SkyframeExecutor.FailureToRetrieveIntrospectedValueException) {
            throw java.lang.AssertionError(e)
        }
    }

    /**
     * Returns the only [InMemoryNodeEntry] that matches the given predicate, or null if there
     * is none.
     * 
     * @throws AssertionError if there are multiple matching entries.
     */
    private fun findOnlyNodeEntry(predicate: java.util.function.Predicate<SkyKey?>): InMemoryNodeEntry? {
        val matchingEntries: com.google.common.collect.ImmutableList<InMemoryNodeEntry?> =
            skyframeExecutor.getEvaluator().getInMemoryGraph().getAllNodeEntries().stream()
                .filter({ entry -> predicate.test(entry.getKey()) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertWithMessage(
            "Found multiple entries: %s",
            matchingEntries.stream().map<Any?> { e: InMemoryNodeEntry? -> e.key.toString() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
            .that(matchingEntries.size)
            .isAtMost(1)
        if (matchingEntries.size == 1) {
            return matchingEntries.get(0)
        }
        return null
    }

    /** StarlarkAspectTest with "keep going" flag  */
    @RunWith(JUnit4::class)
    class WithKeepGoing : StarlarkDefinedAspectsTest() {
        override fun defaultFlags(): FlagBuilder {
            return super.defaultFlags()
                .with(com.google.devtools.build.lib.analysis.util.AnalysisTestCase.Flag.KEEP_GOING)
        }

        override fun keepGoing(): Boolean {
            return true
        }
    }

    companion object {
        private fun getAspectDescriptions(analysisResult: AnalysisResult): Iterable<String?> {
            return com.google.common.collect.Iterables.transform<F?, T?>(
                analysisResult.getAspectsMap().keySet(),
                com.google.common.base.Function { aspectKey: F? ->
                    java.lang.String.format(
                        "%s(%s)",
                        aspectKey.getAspectClass().getName(),
                        aspectKey.getLabel()
                    )
                })
        }

        private fun getLabelsToBuild(analysisResult: AnalysisResult): Iterable<String?> {
            return com.google.common.collect.Iterables.transform<F?, T?>(
                analysisResult.getTargetsToBuild(),
                com.google.common.base.Function { configuredTarget: F? -> configuredTarget.getLabel().toString() })
        }

        private fun getOutputGroupContents(
            outputGroupInfo: OutputGroupInfo, groupName: String?
        ): Iterable<String?> {
            return com.google.common.collect.Iterables.transform<F?, T?>(
                outputGroupInfo.getOutputGroup(groupName).toList(), Artifact::getRootRelativePathString
            )
        }

        private fun aspectBzlFile(attrAspects: String?): Array<String?> {
            return arrayOf<String>(
                "AspectInfo = provider()",
                "def _repro_aspect_impl(target, ctx):",
                "    s = depset([str(target.label)], transitive =",
                "      [d[AspectInfo].aspect_info for d in ctx.rule.attr.deps if AspectInfo in d])",
                "    return AspectInfo(aspect_info = s)",
                "",
                "_repro_aspect = aspect(",
                "    _repro_aspect_impl,",
                "    attr_aspects = [" + attrAspects + "],",
                ")",
                "",
                "RuleInfo = provider()",
                "def repro_impl(ctx):",
                "    s = depset(transitive = ",
                "      [d[AspectInfo].aspect_info for d in ctx.attr.deps if AspectInfo in d])",
                "    return RuleInfo(rule_info = s)",
                "",
                "def repro_no_aspect_impl(ctx):",
                "    pass",
                "",
                "repro_no_aspect = rule(implementation = repro_no_aspect_impl,",
                "             attrs = {",
                "                       'deps': attr.label_list(",
                "                             allow_files = True,",
                "                       )",
                "                      },",
                ")",
                "",
                "repro = rule(implementation = repro_impl,",
                "             attrs = {",
                "                       'deps': attr.label_list(",
                "                             allow_files = True,",
                "                             aspects = [_repro_aspect],",
                "                       )",
                "                      },",
                ")"
            )
        }
    }
}
