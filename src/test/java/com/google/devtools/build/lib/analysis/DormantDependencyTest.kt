// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/** Tests for dormant dependencies.  */
@RunWith(JUnit4::class)
class DormantDependencyTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun enableDormantDeps() {
        useConfiguration("--experimental_dormant_deps")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDormantLabelDisabledWithoutExperimentalFlag() {
        useConfiguration("--noexperimental_dormant_deps")

        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          fail("should not be called")

        r = rule(
          implementation = _r_impl,
          attrs = {
            "dormant": attr.dormant_label(),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("no field or method 'dormant_label'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDormantAttribute() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          print("dormant label is " + str(ctx.attr.dormant.label))
          print("dormant label list is " + str(ctx.attr.dormant_list[0].label))
          return [DefaultInfo()]

        r = rule(
          implementation = _r_impl,
          dependency_resolution_rule = True,
          attrs = {
            "dormant": attr.dormant_label(),
            "dormant_list": attr.dormant_label_list(),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")

        filegroup(name="a")
        filegroup(name="b")
        r(name="r", dormant=":a", dormant_list=[":b"])
        
        """.trimIndent()
        )

        update("//dormant:r")
        assertContainsEvent("dormant label is @@//dormant:a")
        assertContainsEvent("dormant label list is @@//dormant:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDormantAttributeComputedDefaultsFail() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          fail("should not happen")

        def computed_default():
          fail("should not happen")

        r = rule(
          implementation = _r_impl,
          dependency_resolution_rule = True,
          attrs = {
            "dormant": attr.dormant_label(default=computed_default),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("got value of type 'function'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDormantAttributeDefaultValues() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          print("dormant label is " + str(ctx.attr.dormant.label))
          print("dormant label list is " + str(ctx.attr.dormant_list[0].label))
          return [DefaultInfo()]

        r = rule(
          implementation = _r_impl,
          dependency_resolution_rule = True,
          attrs = {
            "dormant": attr.dormant_label(default="//dormant:a"),
            "dormant_list": attr.dormant_label_list(default=["//dormant:b"]),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")

        filegroup(name="a")
        filegroup(name="b")
        r(name="r")
        
        """.trimIndent()
        )

        update("//dormant:r")
        assertContainsEvent("dormant label is @@//dormant:a")
        assertContainsEvent("dormant label list is @@//dormant:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExistenceOfMaterializerParameter() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          return [DefaultInfo()]

        def _label_materializer(*args, **kwargs):
          return None

        def _list_materializer(*args, **kwargs):
          return []

        r = rule(
          implementation = _r_impl,
          attrs = {
            "_materialized": attr.label(materializer=_label_materializer),
            "_materialized_list": attr.label_list(materializer=_list_materializer),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        update("//dormant:r")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializedOnNonHiddenAttribute() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          return [DefaultInfo()]

        def _label_materializer(*args, **kwargs):
          return None

        r = rule(
          implementation = _r_impl,
          attrs = {
            "materialized": attr.label(materializer=_label_materializer),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("attribute must be private")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerAndDefaultAreIncompatible() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          return [DefaultInfo()]

        def _label_materializer(*args, **kwargs):
          return None

        def _list_materializer(*args, **kwargs):
          return []

        r = rule(
          implementation = _r_impl,
          attrs = {
            "_materialized": attr.label(
                materializer=_label_materializer,
                default=Label("//dormant:default")),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("parameters are incompatible")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerAndMandatoryAreIncompatible() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          return [DefaultInfo()]

        def _label_materializer(*args, **kwargs):
          return None

        def _list_materializer(*args, **kwargs):
          return []

        r = rule(
          implementation = _r_impl,
          attrs = {
            "_materialized": attr.label(materializer=_label_materializer, mandatory=True),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("parameters are incompatible")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerAndConfigurableAreIncompatible() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _r_impl(ctx):
          return [DefaultInfo()]

        def _label_materializer(*args, **kwargs):
          return None

        def _list_materializer(*args, **kwargs):
          return []

        r = rule(
          implementation = _r_impl,
          attrs = {
            "_materialized": attr.label(materializer=_label_materializer, configurable=True),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:r") })
        assertContainsEvent("parameters are incompatible")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializerOnAspectNotAllowed() {
        scratch.file(
            "a/a.bzl",
            """
        def _r_impl(ctx):
          fail("rule implementation should not be called")

        def _a_impl(target, ctx):
          fail("aspect implementation should not be called")

        def _materializer(ctx):
           fail("materializer should not be called")

        a = aspect(
          implementation = _a_impl,
          attrs = { "_materialized": attr.label_list(materializer=_materializer)})

        r = rule(
          implementation = _r_impl,
          attrs = { "dep": attr.label(aspects=[a])})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "r")

        filegroup(name="f")
        r(name="r", dep=":f")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:r") })
        assertContainsEvent("has a materializer, which is not allowed on aspects")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttributesOfDependencyResolutionRulesCannotBeMarkedOtherwise() {
        scratch.file(
            "a/a.bzl",
            """
        def _a_impl(ctx):
          fail("rule implementation should not be called")

        a = rule(
          implementation = _a_impl,
          attrs = {"dep": attr.label(for_dependency_resolution = False)},
          dependency_resolution_rule = True)
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("//a:a.bzl", "a")
        a(name="x")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:x") })
        assertContainsEvent("explicitly marked as not for dependency resolution")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAttributesOfDependencyResolutionRulesAreNonconfigurable() {
        scratch.file(
            "a/a.bzl",
            """
        def _a_impl(ctx):
          return [DefaultInfo()]

        a = rule(
          implementation = _a_impl,
          attrs = {"dep": attr.label()},
          dependency_resolution_rule = True)
        
        """.trimIndent()
        )

        scratch.file("a/BUILD")
        scratch.file(
            "x/BUILD",
            """
        load("//a:a.bzl", "a")
        a(name="x")
        
        """.trimIndent()
        )

        scratch.file(
            "y/BUILD",
            """
        load("//a:a.bzl", "a")
        config_setting(name = "cs", values = {"define": "cs"})
        a(name="y", dep=select({":cs": ":y1", "//conditions:default": ":y2"}))
        
        """.trimIndent()
        )

        update("//x")
        val xRule: Rule = getConfiguredTargetAndTarget("//x").getTargetForTesting() as Rule
        val depAttribute: Attribute =
            xRule.getRuleClassObject().getAttributeProvider().getAttributeByName("dep")
        assertThat(depAttribute.isConfigurable()).isFalse()
        assertThat(depAttribute.isForDependencyResolution()).isTrue()

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//y") })
        assertContainsEvent("attribute \"dep\" is not configurable")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleMustBeMarkedAsForDependencyResolution() {
        scratch.file(
            "a/a.bzl",
            """
        def _a_impl(ctx):
          return []

        a = rule(
          implementation = _a_impl,
          attrs = {"dep": attr.dormant_label()})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "a")
        a(name="a")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:a") })
        assertContainsEvent("Has dormant attributes ('dep')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoDormantDepsOnAspects() {
        scratch.file(
            "a/a.bzl",
            """
        def _impl(*args):
          fail("should not be called")

        a = aspect(
          implementation = _impl,
          attrs = { "_dormant": attr.dormant_label(default="//x:x")})

        r = rule(
          implementation = _impl,
          attrs = { "dep": attr.label(aspects=[a]) })
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "r")
        r(name="a")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a") })
        assertContainsEvent("'_dormant' has a dormant label type")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoAspectsOnDependencyResolutionRules() {
        scratch.file(
            "a/a.bzl",
            """
        def _impl(*args):
          fail("should not be called")

        a = aspect(implementation = _impl)

        r = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          attrs = {"dep": attr.label_list(aspects=[a])},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:r") })
        assertContainsEvent("cannot propagate aspects")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoToolchainsOnDependencyResolutionRules() {
        scratch.file(
            "a/a.bzl",
            """
        def _impl(*args):
          fail("should not be called")

        a = aspect(implementation = _impl)

        r = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          attrs = {"dep": attr.label_list()},
          toolchains = ["//a:nonexistent_toolchain"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":a.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:r") })
        assertContainsEvent("cannot depend on toolchains")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorOnUnmarkedRuleInAttributeAvailableInMaterializers() {
        scratch.file(
            "a/dormant.bzl",
            """
        def _component_impl(ctx):
          fail("should not be called")

        component = rule(
          implementation = _component_impl,
          dependency_resolution_rule = True,
          attrs = {
              "impl": attr.label(),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":dormant.bzl", "component")
        component(name="c", impl=":bad")
        filegroup(name="bad")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:c") })
        assertContainsEvent(
            "marked as available in materializers but prerequisite filegroup rule '//a:bad' isn't"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSubrulesCannotHaveDormantDeps() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _impl(ctx):
          fail("implementation should not be called")

        sub = subrule(implementation = _impl, attrs = {
          "_dormant": attr.dormant_label(default="//dormant:dormant"),
        })
        real = rule(implementation = _impl, attrs = {}, subrules = [sub])
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "real")
        real(name="real")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//dormant:real") })
        assertContainsEvent("subrule attributes may only be")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMarkedRulesCannotBeParents() {
        scratch.file(
            "parent/parent.bzl",
            """
        def _impl(ctx):
          fail("rule implementation should not be called")

        p = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          attrs = {
              "dormant": attr.dormant_label(),
          })
        
        """.trimIndent()
        )

        scratch.file("parent/BUILD")

        scratch.file(
            "unmarked/unmarked.bzl",
            """
        load("//parent:parent.bzl", "p")

        def _impl(ctx):
          fail("rule implementation should not be called")

        unmarked = rule(
          implementation = _impl,
          parent = p,
          attrs = {})
        
        """.trimIndent()
        )

        scratch.file(
            "unmarked/BUILD",
            """
        load(":unmarked.bzl", "unmarked")
        unmarked(name="unmarked")
        
        """.trimIndent()
        )

        scratch.file(
            "marked/marked.bzl",
            """
        load("//parent:parent.bzl", "p")

        def _impl(ctx):
          fail("rule implementation should not be called")

        marked = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          parent = p,
          attrs = {})
        
        """.trimIndent()
        )

        scratch.file(
            "marked/BUILD",
            """
        load(":marked.bzl", "marked")
        marked(name="marked")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//unmarked:unmarked") })
        assertContainsEvent("cannot be parents")

        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//marked:marked") })
        assertContainsEvent("cannot be parents")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMarkedRulesCannotHaveParents() {
        scratch.file(
            "a/dormant.bzl",
            """
        def _impl(ctx):
          fail("rule implementation should not be called")


        p = rule(
          implementation = _impl,
          attrs = {})

        r = rule(
          implementation = _impl,
          dependency_resolution_rule = True,
          parent = p,
          attrs = {})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:r") })
        assertContainsEvent("cannot have a parent")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMarkedRulesCannotCreateActions() {
        scratch.file(
            "a/dormant.bzl",
            """
        def _r_impl(ctx):
          a = ctx.actions.declare_file("f")
          ctx.actions.write(a, "foo")
          return [DefaultInfo(files=depset([a]))]

        r = rule(
          implementation = _r_impl,
          dependency_resolution_rule = True,
          attrs = {})
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":dormant.bzl", "r")
        r(name="r")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:r") })
        assertContainsEvent("shouldn't have actions")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowlistForDormantAttributes() {
        scratch.overwriteFile(
            TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + "tools/allowlists/dormant_dependency_allowlist/BUILD",
            """
        package_group(
        name = 'dormant_dependency_allowlist',
          # This rule is in @bazel_tools but must reference a package in the main repository.
          # (the value of packages= can't cross repositories at the moment)
          includes = ['@@//pkg:pkg'])
        
        """.trimIndent()
        )

        scratch.file(
            "pkg/BUILD",
            """
        package_group(name='pkg', packages=['//ok/...'])
        
        """.trimIndent()
        )

        val dormantRule: String =
            """
        def _impl(ctx):
          return [DefaultInfo()]
        r = rule(
            implementation = _impl,
            dependency_resolution_rule = True,
            attrs={"dormant": attr.dormant_label()})
        
        """.trimIndent()

        val dormantBuildFile: String =
            """
        load(":r.bzl", "r")
        filegroup(name="dep")
        r(name="r", dormant=":dep")
        
        """.trimIndent()
        scratch.file("ok/r.bzl", dormantRule)
        scratch.file("ok/BUILD", dormantBuildFile)
        scratch.file("bad/r.bzl", dormantRule)
        scratch.file("bad/BUILD", dormantBuildFile)

        update("//ok:r")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//bad:r") })
        assertContainsEvent("Non-allowlisted use of dormant dependencies")
    }

    @Throws(java.lang.Exception::class)
    private fun writeSimpleDormantRules() {
        scratch.file(
            "dormant/dormant.bzl",
            """
ComponentInfo = provider(fields = ["components"])

def _component_impl(ctx):
  current = struct(label=ctx.label, impl = ctx.attr.impl)
  transitive = [d[ComponentInfo].components for d in ctx.attr.deps]
  return [
    ComponentInfo(components = depset(direct = [current], transitive = transitive)),
  ]

component = rule(
  implementation = _component_impl,
  attrs = {
    "deps": attr.label_list(providers = [ComponentInfo]),
    "impl": attr.dormant_label(),
  },
  provides = [ComponentInfo],
  dependency_resolution_rule = True,
)

def _binary_impl(ctx):
  return [DefaultInfo(files=depset(ctx.files._impls))]

def _materializer(ctx):
  all = depset(transitive = [d[ComponentInfo].components for d in ctx.attr.components])
  selected = [c.impl for c in all.to_list() if "yes" in str(c.label)]
  return selected

binary = rule(
  implementation = _binary_impl,
  attrs = {
      "components": attr.label_list(providers = [ComponentInfo], for_dependency_resolution = True),
      "_impls": attr.label_list(materializer = _materializer),
  })

""".trimIndent()
        )

        scratch.file("dormant/BUILD")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSmoke() {
        writeSimpleDormantRules()
        scratch.file(
            "a/BUILD",
            """
        load("//dormant:dormant.bzl", "component", "binary")

        component(name="a_yes", impl=":a_impl")
        component(name="b_no", deps = [":c_yes", ":d_no"], impl=":b_impl")
        component(name="c_yes", impl=":c_impl")
        component(name="d_no", impl=":d_impl")

        binary(name="bin", components=[":a_yes", ":b_no"])
        [filegroup(name=x + "_impl", srcs=[x]) for x in ["a", "b", "c", "d"]]
        
        """.trimIndent()
        )

        update("//a:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//a:bin")
        val filesToBuild: NestedSet<Artifact?> = target.getProvider(FileProvider::class.java).getFilesToBuild()
        Truth.assertThat(ActionsTestUtil.Companion.baseArtifactNames(filesToBuild)).containsExactly("a", "c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testErrorOnUnmarkedAttribute() {
        scratch.file(
            "a/dormant.bzl",
            """
        ComponentInfo = provider(fields = ["components"])

        def _binary_impl(ctx):
          return [DefaultInfo(files=depset([]))]

        def _materializer(ctx):
          return ctx.attr.dep[ComponentInfo].components

        binary = rule(
          implementation = _binary_impl,
          attrs = {
              "dep": attr.label(),
              "_impls": attr.label_list(materializer = _materializer),
          })
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":dormant.bzl", "binary")
        binary(name="bin", dep=":dep")
        filegroup(name="dep")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:bin") })
        assertContainsEvent("not available in materializer")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMaterializersOnDependencyResolutionRulesDisallowed() {
        scratch.file(
            "a/dormant.bzl",
            """
        def _impl(ctx):
            return [DefaultInfo()]

        def _materializer(ctx):
            return []

        rr = rule(
            dependency_resolution_rule = True,
            implementation = _impl,
            attrs = {
                "_mat": attr.label_list(materializer = _materializer),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load(":dormant.bzl", "rr")
        rr(name="rr")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            TargetParsingException::class.java,
            org.junit.function.ThrowingRunnable { update("//a:rr") })
        assertContainsEvent("has a materializer which is not allowed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSplitConfigurationOnMaterializingAttribute() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        ComponentInfo = provider(fields = ["impls"])

        def _component_impl(ctx):
          return [ComponentInfo(impls = ctx.attr.impls)]

        component = rule(
          implementation = _component_impl,
          attrs = { "impls": attr.dormant_label_list() },
          provides = [ComponentInfo],
          dependency_resolution_rule = True,
        )

        def _binary_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files._impls))]

        def _materializer(ctx):
          return ctx.attr.component[ComponentInfo].impls

        def _transition_impl(settings, attr):
          return [
            {"//command_line_option:compilation_mode": "dbg"},
            {"//command_line_option:compilation_mode": "opt"},
          ]

        _transition = transition(
          implementation = _transition_impl,
          inputs = [],
          outputs = ["//command_line_option:compilation_mode"])

        binary = rule(
          implementation = _binary_impl,
          attrs = {
              "component": attr.label(
                  providers = [ComponentInfo], for_dependency_resolution = True),
              "_impls": attr.label_list(materializer = _materializer, cfg = _transition),
          })
          """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "component", "binary")
        genrule(name="g1", srcs=[], outs=["g1o"], cmd="echo GO >${'$'}@")
        genrule(name="g2", srcs=[], outs=["g2o"], cmd="echo GO >${'$'}@")
        component(name="c", impls=[":g1", ":g2"])
        binary(name="b", component=":c")
        
        """.trimIndent()
        )

        update("//dormant:b")
        val target: ConfiguredTarget = getConfiguredTarget("//dormant:b")
        val filesToBuild: NestedSet<Artifact?> = target.getProvider(FileProvider::class.java).getFilesToBuild()

        // Two deps in two configurations must be four artifacts altogether
        assertThat(filesToBuild.toList()).hasSize(4)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAspectOnDependencyResolutionRule() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _propagator_impl(ctx):
          return [DefaultInfo(files=depset(ctx.files.dep))]

        def _asp_impl(target, ctx):
          o = ctx.actions.declare_file("o")
          ctx.actions.write(o, "CONTENT")
          return [DefaultInfo(files=depset([o]))]

        def _dep_impl(ctx):
          return [DefaultInfo()]

        dep = rule(
          implementation = _dep_impl,
          dependency_resolution_rule=True,
          attrs = {})

        asp = aspect(implementation = _asp_impl)

        propagator = rule(
            implementation = _propagator_impl,
            attrs = {"dep": attr.label(for_dependency_resolution=True, aspects=[asp])})
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "propagator", "dep")
        dep(name="dep")
        propagator(name="propagator", dep=":dep")
        
        """.trimIndent()
        )

        update("//dormant:propagator")
        val propagator: ConfiguredTarget = getConfiguredTarget("//dormant:propagator")
        val filesToBuild: NestedSet<Artifact?> = propagator.getProvider(FileProvider::class.java).getFilesToBuild()
        Truth.assertThat(ActionsTestUtil.Companion.baseArtifactNames(filesToBuild)).containsExactly("o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLabelInMaterializer() {
        scratch.file(
            "dormant/dormant.bzl",
            """
        def _binary_impl(ctx):
          return [DefaultInfo()]

        def _materializer(ctx):
          print("MATERIALIZER LABEL " + str(ctx.label))
          return []

        binary = rule(
          implementation = _binary_impl,
          attrs = { "_materialized": attr.label_list(materializer = _materializer) })
        
        """.trimIndent()
        )

        scratch.file(
            "dormant/BUILD",
            """
        load(":dormant.bzl", "binary")
        binary(name="binary")
        
        """.trimIndent()
        )

        update("//dormant:binary")
        assertContainsEvent("MATERIALIZER LABEL @@//dormant:binary")
    }
}
