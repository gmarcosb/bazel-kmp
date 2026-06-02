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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Tests for the [BuildView].
 */
@RunWith(JUnit4::class)
class BuildViewTest : BuildViewTestBase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun directoryArtifactInRoot() {
        scratch.file(
            "BUILD", "genrule(name = 'slurps_dir', srcs = ['.'], outs = ['out'], cmd = 'touch $@')"
        )
        // Expect no errors.
        update("//:slurps_dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleConfiguredTarget() {
        scratch.file(
            "pkg/BUILD",
            """
        genrule(name='foo',
                cmd = '',
                srcs=['a.src'],
                outs=['a.out'])
        
        """.trimIndent()
        )
        update("//pkg:foo")
        val ruleTarget: Rule = getTarget("//pkg:foo") as Rule
        assertThat(ruleTarget.getRuleClass()).isEqualTo("genrule")

        val ruleCTAT: ConfiguredTargetAndData = getConfiguredTargetAndTarget("//pkg:foo")

        assertThat(ruleCTAT.getTargetForTesting()).isSameInstanceAs(ruleTarget)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilterByTargets() {
        scratch.file(
            "tests/BUILD",
            """
        load('//test_defs:foo_test.bzl', 'foo_test')
        foo_test(name = 'small_test_1',
                srcs = ['small_test_1.sh'],
                data = [':xUnit'],
                size = 'small',
                tags = ['tag1'])

        foo_test(name = 'small_test_2',
                srcs = ['small_test_2.sh'],
                size = 'small',
                tags = ['tag2'])


        test_suite( name = 'smallTests', tags=['small'])
        
        """.trimIndent()
        )

        //scratch.file("tests/small_test_1.py");
        update("//tests:smallTests")
        val test1: ConfiguredTargetAndData = getConfiguredTargetAndTarget("//tests:small_test_1")
        val test2: ConfiguredTargetAndData = getConfiguredTargetAndTarget("//tests:small_test_2")
        val suite: ConfiguredTargetAndData = getConfiguredTargetAndTarget("//tests:smallTests")

        val test1CT: ConfiguredTarget? = test1.getConfiguredTarget()
        val test2CT: ConfiguredTarget? = test2.getConfiguredTarget()
        val suiteCT: ConfiguredTarget? = suite.getConfiguredTarget()
        assertNoEvents() // start from a clean slate

        var targets: MutableCollection<ConfiguredTarget> =
            LinkedHashSet<ConfiguredTarget>(
                com.google.common.collect.ImmutableList.of<ConfiguredTarget?>(
                    test1CT,
                    test2CT,
                    suiteCT
                )
            )
        targets =
            java.util.ArrayList<Any?>(
                BuildView.filterTestsByTargets(
                    targets,
                    com.google.common.collect.Sets.newHashSet<E?>(test1.getTargetLabel(), suite.getTargetLabel())
                )
            )
        Truth.assertThat(targets)
            .containsExactlyElementsIn(com.google.common.collect.Sets.newHashSet<Any?>(test1CT, suiteCT))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSourceArtifact() {
        setupDummyRule()
        update("//pkg:a.src")
        val inputCT: InputFileConfiguredTarget = getInputFileConfiguredTarget("//pkg:a.src")
        val inputArtifact: Artifact = inputCT.getArtifact()
        assertThat(getGeneratingAction(inputArtifact)).isNull()
        assertThat(inputArtifact.getExecPathString()).isEqualTo("pkg/a.src")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGeneratedArtifact() {
        setupDummyRule()
        update("//pkg:a.out")
        val ctad: ConfiguredTargetAndData = getConfiguredTargetAndData("//pkg:a.out")
        val output: OutputFileConfiguredTarget = ctad.getConfiguredTarget() as OutputFileConfiguredTarget
        val outputArtifact: Artifact = output.getArtifact()
        assertThat(outputArtifact.getRoot())
            .isEqualTo(
                ctad.getConfiguration()
                    .getBinDirectory(output.getLabel().getPackageIdentifier().getRepository())
            )
        assertThat(outputArtifact.getExecPath())
            .isEqualTo(
                ctad.getConfiguration().getBinFragment(RepositoryName.MAIN).getRelative("pkg/a.out")
            )
        assertThat(outputArtifact.getRootRelativePath()).isEqualTo(PathFragment.create("pkg/a.out"))

        val action: Action = getGeneratingAction(outputArtifact)
        assertThat(action.getClass()).isSameInstanceAs(FailAction::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetArtifactOwnerInStarlark() {
        scratch.file(
            "foo/rule.bzl",
            """
        def _impl(ctx):
          f = ctx.actions.declare_file('rule_output')
          print('f owner is ' + str(f.owner))
          ctx.actions.write(
            output = f,
            content = 'foo',
          )
        gen = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load(':rule.bzl', 'gen')
        gen(name = 'a')
        
        """.trimIndent()
        )

        update("//foo:a")
        assertContainsEvent("DEBUG /workspace/foo/rule.bzl:3:8: f owner is @@//foo:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSyntaxErrorInDepPackage() {
        // Check that a loading error in a dependency is properly reported.
        scratch.file(
            "a/BUILD",
            "genrule(name='x',",
            "        srcs = ['file.txt'],",
            "        outs = ['foo'],",
            "        cmd = 'echo')",
            "@"
        ) // syntax error

        scratch.file(
            "b/BUILD",
            """
        genrule(name= 'cc',
                tools = ['//a:x'],
                outs = ['bar'],
                cmd = 'echo')
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val result: AnalysisResult = update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//b:cc")

        assertContainsEvent("invalid character: '@'")
        assertThat(result.hasError()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsVisibilityAnalysisRootCauses() {
        scratch.file(
            "private/BUILD",
            """
        genrule(
            name='private',
            outs=['private.out'],
            cmd='',
            visibility=['//visibility:private'])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name='foo',
            tools=[':bar'],
            outs=['foo.out'],
            cmd='')
        genrule(
            name='bar',
            tools=['//private'],
            outs=['bar.out'],
            cmd='')
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()

        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getLegacyFailureReason().toString()).isEqualTo("//foo:bar")
        assertThat(event.getFailedTarget().getLabel().toString()).isEqualTo("//foo:foo")

        Truth.assertThat(recorder.causes).hasSize(1)
        val cause: AnalysisRootCauseEvent = recorder.causes.get(0)
        assertThat(cause.getLabel().toString()).isEqualTo("//foo:bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsNonExistentPackageAnalysisRootCausesNoKeepGoing() {
        // Regression test for b/153480748, content taken from:
        // //devtools/builddoctor/projects/invalid/java/library_invalid_dep/BUILD#2
        scratch.file(
            "java/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name='library_invalid_dep',
            srcs=['NoOp.java'],
            deps=['//non/existent/package:target'])
        java_library(
            name='other',
            srcs=['NoOp.java'],
            deps=[])
        
        """.trimIndent()
        )
        scratch.file("java/NoOp.java", "class NoOp { private NoOp() {} }")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val e: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update(eventBus, defaultFlags(), "//java:library_invalid_dep") })
        assertThat(e)
            .hasMessageThat()
            .contains("Analysis of target '//java:library_invalid_dep' failed; build aborted")

        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getLegacyFailureReason().toString())
            .isEqualTo("//non/existent/package:target")
        assertThat(event.getFailedTarget().getLabel().toString())
            .isEqualTo("//java:library_invalid_dep")

        Truth.assertThat(recorder.causes).hasSize(1)
        val cause: AnalysisRootCauseEvent = recorder.causes.get(0)
        assertThat(cause.getLabel().toString()).isEqualTo("//non/existent/package:target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsNonExistentPackageAnalysisRootCausesKeepGoing() {
        // Regression test for b/153480748, content taken from:
        // //devtools/builddoctor/projects/invalid/java/library_invalid_dep/BUILD#2
        scratch.file(
            "java/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name='library_invalid_dep',
            srcs=['NoOp.java'],
            deps=['//non/existent/package:target'])
        java_library(
            name='other',
            srcs=['NoOp.java'],
            deps=[])
        
        """.trimIndent()
        )
        scratch.file("java/NoOp.java", "class NoOp { private NoOp() {} }")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult =
            update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//java:library_invalid_dep")
        assertThat(result.hasError()).isTrue()

        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getLegacyFailureReason().toString())
            .isEqualTo("//non/existent/package:target")
        assertThat(event.getFailedTarget().getLabel().toString())
            .isEqualTo("//java:library_invalid_dep")

        Truth.assertThat(recorder.causes).hasSize(1)
        val cause: AnalysisRootCauseEvent = recorder.causes.get(0)
        assertThat(cause.getLabel().toString()).isEqualTo("//non/existent/package:target")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsNonExistentPackageInPackageGroupKeepGoing() {
        // Regression test for b/155669924, a missed edge case from the fix to b/153480748.
        scratch.file(
            "java/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        package_group(name = 'group', includes=['//non/existent/package:othergroup'])
        java_library(
            name='library_invalid_visibility',
            srcs=['NoOp.java'],
            deps=[':other'],
            visibility=[':group'])
        java_library(
            name='other',
            srcs=['NoOp.java'],
            deps=[])
        
        """.trimIndent()
        )
        scratch.file("java/NoOp.java", "class NoOp { private NoOp() {} }")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult =
            update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//java:library_invalid_visibility")
        assertThat(result.hasError()).isTrue()

        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getLegacyFailureReason().toString())
            .isEqualTo("//non/existent/package:othergroup")
        assertThat(event.getFailedTarget().getLabel().toString())
            .isEqualTo("//java:library_invalid_visibility")

        Truth.assertThat(recorder.causes).hasSize(1)
        val cause: AnalysisRootCauseEvent = recorder.causes.get(0)
        assertThat(cause.getLabel().toString()).isEqualTo("//non/existent/package:othergroup")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestOnlyFailureReported() {
        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name='foo',
            tools=[':bar'],
            outs=['foo.out'],
            cmd='')
        genrule(
            name='bar',
            outs=['bar.out'],
            testonly=1,
            cmd='')
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()

        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getLegacyFailureReason().toString()).isEqualTo("//foo:foo")
        assertThat(event.getFailedTarget().getLabel().toString()).isEqualTo("//foo:foo")

        Truth.assertThat(recorder.causes).hasSize(1)
        val cause: AnalysisRootCauseEvent = recorder.causes.get(0)
        assertThat(cause.getLabel().toString()).isEqualTo("//foo:foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisReportsDependencyCycle() {
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='foo',deps=['//bar'])"
        )
        scratch.file(
            "bar/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name='bar',deps=[':bar'])"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo")
        assertThat(result.hasError()).isTrue()
        Truth.assertThat(recorder.events).hasSize(1)
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getConfigurationId()).isNotEqualTo(NullConfiguration.INSTANCE.getEventId())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReportsLoadingRootCauses() {
        // This test checks that two simultaneous errors are both reported:
        // - missing outs attribute,
        // - package referenced in tools does not exist
        scratch.file(
            "pkg/BUILD",
            """
        genrule(name='foo',
                tools=['//nopackage:missing'],
                cmd='')
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val loadingRecorder: LoadingFailureRecorder = LoadingFailureRecorder()
        val analysisRecorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(loadingRecorder)
        eventBus.register(analysisRecorder)
        val result: AnalysisResult =
            update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//pkg:foo")
        assertThat(result.hasError()).isTrue()

        Truth.assertThat(analysisRecorder.events).hasSize(1)
        val analysisFailureEvent: AnalysisFailureEvent = analysisRecorder.events.get(0)
        assertThat(analysisFailureEvent.getFailedTarget().getLabel().toString()).isEqualTo("//pkg:foo")
        val analysisFailureCauses: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.causes.Cause> =
            analysisFailureEvent.getRootCauses().toList()
        val missingPackageCause: com.google.devtools.build.lib.causes.Cause =
            if (analysisFailureCauses.get(0) is AnalysisFailedCause)
                analysisFailureCauses.get(0)
            else
                analysisFailureCauses.get(1)
        assertThat(missingPackageCause.label)
            .isEqualTo(Label.parseCanonical("//nopackage:missing"))
        assertContainsEvent("missing value for mandatory attribute 'outs'")
        assertContainsEvent("no such package 'nopackage'")
        // Skyframe correctly reports the other root cause as the genrule itself (since it is
        // missing attributes).
        Truth.assertThat(loadingRecorder.events).hasSize(1)
        Truth.assertThat(loadingRecorder.events)
            .contains(
                LoadingFailureEvent(
                    Label.parseCanonical("//pkg:foo"), Label.parseCanonical("//pkg:foo")
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleRootCauseReporting() {
        scratch.file(
            "gp/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'gp', deps = ['//p:p'])"
        )
        scratch.file(
            "p/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'p', deps = ['//c1:not', '//c2:not'])"
        )
        scratch.file("c1/BUILD")
        scratch.file("c2/BUILD")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: LoadingFailureRecorder = LoadingFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//gp")
        assertThat(result.hasError()).isTrue()
        Truth.assertThat(recorder.events).hasSize(2)
        Truth.assertThat(recorder.events)
            .contains(
                LoadingFailureEvent(
                    Label.parseCanonical("//gp"), Label.parseCanonical("//c1:not")
                )
            )
        Truth.assertThat(recorder.events)
            .contains(
                LoadingFailureEvent(
                    Label.parseCanonical("//gp"), Label.parseCanonical("//c2:not")
                )
            )
    }

    /**
     * Regression test for: "Package group includes are broken"
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelPackageGroup() {
        scratch.file(
            "tropical/BUILD",
            """
        package_group(name='guava', includes=[':mango'])
        package_group(name='mango')
        
        """.trimIndent()
        )

        // If the analysis phase results in an error, this will throw an exception
        update("//tropical:guava")

        // Check if the included package group also got analyzed
        assertThat(getConfiguredTarget("//tropical:mango", null)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTopLevelInputFile() {
        scratch.file(
            "tropical/BUILD",
            "exports_files(['file.txt'])"
        )
        update("//tropical:file.txt")
        assertThat(getConfiguredTarget("//tropical:file.txt", null)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetDirectPrerequisites() {
        scratch.file(
            "package/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        filegroup(name='top', srcs=[':inner', 'file'])
        foo_library(name='inner', srcs=['script.sh'])
        
        """.trimIndent()
        )
        update("//package:top")
        val top: ConfiguredTarget? = getConfiguredTarget("//package:top", getTargetConfiguration())
        val targets: Iterable<ConfiguredTarget?> = getView().getDirectPrerequisitesForTesting(reporter, top)
        val labels: Iterable<Label> = com.google.common.collect.Iterables.transform<ConfiguredTarget?, Label>(
            targets,
            TransitiveInfoCollection::getLabel
        )
        Truth.assertThat(labels)
            .containsExactly(
                Label.parseCanonical("//package:inner"), Label.parseCanonical("//package:file")
            )
    }

    // Regression test: "output_filter broken (but in a different way)"
    @org.junit.Test
    @Ignore("b/182560362 Starlark java_library can't output warnings")
    @Throws(java.lang.Exception::class)
    fun testOutputFilterSeeWarning() {
        runAnalysisWithOutputFilter(java.util.regex.Pattern.compile(".*"))
        assertContainsEvent("please do not import '//java/a:A.java'")
    }

    // Regression test: "output_filter broken (but in a different way)"
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFilter() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        runAnalysisWithOutputFilter(java.util.regex.Pattern.compile("^//java/c"))
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFilterWithDebug() {
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(name = 'a',
          srcs = ['A.java'],
          deps = ['//java/b'])
        
        """.trimIndent()
        )
        scratch.file(
            "java/b/rules.bzl",
            """
        def _impl(ctx):
          print('debug in b')
          ctx.actions.write(
            output = ctx.outputs.my_output,
            content = 'foo',
          )
        gen = rule(implementation = _impl, outputs = {'my_output': 'B.java'})
        
        """.trimIndent()
        )
        scratch.file(
            "java/b/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        load(':rules.bzl', 'gen')
        gen(name='src')
        java_library(name = 'b', srcs = [':src'])
        
        """.trimIndent()
        )
        reporter.setOutputFilter(RegexOutputFilter.forPattern(java.util.regex.Pattern.compile("^//java/a")))

        update("//java/a:a")
        assertContainsEvent("DEBUG /workspace/java/b/rules.bzl:2:8: debug in b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisErrorMessageWithKeepGoing() {
        scratch.file(
            "a/foo_one.bzl",
            """
        def _impl(ctx):
          if len(ctx.files.srcs) != 1:
             fail("you must specify exactly one file in 'srcs'", attr = "srcs")
        foo_one = rule(
          implementation = _impl,
          attrs = {
            "srcs": attr.label_list(allow_files=True),
          },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD", "load(':foo_one.bzl', 'foo_one')", "foo_one(name='a', srcs=['a1.sh', 'a2.sh'])"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val result: AnalysisResult = update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//a")
        assertThat(result.hasError()).isTrue()
        assertContainsEvent("errors encountered while analyzing target '//a:a'")
    }

    /**
     * Regression test: Exception in ConfiguredTargetGraph.checkForCycles()
     * when multiple top-level targets depend on the same cycle.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependencyBelowTwoTargets() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'top1', srcs = ['top1.sh'], deps = [':rec1'])
        foo_library(name = 'top2', srcs = ['top2.sh'], deps = [':rec1'])
        foo_library(name = 'rec1', srcs = ['rec1.sh'], deps = [':rec2'])
        foo_library(name = 'rec2', srcs = ['rec2.sh'], deps = [':rec1'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val result: AnalysisResult =
            update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo:top1", "//foo:top2")
        assertThat(result.hasError()).isTrue()
        assertContainsEvent("in foo_library rule //foo:rec1: cycle in dependency graph:\n")
        assertContainsEvent("in foo_library rule //foo:top")
    }

    // Regression test: cycle node depends on error.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorBelowCycle() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling (also b/67412276: handle cycles properly).
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'top', deps = ['mid'])
        foo_library(name = 'mid', deps = ['bad', 'cycle1'])
        foo_library(name = 'bad', srcs = ['//badbuild:isweird'])
        foo_library(name = 'cycle1', deps = ['cycle2', 'mid'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        
        """.trimIndent()
        )
        scratch.file("badbuild/BUILD", "")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        injectGraphListenerForTesting(NotifyingHelper.Listener.NULL_LISTENER,  /*deterministic=*/true)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//foo:top") })
        assertContainsEvent(
            "no such target '//badbuild:isweird': target 'isweird' not declared in "
                    + "package 'badbuild'"
        )
        assertContainsEvent("and referenced by '//foo:bad'")
        assertContainsEvent("in foo_library rule //foo")
        assertContainsEvent("cycle in dependency graph")
        MoreAsserts.assertEventCountAtLeast(2, eventCollector)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorBelowCycleKeepGoing() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'top', deps = ['mid'])
        foo_library(name = 'mid', deps = ['bad', 'cycle1'])
        foo_library(name = 'bad', srcs = ['//badbuild:isweird'])
        foo_library(name = 'cycle1', deps = ['cycle2', 'mid'])
        foo_library(name = 'cycle2', deps = ['cycle1'])
        
        """.trimIndent()
        )
        scratch.file("badbuild/BUILD", "")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo:top")
        assertContainsEvent(
            "no such target '//badbuild:isweird': target 'isweird' not declared in "
                    + "package 'badbuild'"
        )
        assertContainsEvent("and referenced by '//foo:bad'")
        assertContainsEvent("in foo_library rule //foo")
        assertContainsEvent("cycle in dependency graph")
        // This error is triggered both in configuration trimming (which visits the transitive target
        // closure) and in the normal configured target cycle detection path. So we get an additional
        // instance of this check (which varies depending on whether Skyframe loading phase is enabled).
        // TODO(gregce): Fix above and uncomment the below. Note that the duplicate doesn't make it into
        // real user output (it only affects tests).
        //  assertEventCount(3, eventCollector);
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAnalysisEntryHasActionsEvenWithError() {
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(name = 'foo',
                 expression = 'deps(//foo:nosuchtarget)',
                 scope = ['//foo:a'])
        foo_library(name = 'a')
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//foo:foo") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHelpfulErrorForWrongPackageLabels() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='x', srcs=['x.cc'])"
        )
        scratch.file(
            "y/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='y', srcs=['y.cc'], deps=['//x:z'])"
        )

        val result: AnalysisResult = update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//y:y")
        assertThat(result.hasError()).isTrue()
        assertContainsEvent(
            "no such target '//x:z': target 'z' not declared in package 'x' defined by"
                    + " /workspace/x/BUILD"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNewActionsAreDifferentAndDontConflict() {
        scratch.file(
            "pkg/BUILD",
            """
        genrule(name='a',
                cmd='',
                outs=['a.out'])
        
        """.trimIndent()
        )
        val outputCT: OutputFileConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//pkg:a.out").getTargetsToBuild()) as OutputFileConfiguredTarget?
        val outputArtifact: Artifact? = outputCT.getArtifact()
        val action: Action = getGeneratingAction(outputArtifact)
        assertThat(action).isNotNull()
        scratch.overwriteFile(
            "pkg/BUILD",
            """
        genrule(name='a',
                cmd='false',
                outs=['a.out'])
        
        """.trimIndent()
        )
        update("//pkg:a.out")
        Truth.assertWithMessage("Actions should not be compatible")
            .that(Actions.canBeShared(actionKeyContext, action, getGeneratingAction(outputArtifact)))
            .isFalse()
    }

    /**
     * This test exercises the case where we invalidate (mark dirty) a node in one build command
     * invocation and the revalidation (because it did not change) happens in a subsequent build
     * command call.
     * 
     * - In the first update call we construct A.
     * 
     * - Then we construct B and we make the glob get invalidated. We do that by deleting a file
     * because it depends on the directory listing. Because of that A gets invalidated.
     * 
     * - Then we construct A again. The glob gets revalidated because it is still matching just A.java
     * and A configured target gets revalidated too. At the end of the analysis A java action should
     * be in the action graph.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultiBuildInvalidationRevalidation() {
        scratch.file("java/a/A.java", "bla1")
        scratch.file("java/a/C.java", "bla2")
        scratch.file(
            "java/a/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_test")
        java_test(name = 'A',
                  srcs = glob(['A*.java']))
        java_test(name = 'B',
                  srcs = ['B.java'])
        
        """.trimIndent()
        )
        useConfiguration("--experimental_google_legacy_api")
        val ct: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//java/a:A").getTargetsToBuild())
        scratch.deleteFile("java/a/C.java")
        update("//java/a:B")
        update("//java/a:A")
        assertThat(getGeneratingAction(getBinArtifact("A.jar", ct))).isNotNull()
    }

    /** Regression test for b/14248208.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepOnGoodTargetInBadPkgAndTransitivelyBadTarget() {
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "parent/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'foo',
                   srcs = ['//badpkg1:okay-target', '//okaypkg:transitively-bad-target'])
        
        """.trimIndent()
        )
        val badpkg1BuildFile: Path =
            scratch.file(
                "badpkg1/BUILD",
                """
            exports_files(['okay-target'])
            fail()
            
            """.trimIndent()
            )
        scratch.file(
            "okaypkg/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'transitively-bad-target',
                   srcs = ['//badpkg2:bad-target'])
        
        """.trimIndent()
        )
        val badpkg2BuildFile: Path =
            scratch.file(
                "badpkg2/BUILD",
                """
            load('//test_defs:foo_library.bzl', 'foo_library')
            foo_library(name = 'bad-target')
            fail()
            
            """.trimIndent()
            )
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//parent:foo")
        // Each event string may contain stack traces and error messages with multiple file names.
        assertContainsEventWithFrequency(badpkg1BuildFile.asFragment().getPathString(), 1)
        assertContainsEventWithFrequency(badpkg2BuildFile.asFragment().getPathString(), 1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepOnGoodTargetInBadPkgAndTransitiveCycle_notIncremental() {
        runTestDepOnGoodTargetInBadPkgAndTransitiveCycle( /*incremental=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDepOnGoodTargetInBadPkgAndTransitiveCycle_incremental() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        runTestDepOnGoodTargetInBadPkgAndTransitiveCycle( /*incremental=*/true)
    }

    /**
     * Regression test: in keep_going mode, cycles in target graph are reported even if the package is
     * in error.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCycleReporting_targetCycleWhenPackageInError() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "cycles/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = [':b'])
        foo_library(name = 'b', deps = [':a'])
        x = 1//0
        
        """.trimIndent()
        ) // dynamic error
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//cycles:a")
        assertContainsEvent("division by zero")
        assertContainsEvent("cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLoadingDoesntShortCircuitInKeepGoing() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        scratch.file(
            "parent/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'a', deps = ['//child:b'])
        fail('parentisbad')
        
        """.trimIndent()
        )
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'b')
        fail('childisbad')
        
        """.trimIndent()
        )
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//parent:a")
        assertContainsEventWithFrequency("parentisbad", 1)
        assertContainsEventWithFrequency("childisbad", 1)
        assertContainsEventWithFrequency("and referenced by '//parent:a'", 1)
    }

    /**
     * Smoke test for the Skyframe code path.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframe() {
        setupDummyRule()
        val aoutLabel = "//pkg:a.out"

        update(aoutLabel)

        // However, a ConfiguredTarget was actually produced.
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(getAnalysisResult().getTargetsToBuild())
        assertThat(target.getLabel().toString()).isEqualTo(aoutLabel)

        val aout: Artifact? = target.getProvider(FileProvider::class.java).getFilesToBuild().getSingleton()
        val action: Action = getGeneratingAction(aout)
        assertThat(action.getClass()).isSameInstanceAs(FailAction::class.java)
    }

    /**
     * ConfiguredTargetFunction should not register actions in legacy Blaze ActionGraph unless
     * the creation of the node is successful.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionsNotRegisteredInLegacyWhenError() {
        // First find the artifact we want to make sure is not generated by an action with an error.
        // Then update the BUILD file and re-analyze.

        scratch.file(
            "foo/failer.bzl",
            """
        def _impl(ctx):
          if ctx.attr.fail:
            fail('failing')
          ctx.actions.run_shell(outputs=[ctx.outputs.out], command='null')
        failer = rule(
          _impl,
          attrs = {
            'fail': attr.bool(),
            'out': attr.output(),
          },
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load(':failer.bzl', 'failer')
        failer(name = 'foo', fail = False, out = 'foo.txt')
        
        """.trimIndent()
        )
        val foo: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(update("//foo:foo").getTargetsToBuild())
        val fooOut: Artifact? = foo.getProvider(FileProvider::class.java).getFilesToBuild().getSingleton()
        assertThat(getActionGraph().getGeneratingAction(fooOut)).isNotNull()
        clearAnalysisResult()

        // Overwrite with an analysis-time error.
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load(':failer.bzl', 'failer')
        failer(name = 'foo', fail = True, out = 'foo.txt')
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//foo:foo") })
        assertThat(getActionGraph().getGeneratingAction(fooOut)).isNull()
    }

    /**
     * Regression test:
     * "skyframe: ArtifactFactory and ConfiguredTargets out of sync".
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSkyframeAnalyzeRuleThenItsOutputFile() {
        scratch.file(
            "pkg/BUILD",
            """
        testing_dummy_rule(name='foo',
                           srcs=['a.src'],
                           outs=['a.out'])
        
        """.trimIndent()
        )

        scratch.file(
            "pkg2/BUILD",
            """
        testing_dummy_rule(name='foo',
                           srcs=['a.src'],
                           outs=['a.out'])
        
        """.trimIndent()
        )
        val aoutLabel = "//pkg:a.out"

        update("//pkg2:foo")
        update("//pkg:foo")
        scratch.overwriteFile(
            "pkg2/BUILD",
            """
        testing_dummy_rule(name='foo',
                           srcs=['a.src'],
                           outs=['a.out'])
        # Comment
        
        """.trimIndent()
        )

        update("//pkg:a.out")

        // However, a ConfiguredTarget was actually produced.
        val target: ConfiguredTarget? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(getAnalysisResult().getTargetsToBuild())
        assertThat(target.getLabel().toString()).isEqualTo(aoutLabel)

        val aout: Artifact? = target.getProvider(FileProvider::class.java).getFilesToBuild().getSingleton()
        val action: Action = getGeneratingAction(aout)
        assertThat(action.getClass()).isSameInstanceAs(FailAction::class.java)
    }

    /**
     * Tests that skyframe reports the root cause as being the target that depended on the symlink
     * cycle.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRootCauseReportingFileSymlinks() {
        scratch.file(
            "gp/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'gp', deps = ['//p'])"
        )
        scratch.file(
            "p/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'p', deps = ['//c'])"
        )
        scratch.file(
            "c/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'c', deps = [':c1', ':c2'])
        foo_library(name = 'c1', deps = ['//cycles1'])
        foo_library(name = 'c2', deps = ['//cycles2'])
        
        """.trimIndent()
        )
        val cycles1BuildFilePath: Path =
            scratch.file(
                "cycles1/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'cycles1', srcs = glob(['*.sh']))"
            )
        val cycles2BuildFilePath: Path =
            scratch.file(
                "cycles2/BUILD",
                "load('//test_defs:foo_library.bzl', 'foo_library')",
                "foo_library(name = 'cycles2', srcs = glob(['*.sh']))"
            )
        cycles1BuildFilePath.getParentDirectory().getRelative("cycles1.sh").createSymbolicLink(
            PathFragment.create("cycles1.sh")
        )
        cycles2BuildFilePath.getParentDirectory().getRelative("cycles2.sh").createSymbolicLink(
            PathFragment.create("cycles2.sh")
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val recorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(recorder)
        val result: AnalysisResult = update(eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//gp")
        assertThat(result.hasError()).isTrue()
        val event: AnalysisFailureEvent = recorder.events.get(0)
        assertThat(event.getFailedTarget().getLabel().toString()).isEqualTo("//gp:gp")
        val rootCauseLabels: MutableList<Label>? =
            event.getRootCauses().toList().stream().map(com.google.devtools.build.lib.causes.Cause::label)
                .collect(Collectors.toList())
        Truth.assertThat(rootCauseLabels)
            .containsExactly(Label.parseCanonical("//cycles1"), Label.parseCanonical("//cycles2"))
    }

    /**
     * Regression test for bug when a configured target has missing deps, but also depends
     * transitively on an error. We build //foo:query, which depends on a valid and an invalid target
     * pattern. We first make sure the invalid target pattern is in the graph, so that it throws when
     * requested by //foo:query. Then, when bubbling the invalid target pattern error up, //foo:query
     * must cope with the combination of an error and a missing dep.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGenQueryWithBadTargetAndUnfinishedTarget() {
        // The target //foo:zquery is used to force evaluation of //foo:nosuchtarget before the target
        // patterns in //foo:query are enqueued for evaluation. That way, //foo:query will depend on one
        // invalid target pattern and two target patterns that haven't been evaluated yet.
        // It is important that a missing target pattern is requested before the exception is thrown, so
        // we have both //foo:b and //foo:z missing from the deps, in the hopes that at least one of
        // them will come before //foo:nosuchtarget.
        scratch.file(
            "foo/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        genquery(name = 'query',
                 expression = 'deps(//foo:b) except //foo:nosuchtarget except //foo:z',
                 scope = ['//foo:a'])
        genquery(name = 'zquery',
                 expression = 'deps(//foo:nosuchtarget)',
                 scope = ['//foo:a'])
        foo_library(name = 'a')
        foo_library(name = 'b')
        foo_library(name = 'z')
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        var e: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//foo:zquery") })
        assertThat(e)
            .hasMessageThat()
            .contains("Analysis of target '//foo:zquery' failed; build aborted")
        e = org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//foo:query") })
        assertThat(e)
            .hasMessageThat()
            .contains("Analysis of target '//foo:query' failed; build aborted")
    }

    /**
     * Tests that rules with configurable attributes can be accessed through [ ]. This is a regression test for a Bazel crash.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPostProcessedConfigurableAttributes() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        useConfiguration("--compilation_mode=fastbuild")
        reporter.removeHandler(FoundationTestCase.failFastHandler) // Expect errors from action conflicts.
        scratch.file(
            "conflict/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        config_setting(name = 'a', values = {'compilation_mode': 'dbg'})
        cc_library(name='x', srcs=select({':a': ['a.cc'], '//conditions:default': ['foo.cc']}))
        cc_binary(name='_objs/x/foo.o', srcs=['bar.cc'])
        
        """.trimIndent()
        )
        val result: AnalysisResult =
            update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//conflict:_objs/x/foo.o", "//conflict:x")
        assertThat(result.hasError()).isTrue()
        // Expect to reach this line without a Precondition-triggered NullPointerException.
        assertContainsEvent("file 'conflict/_objs/x/foo.o' is generated by these conflicting actions")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCycleDueToJavaLauncherConfiguration() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(name = 'java', srcs = ['DoesntMatter.java'])
        cc_binary(name = 'cpp', data = [':java'])
        
        """.trimIndent()
        )
        // Everything is fine - the dependency graph is acyclic.
        update("//foo:java", "//foo:cpp")

        // Now there will be an analysis-phase cycle because the java_binary now has an implicit dep on
        // the cc_binary launcher.
        useConfiguration("--java_launcher=//foo:cpp")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val expected: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//foo:java", "//foo:cpp") })
        assertThat(expected)
            .hasMessageThat()
            .matches("Analysis of target '//foo:(java|cpp)' failed; build aborted.*")
        assertContainsEvent("cycle in dependency graph")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependsOnBrokenTarget() {
        scratch.file(
            "foo/BUILD",
            "load('//test_defs:foo_test.bzl', 'foo_test')",
            "foo_test(name = 'test', srcs = ['test.sh'], data = ['//bar:data'])"
        )
        scratch.file("bar/BUILD", "BROKEN BROKEN BROKEN!!!")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val expected: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//foo:test") })
        assertThat(expected)
            .hasMessageThat()
            .matches("Analysis of target '//foo:test' failed; build aborted.*")
    }

    /**
     * Regression test: IllegalStateException in BuildView.update() on circular dependency instead of
     * graceful failure.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependency() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        scratch.file(
            "cycle/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = 'foo', srcs = ['foo.cc'], deps = [':bar'])
        cc_library(name = 'bar', srcs = ['bar.cc'], deps = [':foo'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val expected: ViewCreationFailedException? =
            org.junit.Assert.assertThrows<T?>(
                ViewCreationFailedException::class.java,
                org.junit.function.ThrowingRunnable { update("//cycle:foo") })
        assertContainsEvent("in cc_library rule //cycle:foo: cycle in dependency graph:")
        assertThat(expected)
            .hasMessageThat()
            .contains("Analysis of target '//cycle:foo' failed; build aborted")
    }

    /**
     * Regression test: IllegalStateException in BuildView.update() on circular dependency instead of
     * graceful failure.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCircularDependencyWithKeepGoing() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67412276): handle cycles properly.
            return
        }
        scratch.file(
            "cycle/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(name = 'foo', srcs = ['foo.cc'], deps = [':bar'])
        cc_library(name = 'bar', srcs = ['bar.cc'], deps = [':foo'])
        cc_library(name = 'bat', srcs = ['bat.cc'], deps = [':bas'])
        cc_library(name = 'bas', srcs = ['bas.cc'], deps = [':bau'])
        cc_library(name = 'bau', srcs = ['bas.cc'], deps = [':bas'])
        cc_library(name = 'baz', srcs = ['baz.cc'])
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val loadingFailureRecorder: LoadingFailureRecorder = LoadingFailureRecorder()
        val analysisFailureRecorder: AnalysisFailureRecorder = AnalysisFailureRecorder()
        eventBus.register(loadingFailureRecorder)
        eventBus.register(analysisFailureRecorder)
        update(
            eventBus, defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING),
            "//cycle:foo", "//cycle:bat", "//cycle:baz"
        )
        assertContainsEvent("in cc_library rule //cycle:foo: cycle in dependency graph:")
        assertContainsEvent("in cc_library rule //cycle:bas: cycle in dependency graph:")
        assertContainsEvent(
            "errors encountered while analyzing target '//cycle:foo', it will not be built"
        )
        assertContainsEvent(
            "errors encountered while analyzing target '//cycle:bat', it will not be built"
        )
        // With interleaved loading and analysis, we can no longer distinguish loading-phase cycles
        // and analysis-phase cycles. This was previously reported as a loading-phase cycle, as it
        // happens with any configuration (cycle is hard-coded in the BUILD files). Also see the
        // test below.
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<AnalysisFailureEvent?, Pair<String?, String?>?>(
                analysisFailureRecorder.events,
                com.google.common.base.Function { t: AnalysisFailureEvent? -> ANALYSIS_EVENT_TO_STRING_PAIR.apply(t) })
        )
            .containsExactly(
                Pair.of("//cycle:foo", "//cycle:foo"), Pair.of("//cycle:bat", "//cycle:bas")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLoadingErrorReportedCorrectly() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a')"
        )
        scratch.file(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b', deps = ['//missing:lib'])"
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        val result: AnalysisResult = update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//a", "//b")
        assertThat(result.hasError()).isTrue()
        com.google.common.truth.Subject.contains("command succeeded, but not all targets were analyzed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisibilityReferencesNonexistentPackage() {
        scratch.file("z/a/BUILD", "filegroup(name='a', visibility=['//nonexistent:nothing'])")
        scratch.file("z/b/BUILD", "filegroup(name='b', srcs=['//z/a:a'])")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//z/b:b") })
        assertContainsEvent("no such package 'nonexistent'")
    }

    // regression test ("java.lang.IllegalStateException: cannot happen")
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefaultVisibilityInNonexistentPackage() {
        scratch.file(
            "z/a/BUILD",
            """
        package(default_visibility=['//b'])
        filegroup(name='alib')
        
        """.trimIndent()
        )
        scratch.file("z/b/BUILD", "filegroup(name='b', srcs=['//z/a:alib'])")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//z/b:b") })
        assertContainsEvent("no such package 'b'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonTopLevelErrorsPrintedExactlyOnce() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        scratch.file(
            "parent/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = ['//child:b'])"
        )
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'b')
        fail('some error')
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//parent:a") })
        assertContainsEventWithFrequency("some error", 1)
        assertContainsEventWithFrequency(
            "Target '//child:b' contains an error and its package is in error and referenced "
                    + "by '//parent:a'", 1
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonTopLevelErrorsPrintedExactlyOnce_keepGoing() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        scratch.file(
            "parent/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = ['//child:b'])"
        )
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'b')
        fail('some error')
        
        """.trimIndent()
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//parent:a")
        assertContainsEventWithFrequency("some error", 1)
        assertContainsEventWithFrequency(
            "Target '//child:b' contains an error and its package is in error and referenced "
                    + "by '//parent:a'", 1
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonTopLevelErrorsPrintedExactlyOnce_actionListener() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        scratch.file(
            "parent/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = ['//child:b'])"
        )
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'b')
        fail('some error')
        
        """.trimIndent()
        )
        scratch.file(
            "okay/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'okay', srcs = ['okay.sh'])"
        )
        useConfiguration("--experimental_action_listener=//parent:a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//okay") })
        assertContainsEventWithFrequency("some error", 1)
        assertContainsEventWithFrequency(
            "Target '//child:b' contains an error and its package is in error and referenced "
                    + "by '//parent:a'", 1
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonTopLevelErrorsPrintedExactlyOnce_actionListener_keepGoing() {
        if (AnalysisTestCase.getInternalTestExecutionMode() != InternalTestExecutionMode.NORMAL) {
            // TODO(b/67651960): fix or justify disabling.
            return
        }
        scratch.file(
            "parent/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'a', deps = ['//child:b'])"
        )
        scratch.file(
            "child/BUILD",
            """
        load('//test_defs:foo_library.bzl', 'foo_library')
        foo_library(name = 'b')
        fail('some error')
        
        """.trimIndent()
        )
        scratch.file(
            "okay/BUILD",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "load('//test_defs:foo_library.bzl', 'foo_library')",
            "foo_library(name = 'okay', srcs = ['okay.sh'])"
        )
        useConfiguration("--experimental_action_listener=//parent:a")
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//okay")
        assertContainsEventWithFrequency("some error", 1)
        assertContainsEventWithFrequency(
            "Target '//child:b' contains an error and its package is in error and referenced "
                    + "by '//parent:a'", 1
        )
    }

    /**
     * Here, injecting_rule injects an aspect which acts on a action_rule() and registers an action.
     * The action_rule() registers another action of its own.
     * 
     * 
     * This test asserts that both actions are reported.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleExtraActionsDontHideAspectExtraActions() {
        useConfiguration("--experimental_action_listener=//pkg:listener")

        scratch.file(
            "x/BUILD",
            """
        load(':extension.bzl', 'injecting_rule', 'action_rule')
        injecting_rule(name='a', deps=[':b'])
        action_rule(name='b')
        
        """.trimIndent()
        )

        scratch.file(
            "x/extension.bzl",
            """
        def _aspect1_impl(target, ctx):
          ctx.actions.do_nothing(mnemonic='Mnemonic')
          return []
        aspect1 = aspect(_aspect1_impl, attr_aspects=['deps'])

        def _injecting_rule_impl(ctx):
          return []
        injecting_rule = rule(_injecting_rule_impl,
            attrs = { 'deps' : attr.label_list(aspects = [aspect1]) })

        def _action_rule_impl(ctx):
          out = ctx.actions.declare_file(ctx.label.name)
          ctx.actions.run_shell(outputs = [out], command = 'dontcare', mnemonic='Mnemonic')
          return []
        action_rule = rule(_action_rule_impl, attrs = { 'deps' : attr.label_list() })
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        extra_action(name='xa', cmd='echo dont-care')
        action_listener(name='listener', mnemonics=['Mnemonic'], extra_actions=[':xa'])
        
        """.trimIndent()
        )

        val analysisResult: AnalysisResult = update("//x:a")

        val owners: MutableList<String> = java.util.ArrayList<String>()
        for (artifact in analysisResult.getArtifactsToBuild()) {
            if ("xa" == artifact.getExtension()) {
                owners.add(artifact.getOwnerLabel().toString())
            }
        }
        Truth.assertThat(owners).containsExactly("//x:b", "//x:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorMessageForMissingPackageGroup() {
        scratch.file(
            "apple/BUILD", "filegroup(name='apple', srcs=['x.txt'], visibility=['//non:existent'])"
        )
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//apple") })
        assertDoesNotContainEvent("implicitly depends upon")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun allowedRuleClassesAndAllowedRuleClassesWithWarning() {
        setRulesAvailableInTests(
            MockRule {
                MockRule.define(
                    "custom_rule",
                    attr("deps", BuildType.LABEL_LIST)
                        .allowedFileTypes()
                        .allowedRuleClasses("java_library", "java_binary")
                        .allowedRuleClassesWithWarning("genrule")
                )
            } as MockRule)

        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'genlib',
            srcs = [],
            outs = ['genlib.out'],
            cmd = 'echo hi > ${'$'}@')
        custom_rule(
            name = 'foo',
            deps = [':genlib'])
        
        """.trimIndent()
        )

        update("//foo")
        assertContainsEvent(
            ("WARNING /workspace/foo/BUILD:6:12: in deps attribute of custom_rule rule "
                    + "//foo:foo: genrule rule '//foo:genlib' is unexpected here (expected java_library or "
                    + "java_binary); continuing anyway")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorInImplicitDeps() {
        setRulesAvailableInTests(
            MockRule {
                try {
                    return@MockRule MockRule.define(
                        "custom_rule",
                        attr("\$implicit1", BuildType.LABEL_LIST)
                            .defaultValue(
                                com.google.common.collect.ImmutableList.of<E?>(
                                    Label.parseCanonicalUnchecked("//bad2:label"),
                                    Label.parseCanonicalUnchecked("//foo:dep")
                                )
                            ),
                        attr("\$implicit2", BuildType.LABEL)
                            .defaultValue(Label.parseCanonicalUnchecked("//bad:label"))
                    )
                } catch (e: ConversionException) {
                    throw java.lang.IllegalStateException(e)
                }
            } as MockRule)
        scratch.file(
            "foo/BUILD",
            """
        custom_rule(name = 'foo')
        filegroup(name = 'dep')
        
        """.trimIndent()
        )
        scratch.file(
            "bad/BUILD",
            """
        filegroup(name = 'other_label', nonexistent_attribute = 'blah')
        filegroup(name = 'label')
        
        """.trimIndent()
        )
        // bad2/BUILD is completely missing.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        update(defaultFlags().with(AnalysisTestCase.Flag.KEEP_GOING), "//foo:foo")
        assertContainsEvent(
            ("every rule of type custom_rule implicitly depends upon the target '//bad2:label', but"
                    + " this target could not be found because of: no such package 'bad2': BUILD file not"
                    + " found")
        )
        assertContainsEvent(
            ("every rule of type custom_rule implicitly depends upon the target '//bad:label', but this "
                    + "target could not be found because of: Target '//bad:label' contains an error and its"
                    + " package is in error")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun onlyAllowedRuleClassesWithWarning() {
        setRulesAvailableInTests(
            MockRule {
                MockRule.define(
                    "custom_rule",
                    attr("deps", BuildType.LABEL_LIST)
                        .allowedFileTypes()
                        .allowedRuleClassesWithWarning("genrule")
                )
            } as MockRule)

        scratch.file(
            "foo/BUILD",
            """
        genrule(
            name = 'genlib',
            srcs = [],
            outs = ['genlib.out'],
            cmd = 'echo hi > ${'$'}@')
        custom_rule(
            name = 'foo',
            deps = [':genlib'])
        
        """.trimIndent()
        )

        update("//foo")
        assertContainsEvent(
            "WARNING /workspace/foo/BUILD:6:12: in deps attribute of custom_rule rule "
                    + "//foo:foo: genrule rule '//foo:genlib' is unexpected here; continuing anyway"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExistingRule() {
        scratch.file(
            "pkg/BUILD",
            """
        genrule(name='foo',
                cmd = '',
                srcs=['a.src'],
                outs=['a.out'])
        print(existing_rule('foo')['kind'])
        print(existing_rule('bar'))
        
        """.trimIndent()
        )
        reporter.setOutputFilter(RegexOutputFilter.forPattern(java.util.regex.Pattern.compile("^//pkg")))
        update("//pkg:foo")
        assertContainsEvent("DEBUG /workspace/pkg/BUILD:5:6: genrule")
        assertContainsEvent("DEBUG /workspace/pkg/BUILD:6:6: None")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExistingRules() {
        scratch.file(
            "pkg/BUILD",
            """
        genrule(name='foo',
                cmd = '',
                srcs=['a.src'],
                outs=['a.out'])
        print(existing_rules().keys())
        
        """.trimIndent()
        )
        reporter.setOutputFilter(RegexOutputFilter.forPattern(java.util.regex.Pattern.compile("^//pkg")))
        update("//pkg:foo")
        assertContainsEvent("DEBUG /workspace/pkg/BUILD:5:6: [\"foo\"]")
    }

    companion object {
        private val ANALYSIS_EVENT_TO_STRING_PAIR: java.util.function.Function<AnalysisFailureEvent?, Pair<String?, String?>?> =
            java.util.function.Function { event: AnalysisFailureEvent? ->
                Pair.of(
                    event.getFailedTarget().getLabel().toString(),
                    event.getLegacyFailureReason().toString()
                )
            }
    }
}
