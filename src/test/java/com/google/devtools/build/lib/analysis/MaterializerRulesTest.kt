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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

@RunWith(JUnit4::class)
class MaterializerRulesTest : AnalysisTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun enableDormantDeps() {
        useConfiguration(
            "--experimental_dormant_deps", "--incompatible_package_group_has_public_syntax"
        )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun writeMaterializerRulesAllowlist() {
        writeMaterializerRulesAllowlist(true, false)
    }

    @Throws(java.lang.Exception::class)
    fun writeMaterializerRulesAllowlist(
        materializerRuleAllowed: Boolean, allowRealDeps: Boolean
    ) {
        scratch.overwriteFile(
            TestConstants.TOOLS_REPOSITORY_SCRATCH
                    + "tools/allowlists/materializer_rule_allowlist/BUILD",
            """
        package_group(
            name = 'materializer_rule_allowlist',
            packages = [%s],
        )

        package_group(
            name = 'materializer_rule_real_deps_allowlist',
            packages = [%s],
        )
        
        """
                .trimIndent()
                .formatted(
                    if (materializerRuleAllowed) "\"public\"" else "", if (allowRealDeps) "\"public\"" else ""
                )
        )
    }

    /** Tests materializing dormant deps through materializer rules.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun basicMaterializerRule_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":a_yes",
        ":b_yes",
        ":a_no",
        ":b_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.txt", "a_yes.txt", "b_yes.txt", "zzz.txt")
    }

    /** Tests that multiple materializer rules in an attribute works.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMaterializerRules_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":component_selector_2",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":a_yes",
        ":b_yes",
        ":a_no",
        ":b_no",
    ],
)

component_selector(
    name = "component_selector_2",
    all_components = [
        ":c_yes",
        ":d_yes",
        ":c_no",
        ":d_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "c_yes")
component(name = "d_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.txt", "a_yes.txt", "b_yes.txt", "c_yes.txt", "d_yes.txt", "zzz.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleMaterializersReturnSameTarget_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":component_selector_2",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":a_yes",
        ":b_yes", # <- overlap
        ":c_yes", # <- overlap
        ":a_no",
        ":b_no",
    ],
)

component_selector(
    name = "component_selector_2",
    all_components = [
        ":b_yes", # <- overlap
        ":c_yes", # <- overlap
        ":d_yes",
        ":c_no",
        ":d_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "c_yes")
component(name = "d_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.txt", "a_yes.txt", "b_yes.txt", "c_yes.txt", "d_yes.txt", "zzz.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerForDependencyResolutionRule_works() {
        scratch.file(
            "defs.bzl",
            """
# Module Implementation ######################################

ModuleImplementationInfo = provider(fields = ["output"])

def _module_implementation_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ModuleImplementationInfo(output = f)

module_implementation = rule(
    implementation = _module_implementation_impl,
    provides = [ModuleImplementationInfo],
)

# Module ########################################

# A provider containing a number of modules
ModulesInfo = provider(fields = ["interface", "modules"])

# A provider describing a single module
SingleModuleInfo = provider(fields = ["interface", "implementation"])

# A module collects every other module in its transitive closure.
def _module_impl(ctx):
    me = SingleModuleInfo(
        interface = ctx.attr.interface,
        implementation = ctx.attr.implementation,
    )
    modules = depset(direct = [me], transitive = [dep[ModulesInfo].modules for dep in ctx.attr.deps])
    return ModulesInfo(interface = ctx.attr.interface, modules = modules)

module = rule(
    implementation = _module_impl,
    dependency_resolution_rule = True,  # Accessible from materializers
    attrs = {
        "interface": attr.string(),
        "implementation": attr.dormant_label(),
        "deps": attr.label_list(),
    },
)

# Materializer rule #############################

def _module_materializer_impl(ctx):
    selected = []
    for m in ctx.attr.all_modules:
        mi = m[ModulesInfo]
        if mi.interface == "yes":
            selected.extend([m.implementation for m in mi.modules.to_list()])
    return MaterializedDepsInfo(deps = selected)

module_materializer = materializer_rule(
    implementation = _module_materializer_impl,
    attrs = {
        "all_modules": attr.label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [m[ModuleImplementationInfo].output for m in ctx.attr.modules]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "modules": attr.label_list(providers = [ModuleImplementationInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "module_implementation", "module", "module_materializer", "binary")

binary(
    name = "bin",
    modules = [
        ":module_materializer",
    ],
)

module_materializer(
    name = "module_materializer",
    all_modules = [
        ":foo_a",
        ":foo_b",
        ":bar_a",
        ":bar_b",
    ],
)

module(name = "foo_a", interface = "yes", implementation = ":foo_a_impl", deps = [":baz_a"])
module(name = "foo_b", interface = "yes", implementation = ":foo_b_impl", deps = [":baz_b"])
module(name = "bar_a", interface = "no", implementation = ":bar_a_impl")
module(name = "bar_b", interface = "no", implementation = ":bar_b_impl")

module(name = "baz_a", implementation = ":baz_a_impl")
module(name = "baz_b", implementation = ":baz_b_impl")

module_implementation(name = "foo_a_impl")
module_implementation(name = "foo_b_impl")
module_implementation(name = "bar_a_impl")
module_implementation(name = "bar_b_impl")
module_implementation(name = "baz_a_impl")
module_implementation(name = "baz_b_impl")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("foo_a_impl.txt", "foo_b_impl.txt", "baz_a_impl.txt", "baz_b_impl.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerWithRealDeps_throwsError() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.label_list(),  # Only allows For Dependency Resolution rules
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [ctx.attr.dep[ComponentInfo].output]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo], mandatory = True),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [":a_yes", ":b_no", ":c_no", ":d_no"],
)

component(name = "a_yes")
component(name = "b_no")
component(name = "c_no")
component(name = "d_no")

""".trimIndent()
        )

        this.reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            ("in all_components attribute of component_selector rule //:component_selector: materializer"
                    + " rules can depend on only dependency resolution rules via non-dormant attributes;"
                    + " all_components is a non-dormant attribute, and //:a_yes is not a dependency"
                    + " resolution rule")
        )
        assertContainsEvent(
            ("in all_components attribute of component_selector rule //:component_selector: materializer"
                    + " rules can depend on only dependency resolution rules via non-dormant attributes;"
                    + " all_components is a non-dormant attribute, and //:b_no is not a dependency"
                    + " resolution rule")
        )
        assertContainsEvent(
            ("in all_components attribute of component_selector rule //:component_selector: materializer"
                    + " rules can depend on only dependency resolution rules via non-dormant attributes;"
                    + " all_components is a non-dormant attribute, and //:c_no is not a dependency"
                    + " resolution rule")
        )
        assertContainsEvent(
            ("in all_components attribute of component_selector rule //:component_selector: materializer"
                    + " rules can depend on only dependency resolution rules via non-dormant attributes;"
                    + " all_components is a non-dormant attribute, and //:d_no is not a dependency"
                    + " resolution rule")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dormantDepsNotAnalyzed() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
)

# Fail Component ######################################

def _fail_component_impl(ctx):
    fail("component " + ctx.label.name + " should not be analyzed")

fail_component = rule(
    implementation = _fail_component_impl,
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if not "fail" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "fail_component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":component_selector",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":a_fail",
        ":b",
        ":c_fail",
    ],
)

fail_component(name = "a_fail")
component(name = "b")
fail_component(name = "c_fail")

""".trimIndent()
        )

        // No assertion needed, the test passes if update() does not throw an exception.
        update("//:bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectsThroughMaterializerRules_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components_dormant:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components_dormant": attr.dormant_label_list(),
    },
)

# Aspect #########################################

AspectInfo = provider(fields = ["info_artifact"])

def _mt_aspect_impl(target, ctx):
    artifact = ctx.actions.declare_file(target.label.name + ".info")
    ctx.actions.write(artifact, str(target.label))
    return AspectInfo(info_artifact = artifact)

mt_aspect = aspect(
    implementation = _mt_aspect_impl,
    attrs = {
        # Context creation code is shared between aspects and rules, so having this aspect
        # dependency in the test ensure that materializer dependency validation is only performed
        # for rules and not for aspects.
        "_tool": attr.label(
            default = Label("//:aspect_tool"),
            cfg = "exec",
        ),
    }
)

# Binary #########################################

def _binary_impl(ctx):
    for dep in ctx.attr.deps:
        print(dep[AspectInfo])
    return DefaultInfo(files = depset(direct = [dep[AspectInfo].info_artifact for dep in ctx.attr.deps]))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo], aspects = [mt_aspect]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin_dormant",
    deps = [
        ":aaa",
        ":component_selector_dormant",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector_dormant",
    all_components_dormant = [
        ":a_yes",
        ":b_yes",
        ":a_no",
        ":b_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "zzz")

genrule(
    name = "aspect_tool",
    outs = ["tool"],
    executable = True,
    cmd = "echo 'touch ${'$'}${'$'}1' > ${'$'}@",
)

""".trimIndent()
        )

        update("//:bin_dormant")
        val targetDormant: ConfiguredTarget = getConfiguredTarget("//:bin_dormant")
        val filesToBuildDormant: NestedSet<Artifact?>? =
            targetDormant.getProvider(FileProvider::class.java).getFilesToBuild()
        // The .info files come from the aspect, and only the files from the selected dormant deps
        // should be returned.
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuildDormant))
            .containsExactly("aaa.info", "a_yes.info", "b_yes.info", "zzz.info")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerToMaterializer_throwsError() {
        scratch.file(
            "defs.bzl",
            """

#################################################
# Component impl

ComponentImplInfo = provider()

def _component_impl_impl(ctx):
    return ComponentImplInfo()

component_impl = rule(
    implementation = _component_impl_impl,
)

#################################################
# Component selector

def _component_selector_impl(ctx):
    return MaterializedDepsInfo(deps = ctx.attr.all_components)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

#################################################
# Binary

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentImplInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component_impl", "component_selector", "binary")

binary(
    name = "bin",
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [":impl_selector"],
)

component_selector(
    name = "impl_selector",
    all_components = [":a_impl"],
)

component_impl(name="a_impl")

""".trimIndent()
        )

        this.reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            ("Error while evaluating materializer target in attribute 'deps' of target '//:bin': "
                    + "Materializer target //:component_selector depends on another materializer target "
                    + "//:impl_selector, which is not supported.")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerToMaterializerToMaterializer_throwsError() {
        scratch.file(
            "defs.bzl",
            """
#################################################
# Component

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

#################################################
# Component selector

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

#################################################
# Binary

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [":component_selector2_yes", ":a_yes"],
)

component_selector(
    name = "component_selector2_yes",
    all_components = [":component_selector3_yes", ":b_yes", ":component_selector4_yes", ":x_no"],
)

component_selector(
    name = "component_selector3_yes",
    all_components = [":c_yes", ":d_yes", "e_yes", ":f_yes", ":y_no"],
)

component_selector(
    name = "component_selector4_yes",
    all_components = [":g_yes", ":h_yes", ":z_no"],
)

component(name = "a_yes")
component(name = "b_yes")
component(name = "c_yes")
component(name = "d_yes")
component(name = "e_yes")
component(name = "f_yes")
component(name = "g_yes")
component(name = "h_yes")
component(name = "x_no")
component(name = "y_no")
component(name = "z_no")

""".trimIndent()
        )

        this.reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            ("Error while evaluating materializer target in attribute 'deps' of target '//:bin':"
                    + " Materializer target //:component_selector depends on another materializer target"
                    + " //:component_selector2_yes, which is not supported.")
        )
    }

    /**
     * Tests that an alias can point to a materializer rule (i.e. a materializer rule can go through
     * an alias). This is particularly important for materializer rules that materialize more than one
     * label, because the "actual" attribute of alias() is a single label attribute, so putting a
     * one-to-many materializer there would normally be disallowed.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasToMaterializerRule_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components_dormant:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components_dormant": attr.dormant_label_list(),
    },
)

# Aspect #########################################

AspectInfo = provider(fields = ["info_artifact"])

def _mt_aspect_impl(target, ctx):
    if ctx.rule.kind != "component":
        return []
    print("aspect visiting target: " + str(target.label))
    artifact = ctx.actions.declare_file(target.label.name + ".info")
    ctx.actions.write(artifact, str(target.label))
    return AspectInfo(info_artifact = artifact)

mt_aspect = aspect(
    implementation = _mt_aspect_impl,
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo(files = depset(direct = [dep[AspectInfo].info_artifact for dep in ctx.attr.deps]))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo], aspects = [mt_aspect]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

# Dormant through single alias ####################################

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector_alias",
        ":zzz",
    ],
)

alias(
    name = "component_selector_alias",
    actual = ":component_selector",
)

# Dormant through alias chain ####################################

binary(
    name = "bin_alias_chain",
    deps = [
        ":aaa",
        ":component_selector_alias_alias",
        ":zzz",
    ],
)

alias(
    name = "component_selector_alias_alias",
    actual = ":component_selector_alias",
)

# Materializer rules #############################

component_selector(
    name = "component_selector",
    all_components_dormant = [
        ":a_yes",
        ":b_yes",
        ":a_no",
        ":b_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.info", "a_yes.info", "b_yes.info", "zzz.info")
        eventCollector.clear()

        cleanSkyframe()
        update("//:bin_alias_chain")
        val targetAliasChain: ConfiguredTarget = getConfiguredTarget("//:bin_alias_chain")
        val filesToBuildAliasChain: NestedSet<Artifact?>? =
            targetAliasChain.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuildAliasChain))
            .containsExactly("aaa.info", "a_yes.info", "b_yes.info", "zzz.info")

        // Especially when going through an alias chain, an aspect should still visit the nodes once.
        assertContainsEventWithFrequency("aspect visiting target: @@//:aaa", 1)
        assertContainsEventWithFrequency("aspect visiting target: @@//:zzz", 1)
        assertContainsEventWithFrequency("aspect visiting target: @@//:b_yes", 1)
        assertContainsEventWithFrequency("aspect visiting target: @@//:a_yes", 1)
        assertDoesNotContainEvent("aspect visiting target: @@//:a_no")
        assertDoesNotContainEvent("aspect visiting target: @@//:b_no")
    }

    /** Tests that a materializer can point to an alias and the final target is materialized.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerToAlias_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components_dormant:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components_dormant": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components_dormant = [
        ":a_yes_alias",
        ":b_yes_alias",
        ":a_no",
        ":b_no",
        ":b_no_alias",
    ],
)

alias(name = "a_yes_alias", actual = "a")
alias(name = "b_yes_alias", actual = "b")
alias(name = "b_no_alias", actual = "b_no")

component(name = "aaa")
component(name = "a")
component(name = "b")
component(name = "a_no")
component(name = "b_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.txt", "a.txt", "b.txt", "zzz.txt")
    }

    /**
     * Tests alias -> materializer -> alias -> materializer -> alias throws an error that a
     * materializer depends on a materializer.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aliasToMaterializerToAliasToMaterializerToAlias_throwsError() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    return MaterializedDepsInfo(deps = [ctx.attr.component])

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "component": attr.dormant_label(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":alias_to_materializer_to_alias_to_materializer_to_alias",
        ":zzz",
    ],
)

alias(
    name = "alias_to_materializer_to_alias_to_materializer_to_alias",
    actual = ":materializer_to_alias_to_materializer_to_alias",
)

component_selector(
    name = "materializer_to_alias_to_materializer_to_alias",
    component = ":alias_to_materializer_to_alias",
)

alias(
    name = "alias_to_materializer_to_alias",
    actual = ":materializer_to_alias",
)

component_selector(
    name = "materializer_to_alias",
    component = ":a_alias",
)

alias(
    name = "a_alias",
    actual = ":a",
)

component(name = "aaa")
component(name = "a")
component(name = "zzz")

""".trimIndent()
        )

        this.reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            ("Error while evaluating materializer target in attribute 'deps' of target '//:bin':"
                    + " Materializer target //:materializer_to_alias_to_materializer_to_alias depends on"
                    + " another materializer target //:materializer_to_alias, which is not supported")
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeMaterializerSplitTransitionBzlFile() {
        scratch.file(
            "defs.bzl",
            """
# Component selector setting ###########################################

ComponentSelectorProvider = provider(fields = ["selector"])

def _component_selector_setting_impl(ctx):
    return ComponentSelectorProvider(selector = ctx.build_setting_value)

component_selector_setting = rule(
    implementation = _component_selector_setting_impl,
    build_setting = config.string()
)

# Component transition

def _component_selector_setting_transition_impl(settings, attr):
    return [
        {"//:component_selector_setting" : "foo"},
        {"//:component_selector_setting" : "bar"},
    ]

component_transition = transition(
    implementation = _component_selector_setting_transition_impl,
    inputs = [],
    outputs = ["//:component_selector_setting"],
)

# Component ############################################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector ###################################################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary ###############################################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo], cfg = component_transition),
    },
)

""".trimIndent()
        )
    }

    /** Tests a materializer rule under a split transition.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRulesUnderSplitTransition_works() {
        writeMaterializerSplitTransitionBzlFile()

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary", "component_selector_setting")

component_selector_setting(
    name = "component_selector_setting",
    build_setting_default = "foo",
)

binary(
    name = "bin",
    # has a split transition!
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":yes_a",
        ":yes_b",
        ":no_c",
        ":no_d",
    ],
)

component(name = "yes_a")
component(name = "yes_b")
component(name = "no_c")
component(name = "no_d")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        val artifactNames: MutableList<String?>? = ActionsTestUtil.baseArtifactNames(filesToBuild)
        Truth.assertThat(artifactNames).containsAtLeast("yes_a.txt", "yes_b.txt")
        Truth.assertThat(artifactNames).containsNoneOf("no_c.txt", "no_d.txt")
    }

    /**
     * Tests a materializer rule under a split transition with a select() input to the materializer.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRulesUnderSplitTransitionAndSelect_works() {
        writeMaterializerSplitTransitionBzlFile()

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary", "component_selector_setting")

component_selector_setting(
    name = "component_selector_setting",
    build_setting_default = "foo",
)

config_setting(
    name = "config_setting_foo",
    flag_values = {"//:component_selector_setting": "foo"},
)

config_setting(
    name = "config_setting_bar",
    flag_values = {"//:component_selector_setting": "bar"},
)

binary(
    name = "bin",
    # has a split transition!
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = select({
        ":config_setting_foo": [
            ":yes_a",
            ":yes_b",
            ":no_f",
            ":no_g",
        ],
        ":config_setting_bar": [
            ":yes_c",
            ":yes_d",
            ":yes_e",
            ":no_f",
            ":no_g",
        ],
    }),
)

component(name = "yes_a")
component(name = "yes_b")
component(name = "yes_c")
component(name = "yes_d")
component(name = "yes_e")
component(name = "no_f")
component(name = "no_g")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        val artifactNames: MutableList<String?>? = ActionsTestUtil.baseArtifactNames(filesToBuild)
        Truth.assertThat(artifactNames)
            .containsAtLeast("yes_a.txt", "yes_b.txt", "yes_c.txt", "yes_d.txt", "yes_e.txt")
        Truth.assertThat(artifactNames).containsNoneOf("no_f.txt", "no_g.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRulesPropagatesValidationActions_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)

    validation_output = ctx.actions.declare_file(ctx.attr.name + ".validation")
    ctx.actions.run_shell(
        inputs = [f],
        outputs = [validation_output],
        command = "touch ${'$'}1",
        arguments = [validation_output.path],
    )

    return [
        ComponentInfo(output = f),
        OutputGroupInfo(_validation = depset([validation_output])),
    ]

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":a_yes",
        ":b_yes",
        ":a_no",
        ":b_no",
    ],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "a_no")
component(name = "b_no")
component(name = "zzz")

""".trimIndent()
        )

        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val outputGroupInfo: OutputGroupInfo = target.get(OutputGroupInfo.STARLARK_CONSTRUCTOR)
        val validationOutputs: NestedSet<Artifact?>? =
            outputGroupInfo.getOutputGroup(OutputGroupInfo.VALIDATION)
        val artifactNames: MutableList<String?>? = ActionsTestUtil.baseArtifactNames(validationOutputs)
        Truth.assertThat(artifactNames)
            .containsExactly(
                "aaa.validation", "a_yes.validation", "b_yes.validation", "zzz.validation"
            )
    }

    /**
     * Tests that a materializer rule getting read by a materializer in a materializer attribute
     * resolves.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerTargetInMaterializerAttribute_works() {
        scratch.file(
            "defs.bzl",
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

######################################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

######################################

def _binary_impl(ctx):
    return [DefaultInfo(files=depset(ctx.files._impls))]

def _materializer(ctx):
    for c in ctx.attr.components:
        print(c)
    return []

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "components": attr.label_list(for_dependency_resolution = True),
        "_impls": attr.label_list(materializer = _materializer),
    }
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component_selector", "component", "binary")

binary(
    name = "materializer_attr_bin",
    components = [":a_yes", ":b_no", ":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [
        ":c_yes",
        ":d_no",
    ],
)

component(name="a_yes")
component(name="b_no")
component(name="c_yes")
component(name="d_no")

""".trimIndent()
        )

        update("//:materializer_attr_bin")
        // no ending ">" because after the label are the providers which are not important here.
        assertContainsEvent("<target //:a_yes")
        assertContainsEvent("<target //:b_no")
        assertContainsEvent("<target //:c_yes")
        // This was not selected by the materializer target, so it should not show up to the
        // materializer attribute.
        assertDoesNotContainEvent("<target //:d_no")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun singletonListOfMaterializedDepsInfo_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return [MaterializedDepsInfo(deps = selected)]

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [":a_yes", ":b_yes", ":c_no", ":d_no"],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )
        update("//:bin")
        val target: ConfiguredTarget = getConfiguredTarget("//:bin")
        val filesToBuild: NestedSet<Artifact?>? = target.getProvider(FileProvider::class.java).getFilesToBuild()
        assertThat(ActionsTestUtil.baseArtifactNames(filesToBuild))
            .containsExactly("aaa.txt", "a_yes.txt", "b_yes.txt", "zzz.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleDocsAttr_works() {
        scratch.file(
            "defs.bzl",
            """
materializer_rule_with_doc = materializer_rule(
    implementation = lambda ctx: MaterializedDepsInfo(deps = []),
    doc = "This is a doc string",
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "materializer_rule_with_doc")

materializer_rule_with_doc(
    name = "materializer_rule_with_doc",
)

""".trimIndent()
        )

        update("//:materializer_rule_with_doc")
        val target: ConfiguredTargetAndData = getConfiguredTargetAndData("//:materializer_rule_with_doc")
        assertThat(
            (target.getTargetForTesting() as Rule).getRuleClassObject().starlarkDocumentation
        )
            .isEqualTo("This is a doc string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleInWithDefaultApplicableLicenses_works() {
        scratch.file(
            "fake_licenses/BUILD",
            """
filegroup(
    name = "license",
    srcs = ["LICENSE"],
)

""".trimIndent()
        )

        scratch.file(
            "defs.bzl",
            """
mr = materializer_rule(
    implementation = lambda ctx: MaterializedDepsInfo(deps = []),
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "mr")

package(
    default_applicable_licenses = ["//fake_licenses:license"],
)

mr(
    name = "materializer_rule",
)

""".trimIndent()
        )

        update("//:materializer_rule")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerAllowList_nonAllowedThrowsError() {
        writeMaterializerRulesAllowlist(false, false)

        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [dep[ComponentInfo].output for dep in ctx.attr.deps]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [
        ":aaa",
        ":component_selector",
        ":zzz",
    ],
)

component_selector(
    name = "component_selector",
    all_components = [":a_yes", ":b_no", ":c_no", ":d_no"],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_no")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        this.reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            "in component_selector rule //:component_selector: Non-allowlisted use of materializer"
                    + " rule"
        )
    }

    /**
     * Tests that an error is thrown when a materializer rule returns something other than a
     * MaterializedDepsInfo provider or singleton list thereof.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerReturnsNonMaterializedDepsInfo_throwsError() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def make_materializer_rule(impl):
    return materializer_rule(
        implementation = impl,
        attrs = {
            "all_components": attr.dormant_label_list(),
        },
    )

materializer_wrong_object_DefaultInfo = make_materializer_rule(lambda ctx: DefaultInfo())
materializer_wrong_object_int = make_materializer_rule(lambda ctx: 1)
materializer_wrong_object_in_list = make_materializer_rule(lambda ctx: [DefaultInfo()])
materializer_wrong_object_in_list_int = make_materializer_rule(lambda ctx: [1])
materializer_wrong_list_size = make_materializer_rule(lambda ctx: [DefaultInfo(), ComponentInfo()])
materializer_wrong_list_size_DefaultInfo_int = make_materializer_rule(lambda ctx: [DefaultInfo(), 1])

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(
    ":defs.bzl",
    "component",
    "binary",
    "materializer_wrong_object_DefaultInfo",
    "materializer_wrong_object_int",
    "materializer_wrong_object_in_list",
    "materializer_wrong_object_in_list_int",
    "materializer_wrong_list_size",
    "materializer_wrong_list_size_DefaultInfo_int",
)

binary(name = "bin_materializer_wrong_object_DefaultInfo", deps = [":materializer_wrong_object_DefaultInfo"])
materializer_wrong_object_DefaultInfo(name = "materializer_wrong_object_DefaultInfo", all_components = [":a_yes", ":b_yes"])

binary(name = "bin_materializer_wrong_object_int", deps = [":materializer_wrong_object_int"])
materializer_wrong_object_int(name = "materializer_wrong_object_int", all_components = [":a_yes", ":b_yes"])

binary(name = "bin_materializer_wrong_object_in_list", deps = [":materializer_wrong_object_in_list"])
materializer_wrong_object_in_list(name = "materializer_wrong_object_in_list", all_components = [":a_yes", ":b_yes"])

binary(name = "bin_materializer_wrong_object_in_list_int", deps = [":materializer_wrong_object_in_list_int"])
materializer_wrong_object_in_list_int(name = "materializer_wrong_object_in_list_int", all_components = [":a_yes", ":b_yes"])

binary(name = "bin_materializer_wrong_list_size", deps = [":materializer_wrong_list_size"])
materializer_wrong_list_size(name = "materializer_wrong_list_size", all_components = [":a_yes", ":b_yes"])

binary(name = "bin_materializer_wrong_list_size_DefaultInfo_int", deps = [":materializer_wrong_list_size_DefaultInfo_int"])
materializer_wrong_list_size_DefaultInfo_int(name = "materializer_wrong_list_size_DefaultInfo_int", all_components = [":a_yes", ":b_yes"])

component(name = "a_yes")
component(name = "b_yes")

""".trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_object_DefaultInfo") })
        assertContainsEvent(
            "Materializer rules must return exactly one MaterializedDepsInfo provider, but got"
                    + " [DefaultInfo]"
        )
        eventCollector.clear()

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_object_int") })
        assertContainsEvent("Rule should return a struct or a list, but got int")
        eventCollector.clear()

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_object_in_list") })
        assertContainsEvent(
            "Materializer rules must return exactly one MaterializedDepsInfo provider, but got"
                    + " [DefaultInfo]"
        )
        eventCollector.clear()

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_object_in_list_int") })
        assertContainsEvent(
            "at index 0 of result of rule implementation function, got element of type int, want Info"
        )
        eventCollector.clear()

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_list_size") })
        assertContainsEvent(
            "Materializer rules must return exactly one MaterializedDepsInfo provider, but got"
                    + " [DefaultInfo, ComponentInfo]"
        )
        eventCollector.clear()

        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin_materializer_wrong_list_size_DefaultInfo_int") })
        assertContainsEvent(
            "at index 1 of result of rule implementation function, got element of type int, want Info"
        )
        eventCollector.clear()
    }

    /** Tests that an error is thrown when a materializer goes into a single-label-typed attribute.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleInSingleLabelAttribute_throwsError() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider(fields = ["output"])

def _component_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    ctx.actions.write(f, ctx.label.name)
    return ComponentInfo(output = f)

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    files = [ctx.attr.dep[ComponentInfo].output]
    return DefaultInfo(files = depset(direct = files))

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "dep": attr.label(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    dep = ":component_selector",
)

component_selector(
    name = "component_selector",
    all_components = [":a_yes", ":b_yes", ":c_no", ":d_no"],
)

component(name = "aaa")
component(name = "a_yes")
component(name = "b_yes")
component(name = "c_no")
component(name = "d_no")
component(name = "zzz")

""".trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            ("Error while evaluating materializer target in attribute 'dep' of target '//:bin': Target"
                    + " //:component_selector is a materializer target but attribute 'dep' is a label, not"
                    + " a label list")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun usingActionsInMaterializerRule_throwsError() {
        scratch.file(
            "defs.bzl",
            """
def _component_selector_impl(ctx):
    f = ctx.actions.declare_file(ctx.label.name + ".txt")
    return MaterializedDepsInfo(deps = [])

component_selector = materializer_rule(
    implementation = _component_selector_impl,
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component_selector")
component_selector(
    name = "component_selector",
)

""".trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:component_selector") })
        assertContainsEvent("ctx.actions is not available in materializer rules")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun wrongObjectInMaterializedDepsInfo_throwsError() {
        scratch.file(
            "defs.bzl",
            """
def _component_impl(ctx):
    return []

component = rule(
    implementation = _component_impl,
)

def _component_selector_impl(ctx):
    deps = ctx.attr.components + [1]
    return MaterializedDepsInfo(deps = deps)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "components": attr.dormant_label_list(),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component_selector", "component")

component_selector(
    name = "component_selector",
    components = [":a", ":b"],
)

component(name = "a")
component(name = "b")

""".trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:component_selector") })
        assertContainsEvent(
            ("MaterializedDepsInfo dependencies must be Target objects (retrieved from"
                    + " ctx.attr) or DormantDependency objects (from attr.dormant_label() or"
                    + " attr.dormant_label_list() attributes), but got int at index 2")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializedRuleWithWrongProvider_throwsError() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider()

def _component_impl(ctx):
    return DefaultInfo()

component = rule(
    implementation = _component_impl,
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "binary")

binary(
    name = "bin",
    deps = [":component_selector"],
)

component_selector(
    name = "component_selector",
    all_components = [":a_yes", ":b_yes"],
)

component(name = "a_yes")
component(name = "b_yes")

""".trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//:bin") })
        assertContainsEvent(
            "in deps attribute of binary rule //:bin: '//:a_yes' does not have mandatory providers:"
                    + " 'ComponentInfo'"
        )
        assertContainsEvent(
            "in deps attribute of binary rule //:bin: '//:b_yes' does not have mandatory providers:"
                    + " 'ComponentInfo'"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun writeVisibilityDefsBzlFile() {
        scratch.file(
            "defs.bzl",
            """
# Component ######################################

ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector #############################

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "foo" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

# Binary #########################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(providers = [ComponentInfo]),
    },
)

""".trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleVisibilityViolation_throwsError() {
        writeVisibilityDefsBzlFile()
        scratch.file("BUILD", "")

        scratch.file(
            "binary1/BUILD",
            """
load("//:defs.bzl", "binary")

binary(
    name = "bin1",
    deps = [
        "//components:component_selector",
    ],
)

""".trimIndent()
        )

        scratch.file(
            "binary2/BUILD",
            """
load("//:defs.bzl", "binary")

binary(
    name = "bin2",
    deps = [
        "//components:component_selector",
    ],
)

""".trimIndent()
        )

        scratch.file(
            "components/BUILD",
            """
load("//:defs.bzl", "component", "component_selector", "binary")

component_selector(
    name = "component_selector",
    all_components = [
        ":foo_a",
        ":foo_b",
        ":bar_a",
        ":bar_b",
    ],
    visibility = ["//binary1:__pkg__"],
)

component(name = "foo_a", visibility = ["//:__subpackages__"])
component(name = "foo_b", visibility = ["//:__subpackages__"])
component(name = "bar_a", visibility = ["//:__subpackages__"])
component(name = "bar_b", visibility = ["//:__subpackages__"])

""".trimIndent()
        )

        // The materializer target is visible to bin1.
        update("//binary1:bin1")

        // The materializer target is not visible to bin2.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//binary2:bin2") })
        assertContainsEvent(
            """
ERROR /workspace/binary2/BUILD:3:7: in binary rule //binary2:bin2: Visibility error:
target '//components:component_selector' is not visible from
target '//binary2:bin2'

""".trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleMaterializedTargetVisibilityViolation_throwsError() {
        scratch.file("BUILD", "")

        writeVisibilityDefsBzlFile()

        scratch.file(
            "binary/BUILD",
            """
load("//:defs.bzl", "binary")

binary(
    name = "bin",
    deps = [
        "//components:component_selector",
    ],
)

""".trimIndent()
        )

        scratch.file(
            "components/BUILD",
            """
load("//:defs.bzl", "component", "component_selector", "binary")

component_selector(
    name = "component_selector",
    all_components = [
        ":foo_a",
        ":foo_b",
        ":bar_a",
        ":bar_b",
    ],
    visibility = ["//binary:__pkg__"],
)

component(name = "foo_a", visibility = ["//visibility:private"])
component(name = "foo_b", visibility = ["//visibility:private"])
component(name = "bar_a", visibility = ["//visibility:private"])
component(name = "bar_b", visibility = ["//visibility:private"])

""".trimIndent()
        )

        // The materializer target is visible to bin, but the materialized targets are not.
        reporter.removeHandler(FoundationTestCase.failFastHandler)
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { update("//binary:bin") })
        assertContainsEvent(
            """
ERROR /workspace/binary/BUILD:3:7: in binary rule //binary:bin: Visibility error:
target '//components:foo_a' is not visible from
target '//binary:bin'

""".trimIndent()
        )
        assertContainsEvent(
            """
ERROR /workspace/binary/BUILD:3:7: in binary rule //binary:bin: Visibility error:
target '//components:foo_b' is not visible from
target '//binary:bin'

""".trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleUnderFailureAnalysisTest_works() {
        scratch.file(
            "defs.bzl",
            """
ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

def _component_selector_impl(ctx):
    selected = []
    for c in ctx.attr.all_components:
        if "fail" in str(c.label):
            fail("Intentional failure on " + str(c.label))
        if "yes" in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    allow_real_deps = True,
    attrs = {
        "all_components": attr.label_list(),
    },
)

def _my_test_impl(ctx):
    if len(ctx.attr.targets_under_test) != 1:
        return [AnalysisTestResultInfo(
            success = False, message = "Wrong number of targets under test")]
    causes = ctx.attr.targets_under_test[0][AnalysisFailureInfo].causes.to_list()
    if len(causes) != 1:
        return [AnalysisTestResultInfo(success = False, message = "Wrong number of causes")]
    if "Intentional failure on @@//:fail_a" not in causes[0].message:
        return [AnalysisTestResultInfo(
            success = False, message = "Wrong cause: " + causes[0].message)]
    return [AnalysisTestResultInfo(success = True, message = "Success!")]

my_test = rule(
    implementation = _my_test_impl,
    attrs = {
        "targets_under_test": attr.label_list(
            cfg = analysis_test_transition(
                settings = {
                    "//command_line_option:allow_analysis_failures": "True",
                },
            ),
        ),
    },
    test = True,
    analysis_test = True,
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector", "my_test")

component(name = "fail_a")
component(name = "fail_b")
component(name = "yes_a")
component(name = "yes_b")

component_selector(
    name = "component_selector",
    all_components = [
        ":fail_a",
        ":fail_b",
        ":yes_a",
        ":yes_b",
    ],
)

my_test(
    name = "my_test",
    targets_under_test = [":component_selector"],
)

""".trimIndent()
        )

        update("//:my_test")
        val target: ConfiguredTarget = getConfiguredTarget("//:my_test")
        val info: AnalysisTestResultInfo = target.get(AnalysisTestResultInfo.provider)
        assertThat(info.success).isTrue()
    }

    /**
     * Tests that a materializer rule works correctly when it is under a split transition where the
     * failure happens in only some parts of the split.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun materializerRuleUnderFailureAnalysisTestWithSplitTransition_works() {
        scratch.file(
            "defs.bzl",
            """
# Component selector setting ###########################################

ComponentSelectorProvider = provider(fields = ["selector"])

def _component_selector_setting_impl(ctx):
    return ComponentSelectorProvider(selector = ctx.build_setting_value)

component_selector_setting = rule(
    implementation = _component_selector_setting_impl,
    build_setting = config.string()
)

# Component transition

def _component_selector_setting_transition_impl(settings, attr):
    return [
        {"//:component_selector_setting" : "foo"},
        {"//:component_selector_setting" : "bar"},
        {"//:component_selector_setting" : "baz"},
    ]

component_transition = transition(
    implementation = _component_selector_setting_transition_impl,
    inputs = [],
    outputs = ["//:component_selector_setting"],
)

# Component ############################################################

ComponentInfo = provider()

def _component_impl(ctx):
    return ComponentInfo()

component = rule(
    implementation = _component_impl,
    provides = [ComponentInfo],
)

# Component selector ###################################################

def _component_selector_impl(ctx):
    selector = ctx.attr._component_selector_setting[ComponentSelectorProvider].selector
    # This causes failures in the "bar" and "baz" splits of the transition.
    if selector != "foo":
        fail("Bad selector " + selector)
    selected = []
    for c in ctx.attr.all_components:
        if selector in str(c.label):
            selected.append(c)
    return MaterializedDepsInfo(deps = selected)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    allow_real_deps = True,
    attrs = {
        "all_components": attr.label_list(),
        "_component_selector_setting": attr.label(default = "//:component_selector_setting"),
    },
)

# Binary ###############################################################

def _binary_impl(ctx):
    return DefaultInfo()

binary = rule(
    implementation = _binary_impl,
    attrs = {
        "deps": attr.label_list(cfg = component_transition),
    },
)

# Analysis Test ##################################

def _my_test_impl(ctx):
    if len(ctx.attr.targets_under_test) != 1:
        return [AnalysisTestResultInfo(
            success = False, message = "Wrong number of targets under test")]
    causes = ctx.attr.targets_under_test[0][AnalysisFailureInfo].causes.to_list()
    if len(causes) != 2:
        return [AnalysisTestResultInfo(success = False, message = "Wrong number of causes")]
    messages = "\
    ".join([c.message for c in causes])
    if ("Bad selector baz" not in messages) or ("Bad selector bar" not in messages):
        return [AnalysisTestResultInfo(
            success = False, message = "Wrong causes: " + str(messages))]
    return [AnalysisTestResultInfo(success = True, message = "Success!")]

my_test = rule(
    implementation = _my_test_impl,
    attrs = {
        "targets_under_test": attr.label_list(
            cfg = analysis_test_transition(
                settings = {
                    "//command_line_option:allow_analysis_failures": "True",
                },
            ),
        ),
    },
    test = True,
    analysis_test = True,
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "binary", "component", "component_selector", "component_selector_setting", "my_test")

component(name = "foo_1")
component(name = "foo_2")
component(name = "bar_1")
component(name = "bar_2")
component(name = "baz_1")
component(name = "baz_2")

component_selector_setting(
    name = "component_selector_setting",
    build_setting_default = "foo",
)

component_selector(
    name = "component_selector",
    all_components = [
        ":foo_1",
        ":foo_2",
        ":bar_1",
        ":bar_2",
        ":baz_1",
        ":baz_2",
    ],
)

binary(
    name = "binary",
    deps = [":component_selector"],
)

my_test(
    name = "my_test",
    targets_under_test = [":binary"],
)

""".trimIndent()
        )

        update("//:my_test")
        val target: ConfiguredTarget = getConfiguredTarget("//:my_test")
        val info: AnalysisTestResultInfo = target.get(AnalysisTestResultInfo.provider)
        assertThat(info.success).isTrue()
    }

    /**
     * Tests that top-level aspects do not run on top-level materializer targets. Test for
     * b/488412397.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun topLevelAspectSkipsMaterializerTarget_works() {
        scratch.file(
            "defs.bzl",
            """
# Component ############################################################

def _component_impl(ctx):
    return DefaultInfo()

component = rule(
    implementation = _component_impl,
)

# Aspect ###############################################################

AspectInfo = provider(fields = ["info_artifact"])

def _mt_aspect_impl(target, ctx):
    artifact = ctx.actions.declare_file(target.label.name + ".info")
    # Materializer rules don't have access to ctx.actions, so this will fail if the aspect is run
    # on the materializer target, hence why we do not run aspects on materializer targets.
    ctx.actions.write(artifact, str(target.label))
    return AspectInfo(info_artifact = artifact)

mt_aspect = aspect(
    implementation = _mt_aspect_impl,
)

# Component selector ###################################################

def _component_selector_impl(ctx):
    return MaterializedDepsInfo(deps = ctx.attr.all_components)

component_selector = materializer_rule(
    implementation = _component_selector_impl,
    attrs = {
        "all_components": attr.dormant_label_list(),
    },
)

""".trimIndent()
        )

        scratch.file(
            "BUILD",
            """
load(":defs.bzl", "component", "component_selector")

component(name = "foo_1")
component(name = "foo_2")
component(name = "foo_3")

component_selector(
    name = "component_selector",
    all_components = [
        ":foo_1",
        ":foo_2",
        ":foo_3",
    ],
)

""".trimIndent()
        )

        // Should not result in errors from trying to register an action from an aspect, because the
        // aspect should not run on the materializer target.
        val unused: AnalysisResult? = update(
            com.google.common.collect.ImmutableList.of<String?>("//:defs.bzl%mt_aspect"),
            "//:component_selector"
        )
    }
}
