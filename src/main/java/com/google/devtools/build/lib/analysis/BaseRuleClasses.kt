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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER_EXEC_CONFIG
import com.google.devtools.build.lib.analysis.BaseRuleClasses.RUN_UNDER_TARGET_CONFIG
import com.google.devtools.build.lib.packages.Attribute.attr

/** Rule class definitions used by (almost) every rule.  */
object BaseRuleClasses {
    @SerializationConstant
    @VisibleForSerialization
    val testonlyDefault: Attribute.ComputedDefault = object : ComputedDefault() {
        public override fun getDefault(rule: AttributeMap): Any {
            return rule.getPackageArgs().defaultTestOnly()
        }
    }

    @SerializationConstant
    @VisibleForSerialization
    val deprecationDefault: Attribute.ComputedDefault = object : ComputedDefault() {
        public override fun getDefault(rule: AttributeMap): Any {
            return rule.getPackageArgs().defaultDeprecation()
        }
    }

    @SerializationConstant
    @VisibleForSerialization
    val TIMEOUT_DEFAULT: Attribute.ComputedDefault = object : ComputedDefault() {
        public override fun getDefault(rule: AttributeMap): Any {
            val size: TestSize? = TestSize.getTestSize(rule.get("size", Type.STRING))
            if (size != null) {
                val timeout: String? = size.getDefaultTimeout().toString()
                if (timeout != null) {
                    return timeout
                }
            }
            return "illegal"
        }
    }

    @SerializationConstant
    @VisibleForSerialization
    val packageMetadataDefault: Attribute.ComputedDefault = object : ComputedDefault() {
        public override fun getDefault(rule: AttributeMap): Any {
            return rule.getPackageArgs().defaultPackageMetadata()
        }
    }

    // TODO(b/65746853): provide a way to do this without passing the entire configuration
    /**
     * Implementation for the :action_listener attribute.
     * 
     * 
     * action_listeners are special rules; they tell the build system to add extra_actions to
     * existing rules. As such they need an edge to every ConfiguredTarget with the limitation that
     * they only run on the target configuration and should not operate on action_listeners and
     * extra_actions themselves (to avoid cycles).
     */
    @SerializationConstant
    @VisibleForSerialization
    @com.google.common.annotations.VisibleForTesting
    val ACTION_LISTENER: LabelListLateBoundDefault<*>? = LabelListLateBoundDefault.fromTargetConfiguration(
        BuildConfigurationValue::class.java,
        { rule, attributes, configuration -> configuration.getActionListeners() })

    const val DEFAULT_COVERAGE_SUPPORT_VALUE: String = "//tools/test:coverage_support"

    @SerializationConstant
    @VisibleForSerialization
    val COVERAGE_SUPPORT_CONFIGURATION_RESOLVER: Resolver<TestConfiguration?, Label?> =
        Resolver { rule, attributes, configuration -> configuration.getCoverageSupport() }

    fun coverageSupportAttribute(
        defaultValue: Label?
    ): LabelLateBoundDefault<TestConfiguration?> {
        return LabelLateBoundDefault.fromTargetConfiguration(
            TestConfiguration::class.java, defaultValue, COVERAGE_SUPPORT_CONFIGURATION_RESOLVER
        )
    }

    const val DEFAULT_COVERAGE_REPORT_GENERATOR_VALUE: String = "//tools/test:coverage_report_generator"

    @SerializationConstant
    @VisibleForSerialization
    val COVERAGE_REPORT_GENERATOR_CONFIGURATION_RESOLVER: Resolver<CoverageConfiguration?, Label?> =
        Resolver { rule, attributes, configuration -> configuration.reportGenerator() }

    fun coverageReportGeneratorAttribute(
        defaultValue: Label?
    ): LabelLateBoundDefault<CoverageConfiguration?> {
        return LabelLateBoundDefault.fromTargetConfiguration(
            CoverageConfiguration::class.java,
            defaultValue,
            COVERAGE_REPORT_GENERATOR_CONFIGURATION_RESOLVER
        )
    }

    // TODO(b/65746853): provide a way to do this without passing the entire configuration
    /**
     * Resolves the latebound exec-configured :run_under label. This only exists if --run_under is set
     * to a label and --incompatible_bazel_test_exec_run_under is true. Else it's null.
     * 
     * 
     * [RUN_UNDER_EXEC_CONFIG] and [RUN_UNDER_TARGET_CONFIG] cannot both be non-null in
     * a build: if `--run_under` is set it must connect to a single dependency.
     */
    @SerializationConstant
    @VisibleForSerialization
    val RUN_UNDER_EXEC_CONFIG: LabelLateBoundDefault<*>? = LabelLateBoundDefault.fromTargetConfiguration(
        BuildConfigurationValue::class.java,
        null,
        { rule, attributes, config ->
            if (config.isExecConfiguration() // This is the opposite of RUN_UNDER_TARGET_CONFIG, so both can't be non-null.
                || !config.runUnderExecConfigForTests() || config.getRunUnder() == null
            ) {
                return@fromTargetConfiguration null
            }
            if (config.getRunUnder() is LabelRunUnder) runUnder.label() else null
        })

