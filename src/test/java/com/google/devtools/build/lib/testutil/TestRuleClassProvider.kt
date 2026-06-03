// Copyright 2014 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.packages.Attribute.attr

/** Helper class to provide a RuleClassProvider for tests.  */
object TestRuleClassProvider {
    var ruleClassProvider: ConfiguredRuleClassProvider? = null
        /** Returns a rule class provider.  */
        get() {
            if (field == null) {
                field = createRuleClassProvider()
            }
            return field
        }
        private set

    /** Adds all the rule classes supported internally within the build tool to the given builder.  */
    fun addStandardRules(builder: ConfiguredRuleClassProvider.Builder) {
        try {
            val providerClass: java.lang.Class<*> = java.lang.Class.forName(TestConstants.TEST_RULE_CLASS_PROVIDER)
            val setupMethod: java.lang.reflect.Method =
                providerClass.getMethod("setup", ConfiguredRuleClassProvider.Builder::class.java)
            setupMethod.invoke(null, builder)

            // Add the repository module for any unit tests that test local_repository behavior
            builder.addStarlarkBootstrap(RepositoryBootstrap(StarlarkRepositoryModule()))
        } catch (e: java.lang.Exception) {
            throw java.lang.IllegalStateException(e)
        }
    }

    private fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        addStandardRules(builder)
        // TODO(b/174773026): Eliminate TestingDummyRule/MockToolchainRule from this class, push them
        // down into the tests that use them. It's better for tests to avoid spooky mocks at a distance.
        // If we eliminate it, TestRuleClassProvider probably doesn't need to exist anymore.
        builder.addRuleDefinition(TestingDummyRule())
        builder.addRuleDefinition(MockToolchainRule())
        return builder.build()
    }

    // TODO(bazel-team): The logic for the "minimal" rule class provider is currently split between
    // TestRuleClassProvider and BuiltinsInjectionTest's overrides of BuildViewTestCase setup helpers.
    // Consider refactoring this together into one place as a new MinimalAnalysisMock.
    /**
     * Adds a few essential rules to a builder, such that it is usable but does not contain all the
     * rule classes known to the production environment.
     */
    fun addMinimalRules(builder: ConfiguredRuleClassProvider.Builder) {
        // TODO(bazel-team): See also TrimmableTestConfigurationFragments#installFragmentsAndNativeRules
        // for alternative/additional setup. Consider factoring that one to use this method.
        builder
            .setToolsRepository(RepositoryName.MAIN)
            .setRunfilesPrefix("test")
            .setPrerequisiteValidator(MinimalPrerequisiteValidator())
        CoreRules.INSTANCE.init(builder)
        builder.addConfigurationOptions(CoreOptions::class.java)
        PlatformRules.INSTANCE.init(builder)
        ConfigRules.INSTANCE.init(builder)
    }

    private val FAKE_LABEL: Label? = Label.parseCanonicalUnchecked("//fake/label.bzl")

    private val STARLARK_P1: StarlarkProviderIdentifier? = StarlarkProviderIdentifier.forKey(
        Key(keyForBuild(FAKE_LABEL), "STARLARK_P1")
    )

    class MinimalPrerequisiteValidator : CommonPrerequisiteValidator() {
        protected override fun isSameLogicalPackage(
            thisPackage: PackageIdentifier, prerequisitePackage: PackageIdentifier?
        ): Boolean {
            return thisPackage.equals(prerequisitePackage)
        }

        public override fun packageUnderExperimental(packageIdentifier: PackageIdentifier?): Boolean {
            return false
        }

        public override fun packageUnderPrototypes(packageIdentifier: PackageIdentifier?): Boolean {
            return false
        }

        protected override fun checkVisibilityForExperimental(context: RuleContext.Builder?): Boolean {
            // It does not matter whether we return true or false here if packageUnderExperimental always
            // returns false.
            return true
        }

        protected override fun checkVisibilityForPrototypes(context: RuleContext.Builder?): Boolean {
            return true
        }

        protected override fun allowExperimentalDeps(context: RuleContext.Builder?): Boolean {
            // It does not matter whether we return true or false here if packageUnderExperimental always
            // returns false.
            return false
        }
    }

    /** A dummy rule with some dummy attributes.  */
    class TestingDummyRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .setUndocumented()
                .add(attr("srcs", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                .add(attr("outs", OUTPUT_LIST))
                .add(attr("dummystrings", STRING_LIST))
                .add(attr("dummyinteger", INTEGER))
                .build()
        }

        val metadata: Metadata
            get() = RuleDefinition.Metadata.builder()
                .name("testing_dummy_rule")
                .ancestors(BaseRuleClasses.NativeActionCreatingRule::class.java)
                .factoryClass(UnknownRuleConfiguredTarget::class.java)
                .build()
    }

    /** Stub rule to test Make variable expansion.  */
    class MakeVariableTester : RuleConfiguredTargetFactory {
        @Throws(java.lang.InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            val variables: MutableMap<String?, String?>? = ruleContext.attributes().get("variables", Types.STRING_DICT)
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(NestedSetBuilder.emptySet(Order.STABLE_ORDER))
                .addProvider(RunfilesProvider.EMPTY)
                .addNativeDeclaredProvider(
                    TemplateVariableInfo(
                        com.google.common.collect.ImmutableMap.< K,
                        V > copyOf<K?, V?>(variables)
                    )
                )
                .build()
        }
    }

    /** Definition of a stub rule to test Make variable expansion.  */
    class MakeVariableTesterRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .advertiseStarlarkProvider(
                    StarlarkProviderIdentifier.forKey(TemplateVariableInfo.PROVIDER.getKey())
                )
                .add(attr("variables", Types.STRING_DICT))
                .build()
        }

        val metadata: Metadata
            get() = Metadata.builder()
                .name("make_variable_tester")
                .ancestors(
                    BaseRuleClasses.NativeBuildRule::class.java,
                    BaseRuleClasses.MakeVariableExpandingRule::class.java
                )
                .factoryClass(MakeVariableTester::class.java)
                .build()
    }

    /** A mock rule that requires a toolchain.  */
    class MockToolchainRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .requiresConfigurationFragments(PlatformConfiguration::class.java)
                .addToolchainTypes(
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//toolchain:test_toolchain")
                    )
                )
                .build()
        }

        val metadata: Metadata
            get() = RuleDefinition.Metadata.builder()
                .name("mock_toolchain_rule")
                .factoryClass(UnknownRuleConfiguredTarget::class.java)
                .ancestors(BaseRuleClasses.NativeActionCreatingRule::class.java)
                .build()
    }

    /** A simple provider for testing.  */
    class FooProvider : TransitiveInfoProvider

    /** Definition of a rule that advertises a native provider that it does not return.  */
    class LiarRuleWithNativeProvider : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder.advertiseProvider(FooProvider::class.java).build()
        }

        val metadata: Metadata
            get() = Metadata.builder()
                .name("liar_rule_with_native_provider")
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .factoryClass(UnknownRuleConfiguredTarget::class.java)
                .build()
    }

    /** Definition of a rule that advertises a Starlark provider that it does not return.  */
    class LiarRuleWithStarlarkProvider : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder.advertiseStarlarkProvider(STARLARK_P1).build()
        }

        val metadata: Metadata
            get() = Metadata.builder()
                .name("liar_rule_with_starlark_provider")
                .ancestors(BaseRuleClasses.NativeBuildRule::class.java)
                .factoryClass(UnknownRuleConfiguredTarget::class.java)
                .build()
    }
}
