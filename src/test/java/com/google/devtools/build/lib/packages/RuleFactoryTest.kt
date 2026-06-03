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

import com.google.devtools.build.lib.analysis.ConfiguredRuleClassProvider

@RunWith(TestParameterInjector::class)
class RuleFactoryTest : PackageLoadingTestCase() {
    private val provider: ConfiguredRuleClassProvider = TestRuleClassProvider.getRuleClassProvider()

    private fun newBuilder(id: PackageIdentifier?, filename: Path?): Package.Builder {
        return packageFactory
            .newPackageBuilder(
                id,
                RootedPath.toRootedPath(root, filename),
                java.util.Optional.empty<T?>(),
                java.util.Optional.empty<T?>(),
                StarlarkSemantics.DEFAULT,  /* repositoryMapping= */
                RepositoryMapping.EMPTY,  /* mainRepositoryMapping= */
                null,  /* cpuBoundSemaphore= */
                null,  /* generatorMap= */
                null,  /* configSettingVisibilityPolicy= */
                null,  /* globber= */
                null
            )
            .setLoads(com.google.common.collect.ImmutableList.of<E?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateRule(@TestParameter explicitlySetGeneratorAttrs: Boolean) {
        val myPkgPath: Path = scratch.resolve("/workspace/mypkg/BUILD")
        val pkgBuilder: Package.Builder = newBuilder(PackageIdentifier.createInMainRepo("mypkg"), myPkgPath)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("name", "foo")
        attributeValues.put("executable", true)
        attributeValues.put("outs", com.google.common.collect.ImmutableList.of<String?>("foo.out"))
        attributeValues.put("cmd", "echo")

        // TODO(b/274802222): Should this be prohibited?
        if (explicitlySetGeneratorAttrs) {
            attributeValues.put("generator_name", "fake_generator_name")
            attributeValues.put("generator_function", "fake_generator_function")
        }

        val ruleClass: RuleClass? = provider.getRuleClassMap().get("genrule")
        val rule: Rule =
            RuleFactory.createAndAddRule(
                pkgBuilder,
                ruleClass,
                BuildLangTypedAttributeValuesMap(attributeValues),
                true,
                DUMMY_STACK
            )

        assertThat(rule.getAssociatedRule()).isSameInstanceAs(rule)

        // pkg.getRules() = [rule]
        val pkg: java.lang.Package = pkgBuilder.build()
        Truth.assertThat(com.google.common.collect.Sets.newHashSet(pkg.getTargets(Rule::class.java))).hasSize(1)
        assertThat(pkg.getTargets(Rule::class.java).iterator().next()).isEqualTo(rule)

        assertThat(pkg.getTarget("foo")).isSameInstanceAs(rule)

        assertThat(rule.getLabel()).isEqualTo(Label.parseCanonical("//mypkg:foo"))
        assertThat(rule.getName()).isEqualTo("foo")

        assertThat(rule.getRuleClass()).isEqualTo("genrule")
        assertThat(rule.getTargetKind()).isEqualTo("genrule rule")
        // The rule reports the location of the outermost call (aka generator), in the BUILD file.
        // This behavior was added to fix b/23974287, but it loses information and is redundant
        // w.r.t. generator_location. A better fix to that issue would be to keep rule.location as
        // the innermost call, and to report the entire call stack at the first error for the rule.
        assertThat(rule.getLocation().file()).isEqualTo("BUILD")
        assertThat(rule.getLocation().line()).isEqualTo(42)
        assertThat(rule.getLocation().column()).isEqualTo(1)
        assertThat(rule.containsErrors()).isFalse()

        // Attr with explicitly-supplied value:
        val attributes: AttributeMap = RawAttributeMapper.of(rule)
        assertThat(attributes.get("executable", Type.BOOLEAN)).isTrue()
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { attributes.get("tools", Type.STRING) })
        org.junit.Assert.assertThrows<java.lang.Exception?>(
            java.lang.Exception::class.java,
            org.junit.function.ThrowingRunnable { attributes.get("nosuchattr", Type.STRING) })

        // Attrs with default values:
        // cc_library linkstatic default=0 according to build encyc.
        assertThat(attributes.get("output_to_bindir", Type.BOOLEAN)).isFalse()
        assertThat(attributes.get("testonly", Type.BOOLEAN)).isFalse()
        assertThat(attributes.get("srcs", BuildType.LABEL_LIST)).isEmpty()
    }

    @org.junit.Test
    fun testOutputFileNotEqualDot() {
        val myPkgPath: Path = scratch.resolve("/workspace/mypkg/BUILD")
        val pkgBuilder: Package.Builder = newBuilder(PackageIdentifier.createInMainRepo("mypkg"), myPkgPath)

        val attributeValues: MutableMap<String?, Any?> = HashMap<String?, Any?>()
        attributeValues.put("outs", com.google.common.collect.Lists.newArrayList<String?>("."))
        attributeValues.put("name", "some")
        val ruleClass: RuleClass? = provider.getRuleClassMap().get("genrule")
        val e: RuleFactory.InvalidRuleException =
            org.junit.Assert.assertThrows<T>(
                RuleFactory.InvalidRuleException::class.java,
                org.junit.function.ThrowingRunnable {
                    RuleFactory.createAndAddRule(
                        pkgBuilder,
                        ruleClass,
                        BuildLangTypedAttributeValuesMap(attributeValues),
                        true,
                        DUMMY_STACK
                    )
                })
        assertWithMessage(e.getMessage())
            .that(e.getMessage().contains("output file name can't be equal '.'"))
            .isTrue()
    }

    /** Tests mandatory attribute definitions for test rules.  */ // TODO(ulfjack): Remove this check when we switch over to the builder
    // pattern, which will always guarantee that these attributes are present.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestRules() {
        val myPkgPath: Path = scratch.resolve("/workspace/mypkg/BUILD")
        val pkg: java.lang.Package = newBuilder(PackageIdentifier.createInMainRepo("mypkg"), myPkgPath).build()

        for (ruleClass in provider.getRuleClassMap().values()) {
            // Create rule instance directly so we'll avoid mandatory attribute check yet will be able
            // to use TargetUtils.isTestRule() method to identify test rules.
            val rule: Rule =
                Rule(
                    pkg,
                    Label.create(pkg.getPackageIdentifier(), "myrule"),
                    ruleClass,
                    net.starlark.java.syntax.Location.fromFile(myPkgPath.toString()),  /* interiorCallStack= */
                    null
                )
            if (TargetUtils.isTestRule(rule)) {
                assertAttr(ruleClass, "tags", Types.STRING_LIST)
                assertAttr(ruleClass, "size", Type.STRING)
                assertAttr(ruleClass, "flaky", Type.BOOLEAN)
                assertAttr(ruleClass, "shard_count", Type.INTEGER)
                assertAttr(ruleClass, "local", Type.BOOLEAN)
            }
        }
    }

    companion object {
        private val DUMMY_STACK: com.google.common.collect.ImmutableList<CallStackEntry?> =
            com.google.common.collect.ImmutableList.of<CallStackEntry?>(
                StarlarkThread.callStackEntry(
                    StarlarkThread.TOP_LEVEL, net.starlark.java.syntax.Location.fromFileLineColumn("BUILD", 42, 1)
                ),
                StarlarkThread.callStackEntry(
                    "foo",
                    net.starlark.java.syntax.Location.fromFileLineColumn("foo.bzl", 10, 1)
                ),
                StarlarkThread.callStackEntry(
                    "myrule",
                    net.starlark.java.syntax.Location.fromFileLineColumn("bar.bzl", 30, 6)
                )
            )

        private fun assertAttr(ruleClass: RuleClass, attrName: String?, type: Type<*>?) {
            Truth.assertWithMessage(
                "Rule class '%s' should have attribute '%s' of type '%s'",
                ruleClass.getName(), attrName, type
            )
                .that(ruleClass.getAttributeProvider().hasAttr(attrName, type))
                .isTrue()
        }
    }
}