    // TODO(b/65746853): provide a way to do this without passing the entire configuration
    /**
     * Resolves the latebound target-configured :run_under label. This only exists if --run_under is
     * set to a label and --incompatible_bazel_test_exec_run_under is false. Else it's null.
     * 
     * 
     * [RUN_UNDER_EXEC_CONFIG] and [RUN_UNDER_TARGET_CONFIG] cannot both be non-null in
     * a build: if `--run_under` is set it must connect to a single dependency.
     */
    val RUN_UNDER_TARGET_CONFIG: LabelLateBoundDefault<*>? = LabelLateBoundDefault.fromTargetConfiguration(
        BuildConfigurationValue::class.java,
        null,
        { rule, attributes, config ->
            if (config.isExecConfiguration() // This is the opposite of RUN_UNDER_EXEC_CONFIG, so both can't be non-null.
                || config.runUnderExecConfigForTests()
                || config.getRunUnder() == null
            ) {
                return@fromTargetConfiguration null
            }
            if (config.getRunUnder() is LabelRunUnder) runUnder.label() else null
        })

    private const val TOOLS_TEST_RUNTIME_TARGET_PATTERN = "//tools/test:runtime"
    private var testRuntimeLabelList: com.google.common.collect.ImmutableList<Label?>? = null

    // Always return the same ImmutableList<Label> for every $test_runtime attribute's default value.
    @kotlin.jvm.Synchronized
    fun getTestRuntimeLabelList(
        env: RuleDefinitionEnvironment
    ): com.google.common.collect.ImmutableList<Label?> {
        if (testRuntimeLabelList == null) {
            testRuntimeLabelList =
                com.google.common.collect.ImmutableList.of<E?>(
                    Label.parseCanonicalUnchecked(
                        env.getToolsRepository() + TOOLS_TEST_RUNTIME_TARGET_PATTERN
                    )
                )
        }
        return testRuntimeLabelList
    }

    /**
     * The attribute used to list the configuration properties used by a target and its transitive
     * dependencies. Currently only supports config_feature_flag.
     * 
     * 
     * A special value of "//command_line_option/fragments:test" instructs
     * TestTrimmingTransitionFactory to skip trimming for this rule.
     */
    const val TAGGED_TRIMMING_ATTR: String = "transitive_configs"

    /** Share common attributes across both base and Starlark base rules.  */ // TODO(bazel-team): replace this with a common RuleDefinition ancestor of NativeBuildRule
    // and StarlarkRuleClassFunctions.baseRule. This requires refactoring StarlarkRuleClassFunctions
    // to instantiate its RuleClasses through RuleDefinition.
    fun commonCoreAndStarlarkAttributes(builder: RuleClass.Builder): RuleClass.Builder {
        return builder // The visibility attribute is special: it is a nodep label, and loading the
            // necessary package groups is handled by {@link LabelVisitor#visitTargetVisibility}.
            // Package groups always have the null configuration so that they are not duplicated
            // needlessly.
            .add(
                attr("visibility", NODEP_LABEL_LIST)
                    .orderIndependent()
                    .cfg(ExecutionTransitionFactory.Companion.createFactory())
                    .nonconfigurable(
                        "special attribute integrated more deeply into Bazel's core logic"
                    )
            )
            .add(
                attr(TAGGED_TRIMMING_ATTR, NODEP_LABEL_LIST)
                    .orderIndependent()
                    .nonconfigurable("Used in determining configuration")
            )
            .add(
                attr("deprecation", STRING)
                    .value(deprecationDefault)
                    .nonconfigurable("Used in core loading phase logic with no access to configs")
            )
            .add(
                attr("tags", STRING_LIST)
                    .orderIndependent()
                    .taggable()
                    .nonconfigurable("low-level attribute, used in TargetUtils without configurations")
            )
            .add(
                attr("generator_name", STRING)
                    .undocumented("internal")
                    .nonconfigurable("static structure of a rule")
            )
            .add(
                attr("generator_function", STRING)
                    .undocumented("internal")
                    .nonconfigurable("static structure of a rule")
            )
            .add(
                attr("generator_location", STRING)
                    .undocumented("internal")
                    .nonconfigurable("static structure of a rule")
            )
            .add(
                attr("testonly", BOOLEAN)
                    .value(testonlyDefault)
                    .nonconfigurable("policy decision: rules testability should be consistent")
            )
            .add(attr("features", STRING_LIST).orderIndependent())
            .add(
                attr(":action_listener", LABEL_LIST)
                    .cfg(ExecutionTransitionFactory.Companion.createFactory())
                    .value(ACTION_LISTENER)
            )
            .add(
                attr(RuleClass.COMPATIBLE_ENVIRONMENT_ATTR, LABEL_LIST)
                    .allowedRuleClasses(ConstraintConstants.ENVIRONMENT_RULE)
                    .cfg(NoConfigTransition.getFactory())
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .dontCheckConstraints()
                    .nonconfigurable(
                        "special logic for constraints and select: see ConstraintSemantics"
                    )
            )
            .add(
                attr(RuleClass.RESTRICTED_ENVIRONMENT_ATTR, LABEL_LIST)
                    .allowedRuleClasses(ConstraintConstants.ENVIRONMENT_RULE)
                    .cfg(NoConfigTransition.getFactory())
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .dontCheckConstraints()
                    .nonconfigurable(
                        "special logic for constraints and select: see ConstraintSemantics"
                    )
            )
            .add(
                attr(RuleClass.CONFIG_SETTING_DEPS_ATTRIBUTE, LABEL_LIST)
                    .nonconfigurable("stores configurability keys")
            )
            .add(
                attr(RuleClass.APPLICABLE_METADATA_ATTR, LABEL_LIST)
                    .value(packageMetadataDefault)
                    .cfg(NoConfigTransition.getFactory())
                    .allowedFileTypes(FileTypeSet.NO_FILE)
                    .dontCheckConstraints()
                    .nonconfigurable("applicable_metadata is not configurable")
            )
            .add(attr("aspect_hints", LABEL_LIST).allowedFileTypes(FileTypeSet.NO_FILE))
    }

    @Throws(ConversionException::class)
    fun execPropertiesAttribute(builder: RuleClass.Builder): RuleClass.Builder {
        return builder.add(
            attr(
                RuleClass.EXEC_PROPERTIES_ATTR,
                STRING_DICT
            ).defaultValue(com.google.common.collect.ImmutableMap.of<K?, V?>())
        )
    }

    /**
     * Ancestor of every native rule in BUILD files (not WORKSPACE files).
     * 
     * 
     * This includes:
     * 
     * 
     *  * rules that create actions ([NativeActionCreatingRule])
     *  * rules that encapsulate toolchain and build environment context
     *  * rules that aggregate other rules (like file groups, test suites, or aliases)
     * 
     */
    class NativeBuildRule : RuleDefinition {
        override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return commonCoreAndStarlarkAttributes(builder)
                .add(
                    attr("licenses", LICENSE)
                        .nonconfigurable("Used in core loading phase logic with no access to configs")
                )
                .add( // TODO: b/148549967 - Remove for Bazel 9.0
                    attr("distribs", STRING_LIST).nonconfigurable("deprecated - no op")
                ) // Any rule that provides its own meaning for the "target_compatible_with" attribute
                // has to be excluded in `IncompatibleTargetChecker`.
                .add(
                    attr(RuleClass.TARGET_COMPATIBLE_WITH_ATTR, LABEL_LIST)
                        .mandatoryProviders(ConstraintValueInfo.PROVIDER.id()) // This should be configurable to allow for complex types of restrictions.
                        .tool(
                            "target_compatible_with exists for constraint checking, not to create an"
                                    + " actual dependency"
                        )
                        .allowedFileTypes(FileTypeSet.NO_FILE)
                )
                .build()
        }

        override fun getMetadata(): com.google.devtools.build.lib.analysis.RuleDefinition.Metadata? {
            return com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Companion.builder()
                .name("\$native_build_rule")
                .type(RuleClassType.ABSTRACT)
                .build()
        }
    }

    /** A rule that contains a `variables=` attribute to allow referencing Make variables.  */
    class MakeVariableExpandingRule : RuleDefinition {
        override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder // Documented in
                // com/google/devtools/build/docgen/templates/attributes/common/toolchains.html.
                .add(
                    attr("toolchains", LABEL_LIST)
                        .allowedFileTypes(FileTypeSet.NO_FILE)
                        .mandatoryProviders(com.google.common.collect.ImmutableList.of<E?>(TemplateVariableInfo.PROVIDER.id()))
                        .dontCheckConstraints()
                )
                .build()
        }

        override fun getMetadata(): com.google.devtools.build.lib.analysis.RuleDefinition.Metadata? {
            return com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Companion.builder()
                .name("\$make_variable_expanding_rule")
                .type(RuleClassType.ABSTRACT)
                .build()
        }
    }

    /**
     * Ancestor of every native BUILD rule that creates actions.
     * 
     * 
     * This is a subset of all BUILD rules. Filegroups and aliases, for example, simply encapsulate
     * other rules. Toolchain rules provide metadata for actions of other rules. See [ ] for these.
     */
    class NativeActionCreatingRule : RuleDefinition {
        override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .add(attr("deps", LABEL_LIST).legacyAllowAnyFileType())
                .add(
                    attr("data", LABEL_LIST)
                        .allowedFileTypes(FileTypeSet.ANY_FILE)
                        .dontCheckConstraints()
                )
                .add(
                    attr(
                        RuleClass.EXEC_PROPERTIES_ATTR,
                        Types.STRING_DICT
                    ).value(com.google.common.collect.ImmutableMap.of<K?, V?>())
                )
                .add(
                    attr(RuleClass.EXEC_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST)
                        .allowedFileTypes()
                        .nonconfigurable("Used in toolchain resolution")
                        .tool(
                            "exec_compatible_with exists for constraint checking, not to create an"
                                    + " actual dependency"
                        )
                        .value(com.google.common.collect.ImmutableList.of<E?>())
                )
                .add(
                    attr(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR, BuildType.LABEL_LIST_DICT)
                        .allowedFileTypes()
                        .nonconfigurable("Used in toolchain resolution")
                        .tool(
                            "exec_group_compatible_with exists for constraint checking, not to create an"
                                    + " actual dependency"
                        )
                        .value(com.google.common.collect.ImmutableMap.of<K?, V?>())
                )
                .build()
        }

        override fun getMetadata(): com.google.devtools.build.lib.analysis.RuleDefinition.Metadata? {
            return com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Companion.builder()
                .name("\$native_buildable_rule")
                .type(RuleClassType.ABSTRACT)
                .ancestors(NativeBuildRule::class.java)
                .build()
        }
    }

    /**
     * An empty rule that exists for the sole purpose to completely remove a native rule while it's
     * still defined as a Starlark rule in builtins.
     * 
     * 
     * Use it like `builder.addRuleDefinition(new BaseRuleClasses.EmptyRule("name") {});
    ` * . The `{}` create a new class for each rule. That's needed because [ ] assumes each rule class has a different Java class.
     */
    abstract class EmptyRule @kotlin.jvm.JvmOverloads constructor(
        private val name: String?,
        private val bzlLoadFile: String? = null
    ) : RuleDefinition {
        override fun build(builder: RuleClass.Builder, env: RuleDefinitionEnvironment?): RuleClass {
            if (builder.getType() === RuleClassType.TEST) {
                builder
                    .add(
                        attr("size", STRING)
                            .nonconfigurable("policy decision: should be consistent across configurations")
                    )
                    .add(
                        attr("timeout", STRING)
                            .nonconfigurable("policy decision: should be consistent across configurations")
                    )
                    .add(attr("flaky", BOOLEAN))
                    .add(attr("shard_count", INTEGER))
                    .add(attr("local", BOOLEAN))
            }
            return builder
                .removeAttribute("deps")
                .removeAttribute("data")
                .addAttribute(attr("\$bzl_load_label", STRING).value(this.bzlLoadFile).build())
                .build()
        }

        override fun getMetadata(): com.google.devtools.build.lib.analysis.RuleDefinition.Metadata? {
            val metadata: com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Builder =
                com.google.devtools.build.lib.analysis.RuleDefinition.Metadata.Companion.builder()
                    .name(name)
                    .type(if (TargetUtils.isTestRuleName(name)) RuleClassType.TEST else RuleClassType.NORMAL)
                    .ancestors(NativeActionCreatingRule::class.java)
                    .factoryClass(EmptyRuleConfiguredTargetFactory::class.java)
            return metadata.build()
        }
    }

    /**
     * Factory used by rules' definitions that exist for the sole purpose of providing documentation.
     * For most of these rules, the actual rule is implemented in Starlark but the documentation
     * generation mechanism does not work yet for Starlark rules. TODO(bazel-team): Delete once
     * documentation tools work for Starlark.
     */
    class EmptyRuleConfiguredTargetFactory : RuleConfiguredTargetFactory {
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            val ruleName: String? = ruleContext.getRule().getRuleClass()
            val bzlLoadLabel: String? = ruleContext.attributes().getOrDefault("\$bzl_load_label", STRING, null)
            if (bzlLoadLabel != null) {
                ruleContext.ruleError(
                    """
            The %s rule has been removed, add the following to your BUILD/bzl file:

            load("%s", "%s")
            
            """
                        .trimIndent()
                        .formatted(ruleName, bzlLoadLabel, ruleName)
                )
            } else {
                ruleContext.ruleError("Rule '" + ruleName + "' is unimplemented.")
            }
            return null
        }
    }
}
