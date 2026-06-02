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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Function
import com.google.common.collect.*
import com.google.devtools.build.lib.concurrent.ThreadSafety
import com.google.devtools.build.lib.packages.Attribute.attr
import net.starlark.java.eval.EvalException
import net.starlark.java.syntax.Location

/**
 * Various rule and aspect classes that aid in testing the aspect machinery.
 * 
 * 
 * These are mostly used in [com.google.devtools.build.lib.analysis.DependencyResolverTest]
 * and [com.google.devtools.build.lib.analysis.AspectTest].
 */
object TestAspects {
    private val FAKE_LABEL: Label? = Label.parseCanonicalUnchecked("//fake/label.bzl")

    val REQUIRED_PROVIDER_KEY: StarlarkProvider.Key = Key(keyForBuild(FAKE_LABEL), "RequiredProvider")
    val REQUIRED_PROVIDER2_KEY: StarlarkProvider.Key = Key(keyForBuild(FAKE_LABEL), "RequiredProvider2")

    /**
     * A very simple provider used in tests that check whether the logic that attaches aspects
     * depending on whether a configured target has a provider works or not.
     */
    @SerializationConstant
    @VisibleForSerialization
    val REQUIRED_PROVIDER: StarlarkProvider? = StarlarkProvider.builder(Location.BUILTIN).buildExported(
        REQUIRED_PROVIDER_KEY
    )

    /**
     * Another very simple provider used in tests that check whether the logic that attaches aspects
     * depending on whether a configured target has a provider works or not.
     */
    @SerializationConstant
    @VisibleForSerialization
    val REQUIRED_PROVIDER2: StarlarkProvider? = StarlarkProvider.builder(Location.BUILTIN).buildExported(
        REQUIRED_PROVIDER2_KEY
    )

    private fun collectAspectData(me: String?, ruleContext: RuleContext): NestedSet<String?> {
        val result: NestedSetBuilder<String?> = NestedSetBuilder.newBuilder(Order.STABLE_ORDER)
        result.add(me)

        val attributeNames: Iterable<String?> = ruleContext.attributes().getAttributeNames()
        for (attributeName in attributeNames) {
            val attributeType: Type<*>? = ruleContext.attributes().getAttributeType(attributeName)
            if (!LABEL.equals(attributeType) && !LABEL_LIST.equals(attributeType)) {
                continue
            }
            val prerequisites: Iterable<AspectInfo> =
                ruleContext
                    .getRulePrerequisitesCollection()
                    .getPrerequisites(attributeName, AspectInfo::class.java)
            for (prerequisite in prerequisites) {
                result.addTransitive(prerequisite.getData())
            }
        }
        return result.build()
    }

    val SIMPLE_ASPECT: SimpleAspect = SimpleAspect()
    val FILE_PROVIDER_ASPECT: FileProviderAspect = FileProviderAspect()
    val FOO_PROVIDER_ASPECT: FooProviderAspect = FooProviderAspect()
    val BAR_PROVIDER_ASPECT: BarProviderAspect = BarProviderAspect()
    val SIMPLE_STARLARK_NATIVE_ASPECT: SimpleStarlarkNativeAspect = SimpleStarlarkNativeAspect()
    val PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER: ParametrizedAspectWithProvider =
        ParametrizedAspectWithProvider()
    val STARLARK_NATIVE_ASPECT_WITH_PROVIDER: StarlarkNativeAspectWithProvider = StarlarkNativeAspectWithProvider()

    private val SIMPLE_ASPECT_DEFINITION: AspectDefinition? = Builder(SIMPLE_ASPECT).build()
    private val FOO_PROVIDER_ASPECT_DEFINITION: AspectDefinition? = Builder(FOO_PROVIDER_ASPECT).build()
    private val BAR_PROVIDER_ASPECT_DEFINITION: AspectDefinition? = Builder(BAR_PROVIDER_ASPECT).build()
    private val SIMPLE_STARLARK_NATIVE_ASPECT_DEFINITION: AspectDefinition? =
        Builder(SIMPLE_STARLARK_NATIVE_ASPECT).build()

    val EXTRA_ATTRIBUTE_ASPECT: ExtraAttributeAspect =
        ExtraAttributeAspect( /*depLabel=*/"//extra",  /*applyToFiles=*/false)

    private val EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER = ExtraAttributeAspectRequiringProvider()
    private val EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER_DEFINITION: AspectDefinition? = Builder(
        EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER
    )
        .add(attr("\$dep", LABEL).value(Label.parseCanonicalUnchecked("//extra:extra")))
        .requireStarlarkProviders(StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY))
        .build()

    val PACKAGE_GROUP_ATTRIBUTE_ASPECT: PackageGroupAttributeAspect = PackageGroupAttributeAspect()
    private val PACKAGE_GROUP_ATTRIBUTE_ASPECT_DEFINITION: AspectDefinition? = Builder(PACKAGE_GROUP_ATTRIBUTE_ASPECT)
        .add(
            attr("\$dep", LABEL)
                .value(Label.parseCanonicalUnchecked("//extra:extra"))
                .mandatoryBuiltinProviders(ImmutableList.of<E?>(PackageSpecificationProvider::class.java))
        )
        .build()

    val COMPUTED_ATTRIBUTE_ASPECT: ComputedAttributeAspect = ComputedAttributeAspect()
    private val COMPUTED_ATTRIBUTE_ASPECT_DEFINITION: AspectDefinition? = Builder(COMPUTED_ATTRIBUTE_ASPECT).build()

    val ATTRIBUTE_ASPECT: AttributeAspect = AttributeAspect()
    private val ATTRIBUTE_ASPECT_DEFINITION: AspectDefinition? = Builder(ATTRIBUTE_ASPECT)
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("foo")))
        .build()

    val ALL_ATTRIBUTES_ASPECT: NativeAspectClass = AllAttributesAspect()
    private val ALL_ATTRIBUTES_ASPECT_DEFINITION: AspectDefinition? = Builder(ALL_ATTRIBUTES_ASPECT)
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("*")))
        .build()

    val ALL_ATTRIBUTES_WITH_TOOL_ASPECT: NativeAspectClass = AllAttributesWithToolAspect()
    private val ALL_ATTRIBUTES_WITH_TOOL_ASPECT_DEFINITION: AspectDefinition? = Builder(ALL_ATTRIBUTES_WITH_TOOL_ASPECT)
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("*")))
        .add(
            attr("\$tool", BuildType.LABEL)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .value(Label.parseCanonicalUnchecked("//a:tool"))
        )
        .build()

    val PARAMETRIZED_DEFINITION_ASPECT: ParametrizedDefinitionAspect = ParametrizedDefinitionAspect()

    val ASPECT_REQUIRING_PROVIDER: AspectRequiringProvider = AspectRequiringProvider()
    val ASPECT_REQUIRING_PROVIDER_SETS: AspectRequiringProviderSets = AspectRequiringProviderSets()
    private val ASPECT_REQUIRING_PROVIDER_DEFINITION: AspectDefinition? = Builder(ASPECT_REQUIRING_PROVIDER)
        .requireStarlarkProviders(StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY))
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("foo")))
        .build()
    private val ASPECT_REQUIRING_PROVIDER_SETS_DEFINITION: AspectDefinition? = Builder(ASPECT_REQUIRING_PROVIDER_SETS)
        .requireStarlarkProviderSets(
            ImmutableList.of<E?>(
                ImmutableSet.of<E?>(StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY)),
                ImmutableSet.of<E?>(StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER2_KEY))
            )
        )
        .build()

    val WARNING_ASPECT: WarningAspect = WarningAspect()
    private val WARNING_ASPECT_DEFINITION: AspectDefinition? = Builder(WARNING_ASPECT)
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("bar")))
        .build()

    val ERROR_ASPECT: ErrorAspect = ErrorAspect()
    private val ERROR_ASPECT_DEFINITION: AspectDefinition? = Builder(ERROR_ASPECT)
        .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("bar")))
        .build()

    private fun createAttrAspects(
        attrAspects: ImmutableList<String?>?
    ): AspectPropagationEdgesSupplier<String?> {
        try {
            return AspectPropagationEdgesSupplier.createForAttrAspects(
                StarlarkList.immutableCopyOf<T?>(attrAspects),  /* thread= */null
            )
        } catch (e: EvalException) {
            throw IllegalStateException(e)
        }
    }

    private fun createToolchainsAspects(
        toolchainsAspects: ImmutableList<String?>?
    ): AspectPropagationEdgesSupplier<Label?> {
        try {
            return AspectPropagationEdgesSupplier.createForToolchainsAspects(
                StarlarkList.immutableCopyOf<T?>(toolchainsAspects),  /* thread= */
                null,
                LabelConverter(PackageIdentifier.createInMainRepo("quux"), RepositoryMapping.EMPTY)
            )
        } catch (e: EvalException) {
            throw IllegalStateException(e)
        }
    }

    val FALSE_ADVERTISEMENT_ASPECT
            : FalseAdvertisementAspect = FalseAdvertisementAspect()
    private val FALSE_ADVERTISEMENT_DEFINITION: AspectDefinition? = Builder(FALSE_ADVERTISEMENT_ASPECT)
        .advertiseProvider(
            ImmutableList.of<E?>(
                StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY)
            )
        )
        .build()

    /**
     * A common base rule for mock rules in this class to reduce boilerplate.
     * 
     * 
     * It has a few common attributes because internal Blaze machinery assumes the presence of
     * these.
     */
    val BASE_RULE: MockRule = MockRule { MockRule.Companion.factory(DummyRuleFactory::class.java).define("base") }

    val FILE_PROVIDER_ASPECT_REQUIRING_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(FileProviderForwardingRuleFactory::class.java)
            .define(
                "file_provider_aspect",
                attr("dep", LABEL)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .aspect(FILE_PROVIDER_ASPECT)
            )
    }

    /**
     * A rule that defines an aspect on one of its attributes.
     */
    val ASPECT_REQUIRING_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "aspect",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(SIMPLE_ASPECT),
            attr("bar", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(SIMPLE_ASPECT)
        )
    }

    /**
     * A rule that defines different aspects on different attributes.
     */
    val MULTI_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(MultiAspectRuleFactory::class.java).define(
            "multi_aspect",
            attr("foo", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                .mandatory()
                .aspect(FOO_PROVIDER_ASPECT),
            attr("bar", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE)
                .mandatory()
                .aspect(BAR_PROVIDER_ASPECT)
        )
    }

    private val TEST_ASPECT_PARAMETERS_EXTRACTOR: com.google.common.base.Function<Rule?, AspectParameters?> =
        Function { rule: Rule? ->
            if (rule.isAttrDefined("baz", STRING)) {
                val value = rule.getAttr("baz").toString()
                if (value != "") {
                    return@Function Builder().addAttribute("baz", value).build()
                }
            }
            AspectParameters.EMPTY
        }

    /**
     * A rule that defines an [AspectRequiringProvider] on one of its attributes.
     */
    val ASPECT_REQUIRING_PROVIDER_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "aspect_requiring_provider",
            MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                builder
                    .add(
                        attr("foo", LABEL_LIST)
                            .allowedFileTypes(FileTypeSet.ANY_FILE)
                            .aspect(ASPECT_REQUIRING_PROVIDER, TEST_ASPECT_PARAMETERS_EXTRACTOR)
                    )
                    .add(attr("baz", STRING))
            })
    }

    /**
     * A rule that defines an [AspectRequiringProviderSets] on one of its attributes.
     */
    val ASPECT_REQUIRING_PROVIDER_SETS_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "aspect_requiring_provider_sets",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(ASPECT_REQUIRING_PROVIDER_SETS),
            attr("baz", STRING)
        )
    }

    /**
     * A rule that defines an [ExtraAttributeAspect] on one of its attributes.
     */
    val EXTRA_ATTRIBUTE_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "rule_with_extra_deps_aspect",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(EXTRA_ATTRIBUTE_ASPECT)
        )
    }

    /** A rule that defines an [PackageGroupAttributeAspect] on one of its attributes.  */
    val PACKAGE_GROUP_ATTRIBUTE_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(DummyRuleFactory::class.java)
            .define(
                "rule_with_package_group_deps_aspect",
                attr("foo", LABEL_LIST)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .aspect(PACKAGE_GROUP_ATTRIBUTE_ASPECT)
            )
    }

    /** A rule that defines an [ComputedAttributeAspect] on one of its attributes.  */
    val COMPUTED_ATTRIBUTE_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(DummyRuleFactory::class.java)
            .define(
                "rule_with_computed_deps_aspect",
                attr("foo", LABEL_LIST)
                    .allowedFileTypes(FileTypeSet.ANY_FILE)
                    .aspect(COMPUTED_ATTRIBUTE_ASPECT)
            )
    }

    /**
     * A rule that defines an [ParametrizedDefinitionAspect] on one of its attributes.
     */
    val PARAMETERIZED_DEFINITION_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(
            DummyRuleFactory::class.java
        ).define(
            "parametrized_definition_aspect",
            MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                builder
                    .add(
                        attr("foo", LABEL_LIST)
                            .allowedFileTypes(FileTypeSet.ANY_FILE)
                            .aspect(PARAMETRIZED_DEFINITION_ASPECT, TEST_ASPECT_PARAMETERS_EXTRACTOR)
                    )
                    .add(attr("baz", STRING))
            })
    }


    /**
     * A rule that defines an [ExtraAttributeAspectRequiringProvider] on one of its attributes.
     */
    val EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(
            DummyRuleFactory::class.java
        ).define(
            "extra_attribute_aspect_requiring_provider",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER)
        )
    }

    /**
     * A rule that defines an [AllAttributesAspect] on one of its attributes.
     */
    val ALL_ATTRIBUTES_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "all_attributes_aspect",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(ALL_ATTRIBUTES_ASPECT)
        )
    }

    /** A rule that defines an [AllAttributesWithToolAspect] on one of its attributes.  */
    val ALL_ATTRIBUTES_WITH_TOOL_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(
            DummyRuleFactory::class.java
        ).define(
            "all_attributes_with_tool_aspect",
            attr("foo", LABEL_LIST)
                .allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(ALL_ATTRIBUTES_WITH_TOOL_ASPECT)
        )
    }

    /**
     * A rule that defines a [WarningAspect] on one of its attributes.
     */
    val WARNING_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "warning_aspect",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(WARNING_ASPECT),
            attr("bar", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
        )
    }

    /**
     * A rule that defines an [ErrorAspect] on one of its attributes.
     */
    val ERROR_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "error_aspect",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
                .aspect(ERROR_ASPECT),
            attr("bar", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE)
        )
    }

    /**
     * A simple rule that has an attribute.
     */
    val SIMPLE_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "simple",
            attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE),
            attr("foo1", LABEL).allowedFileTypes(FileTypeSet.ANY_FILE),
            attr("txt", STRING)
        )
    }


    /** A rule that advertises a provider and implements it.  */
    val HONEST_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(DummyRuleFactory::class.java)
            .define(
                "honest",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                        .advertiseStarlarkProvider(
                            StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY)
                        )
                })
    }

    /** A rule that advertises another, different provider and implements it.  */
    val HONEST_RULE_2: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(DummyRuleFactory2::class.java)
            .define(
                "honest2",
                MockRuleCustomBehavior { builder: RuleClass.Builder?, env: RuleDefinitionEnvironment? ->
                    builder
                        .add(attr("foo", LABEL_LIST).allowedFileTypes(FileTypeSet.ANY_FILE))
                        .advertiseStarlarkProvider(
                            StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER2_KEY)
                        )
                })
    }

    /** Rule with an implicit dependency.  */
    val IMPLICIT_DEP_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass)
            .factory(DummyRuleFactory::class.java)
            .define(
                "implicit_dep",
                attr("\$dep", LABEL).value(Label.parseCanonicalUnchecked("//extra:extra"))
            )
    }

    // TODO(b/65746853): provide a way to do this without passing the entire configuration
    private val PLUGINS_LABEL_LIST: LabelListLateBoundDefault<*>? = LabelListLateBoundDefault.fromTargetConfiguration(
        JavaConfiguration::class.java, { rule, attributes, javaConfig -> javaConfig.getPlugins() })

    val LATE_BOUND_DEP_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "late_bound_dep",
            attr(":plugins", LABEL_LIST).value(PLUGINS_LABEL_LIST)
        )
    }

    /**
     * Rule with [FalseAdvertisementAspect]
     */
    val FALSE_ADVERTISEMENT_ASPECT_RULE: MockRule = MockRule {
        ancestor(BASE_RULE.javaClass).factory(DummyRuleFactory::class.java).define(
            "false_advertisement_aspect",
            attr("deps", LABEL_LIST).allowedFileTypes().aspect(FALSE_ADVERTISEMENT_ASPECT)
        )
    }

    /**
     * A transitive info provider for collecting aspects in the transitive closure. Created by
     * aspects.
     */
    @ThreadSafety.Immutable
    class AspectInfo(data: NestedSet<String?>?) : TransitiveInfoProvider {
        private val data: NestedSet<String?>?

        init {
            this.data = data
        }

        fun getData(): NestedSet<String?>? {
            return data
        }
    }

    /**
     * A transitive info provider used as sentinel. Created by aspects.
     */
    @ThreadSafety.Immutable
    class FooProvider : TransitiveInfoProvider

    /**
     * A transitive info provider used as sentinel. Created by aspects.
     */
    @ThreadSafety.Immutable
    class BarProvider : TransitiveInfoProvider

    /**
     * A transitive info provider for collecting aspects in the transitive closure. Created by
     * rules.
     */
    @ThreadSafety.Immutable
    class RuleInfo(data: NestedSet<String?>?) : TransitiveInfoProvider {
        private val data: NestedSet<String?>?

        init {
            this.data = data
        }

        fun getData(): NestedSet<String?>? {
            return data
        }
    }

    class FileProviderForwardingRuleFactory : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget {
            return RuleConfiguredTargetBuilder(ruleContext)
                .setFilesToBuild(ruleContext.getPrerequisite("dep", FileProvider::class.java).getFilesToBuild())
                .setRunfilesSupport(null, null)
                .addProvider(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
                .build()
        }
    }

    /**
     * A simple rule configured target factory that is used in all the mock rules in this class.
     */
    class DummyRuleFactory : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget? {
            val builder: RuleConfiguredTargetBuilder =
                RuleConfiguredTargetBuilder(ruleContext)
                    .addProvider(
                        RuleInfo(collectAspectData("rule " + ruleContext.getLabel(), ruleContext))
                    )
                    .setFilesToBuild(NestedSetBuilder.< Artifact > create < Artifact ? > (Order.STABLE_ORDER))
                    .setRunfilesSupport(null, null)
                    .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))

            if (ruleContext.getRule().getRuleClassObject().getName().equals("honest")) {
                builder.addStarlarkDeclaredProvider(
                    StarlarkInfo.create(REQUIRED_PROVIDER, ImmutableMap.of<K?, V?>())
                )
            }

            return builder.build()
        }
    }

    /** A simple rule configured target factory that exports provider [.REQUIRED_PROVIDER2].  */
    class DummyRuleFactory2 : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget {
            return RuleConfiguredTargetBuilder(ruleContext)
                .addProvider(
                    RuleInfo(collectAspectData("rule " + ruleContext.getLabel(), ruleContext))
                )
                .setFilesToBuild(NestedSetBuilder.< Artifact > create < Artifact ? > (Order.STABLE_ORDER))
                .setRunfilesSupport(null, null)
                .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))
                .addStarlarkDeclaredProvider(StarlarkInfo.create(REQUIRED_PROVIDER, ImmutableMap.of<K?, V?>()))
                .addStarlarkDeclaredProvider(StarlarkInfo.create(REQUIRED_PROVIDER2, ImmutableMap.of<K?, V?>()))
                .build()
        }
    }

    /**
     * A simple rule configured target factory that expects different providers added through
     * different aspects.
     */
    class MultiAspectRuleFactory : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext): ConfiguredTarget {
            val fooAttribute: TransitiveInfoCollection =
                ruleContext.getRulePrerequisitesCollection().getPrerequisite("foo")
            val barAttribute: TransitiveInfoCollection =
                ruleContext.getRulePrerequisitesCollection().getPrerequisite("bar")

            val infoBuilder: NestedSetBuilder<String?> =
                NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()

            if (fooAttribute.getProvider(FooProvider::class.java) != null) {
                infoBuilder.add("foo")
            }
            if (barAttribute.getProvider(BarProvider::class.java) != null) {
                infoBuilder.add("bar")
            }

            val builder: RuleConfiguredTargetBuilder =
                RuleConfiguredTargetBuilder(ruleContext)
                    .addProvider(
                        RuleInfo(infoBuilder.build())
                    )
                    .setFilesToBuild(NestedSetBuilder.< Artifact > create < Artifact ? > (Order.STABLE_ORDER))
                    .setRunfilesSupport(null, null)
                    .add(RunfilesProvider::class.java, RunfilesProvider.simple(Runfiles.EMPTY))

            return builder.build()
        }
    }

    /**
     * A base class for mock aspects to reduce boilerplate.
     */
    abstract class BaseAspect : NativeAspectClass(), ConfiguredAspectFactory {
        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            val information = if (parameters.isEmpty())
                ""
            else
                " data " + Iterables.getFirst<T?>(parameters.getAttribute("baz"), null)
            return Builder(ruleContext)
                .addProvider(
                    AspectInfo(
                        collectAspectData("aspect " + ruleContext.getLabel() + information, ruleContext)
                    )
                )
                .build()
        }
    }

    class FileProviderAspect : BaseAspect() {
        @Throws(ActionConflictException::class, InterruptedException::class)
        override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            val artifact: Artifact? = ruleContext.getBinArtifact("file_provider_aspect_file")
            ruleContext.registerAction(FileWriteAction.create(ruleContext, artifact, "empty", false))
            return Builder(ruleContext)
                .addProvider(FileProvider.of(NestedSetBuilder.create(Order.STABLE_ORDER, artifact)))
                .build()
        }

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return SIMPLE_ASPECT_DEFINITION
        }
    }

    /** Simple StarlarkNativeAspect  */
    class SimpleStarlarkNativeAspect : StarlarkNativeAspect(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return SIMPLE_STARLARK_NATIVE_ASPECT_DEFINITION
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext).addProvider(FooProvider()).build()
        }
    }

    /**
     * A very simple aspect.
     */
    class SimpleAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return SIMPLE_ASPECT_DEFINITION
        }
    }

    /**
     * A simple aspect that propagates a FooProvider provider.
     */
    class FooProviderAspect : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return FOO_PROVIDER_ASPECT_DEFINITION
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext).addProvider(FooProvider()).build()
        }
    }

    /** A simple aspect that propagates a BarProvider provider.  */
    class BarProviderAspect : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return BAR_PROVIDER_ASPECT_DEFINITION
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext).addProvider(BarProvider()).build()
        }
    }

    /** An aspect that defines its own implicit attribute.  */
    class ExtraAttributeAspect(
        depLabel: String?,
        private val applyToFiles: Boolean,
        vararg requiredAspectProviders: StarlarkProviderIdentifier?
    ) : BaseAspect() {
        private val depLabel: Label
        private val requiredAspectProviders: Array<StarlarkProviderIdentifier?>

        init {
            this.depLabel = Label.parseCanonicalUnchecked(depLabel)
            this.requiredAspectProviders = requiredAspectProviders
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            val dep: TransitiveInfoCollection? =
                (ruleContext as AspectContext)
                    .getMainAspectPrerequisitesCollection()
                    .getPrerequisite("\$dep")
            if (dep == null) {
                ruleContext.attributeError("\$dep", "\$dep attribute not resolved")
                return ConfiguredAspect.builder(ruleContext).build()
            }
            try {
                return ConfiguredAspect.builder(ruleContext)
                    .addStarlarkDeclaredProvider(
                        StarlarkInfo.create(
                            PROVIDER, ImmutableMap.of<K?, V?>("label", dep.label.getCanonicalForm())
                        )
                    )
                    .build()
            } catch (e: EvalException) {
                throw IllegalStateException(e)
            }
        }

        val name: String?
            get() = java.lang.String.format("%s_%s_%s", super.getName(), depLabel.getCanonicalForm(), applyToFiles)

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
            val aspectDefinition: AspectDefinition.Builder =
                Builder(this)
                    .add(attr("\$dep", LABEL).value(depLabel))
                    .applyToFiles(applyToFiles)
                    .advertiseProvider(
                        ImmutableList.of<E?>(StarlarkProviderIdentifier.forKey(PROVIDER.getKey()))
                    )

            if (requiredAspectProviders.size > 0) {
                aspectDefinition.requireAspectsWithProviders(
                    ImmutableList.of<E?>(ImmutableSet.< E > copyOf < E ? > (requiredAspectProviders))
                )
            }

            return aspectDefinition.build()
        }

        companion object {
            /** Test provider which includes the `dep` label.  */
            @SerializationConstant
            val PROVIDER: StarlarkProvider = StarlarkProvider.builder(Location.BUILTIN)
                .buildExported(Key(keyForBuild(FAKE_LABEL), "Provider"))
        }
    }

    /**
     * An aspect that applies to output files and propagates to toolchain dependencies and attribute
     * dependencies.
     */
    class DepsVisitingFileAspect(private val depAttr: String, private val toolchainType: String) : BaseAspect() {
        @Throws(ActionConflictException::class, InterruptedException::class)
        override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            try {
                return ConfiguredAspect.builder(ruleContext)
                    .addStarlarkDeclaredProvider(
                        StarlarkInfo.create(
                            PROVIDER, ImmutableMap.of<K?, V?>("val", ct.getLabel().getCanonicalForm())
                        )
                    )
                    .build()
            } catch (e: EvalException) {
                throw IllegalStateException(e)
            }
        }

        val name: String?
            get() = java.lang.String.format("%s_%s_%s", super.getName(), depAttr, toolchainType)

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
            val aspectDefinition: AspectDefinition.Builder =
                Builder(this)
                    .applyToFiles(true)
                    .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>(depAttr)))
                    .propagateToToolchainsTypes(createToolchainsAspects(ImmutableList.of<String?>(toolchainType)))

            return aspectDefinition.build()
        }

        companion object {
            /** Test provider which includes the base target label.  */
            @SerializationConstant
            val PROVIDER: StarlarkProvider? = StarlarkProvider.builder(Location.BUILTIN)
                .buildExported(Key(keyForBuild(FAKE_LABEL), "AspectProvider"))
        }
    }

    /** An aspect that defines its own implicit attribute, requiring PackageSpecificationProvider.  */
    class PackageGroupAttributeAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return PACKAGE_GROUP_ATTRIBUTE_ASPECT_DEFINITION
        }
    }

    /** An aspect that defines its own computed default attribute.  */
    class ComputedAttributeAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return COMPUTED_ATTRIBUTE_ASPECT_DEFINITION
        }
    }

    /**
     * An aspect that propagates along all attributes.
     */
    class AllAttributesAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ALL_ATTRIBUTES_ASPECT_DEFINITION
        }
    }

    /** An aspect that propagates along all attributes and has a tool dependency.  */
    class AllAttributesWithToolAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ALL_ATTRIBUTES_WITH_TOOL_ASPECT_DEFINITION
        }
    }

    /**
     * An aspect that requires aspects on the attributes of rules it attaches to.
     */
    class AttributeAspect : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ATTRIBUTE_ASPECT_DEFINITION
        }
    }

    /**
     * An aspect that defines its own implicit attribute and requires provider.
     */
    class ExtraAttributeAspectRequiringProvider : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return EXTRA_ATTRIBUTE_ASPECT_REQUIRING_PROVIDER_DEFINITION
        }
    }

    class AspectRequiringProvider : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ASPECT_REQUIRING_PROVIDER_DEFINITION
        }
    }

    /**
     * An aspect that requires provider sets [.REQUIRED_PROVIDER] and [ ][.REQUIRED_PROVIDER2].
     */
    class AspectRequiringProviderSets : BaseAspect() {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ASPECT_REQUIRING_PROVIDER_SETS_DEFINITION
        }
    }

    /** A native aspect exposed to Starlark and advertises a simple provider.  */
    class StarlarkNativeAspectWithProvider : StarlarkNativeAspect(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
            val builder: AspectDefinition.Builder =
                Builder(STARLARK_NATIVE_ASPECT_WITH_PROVIDER)
            builder.requireStarlarkProviders(StarlarkProviderIdentifier.forKey(REQUIRED_PROVIDER_KEY))
            return builder.build()
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext)
                .addStarlarkTransitiveInfo("native_aspect_prov", "native_aspect_val")
                .build()
        }
    }

    /**
     * An aspect that has a definition depending on parameters provided by originating rule and
     * advertises a simple provider.
     */
    class ParametrizedAspectWithProvider : StarlarkNativeAspect(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters): AspectDefinition {
            val builder: AspectDefinition.Builder =
                Builder(PARAMETRIZED_STARLARK_NATIVE_ASPECT_WITH_PROVIDER)
            val aspectAttr: ImmutableCollection<String?>? = aspectParameters.getAttribute("aspect_attr")
            if (aspectAttr != null) {
                builder.add(
                    attr("aspect_attr", Type.STRING)
                        .allowedValues(AllowedValueSet("v1", "v2"))
                        .value(aspectAttr.iterator().next())
                )
            }
            return builder.build()
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(ruleContext).addProvider(FooProvider()).build()
        }

        val defaultParametersExtractor: Function<Rule, AspectParameters>
            get() = Function { rule: Rule? ->
                val attributes: AttributeMap = RawAttributeMapper.of(rule)
                Builder()
                    .addAttribute("aspect_attr", attributes.get("aspect_attr", Type.STRING))
                    .build()
            } as Function<Rule?, AspectParameters?>

        val paramAttributes: ImmutableSet<String?>
            get() = ImmutableSet.of<String?>("aspect_attr")
    }

    /**
     * An aspect that has a definition depending on parameters provided by originating rule.
     */
    class ParametrizedDefinitionAspect : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters): AspectDefinition {
            val builder: AspectDefinition.Builder =
                Builder(PARAMETRIZED_DEFINITION_ASPECT)
                    .propagateToAttributes(createAttrAspects(ImmutableList.of<String?>("foo")))
            val baz: ImmutableCollection<String?>? = aspectParameters.getAttribute("baz")
            if (baz != null) {
                try {
                    builder.add(attr("\$dep", LABEL).value(Label.parseCanonical(baz.iterator().next())))
                } catch (e: LabelSyntaxException) {
                    throw IllegalStateException(e)
                }
            }
            return builder.build()
        }

        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            val information = StringBuilder("aspect " + ruleContext.getLabel())
            if (!parameters.isEmpty()) {
                information.append(" data " + Iterables.getFirst<T?>(parameters.getAttribute("baz"), null))
                information.append(" ")
            }
            val deps: MutableList<out TransitiveInfoCollection> =
                (ruleContext as AspectContext)
                    .getMainAspectPrerequisitesCollection()
                    .getPrerequisites("\$dep")
            information.append("\$dep:[")
            for (dep in deps) {
                information.append(" ")
                information.append(dep.label)
            }
            information.append("]")
            return Builder(ruleContext)
                .addProvider(AspectInfo(collectAspectData(information.toString(), ruleContext)))
                .build()
        }
    }

    /**
     * An aspect that prints a warning.
     */
    class WarningAspect : NativeAspectClass(), ConfiguredAspectFactory {
        @Throws(ActionConflictException::class, InterruptedException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            ruleContext.ruleWarning("Aspect warning on " + targetLabel)
            return Builder(ruleContext).build()
        }

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return WARNING_ASPECT_DEFINITION
        }
    }

    /**
     * An aspect that raises an error.
     */
    class ErrorAspect : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            ruleContext: RuleContext,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect? {
            ruleContext.ruleError("Aspect error")
            return null
        }

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return ERROR_ASPECT_DEFINITION
        }
    }

    /**
     * An aspect that advertises but fails to provide providers.
     */
    class FalseAdvertisementAspect : NativeAspectClass(), ConfiguredAspectFactory {
        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition? {
            return FALSE_ADVERTISEMENT_DEFINITION
        }

        @Throws(InterruptedException::class, ActionConflictException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget?,
            context: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return Builder(context).build()
        }
    }

    /** Aspect that propagates over rule outputs.  */
    class AspectApplyingToFiles : NativeAspectClass(), ConfiguredAspectFactory {
        /** Simple provider for testing  */
        @ThreadSafety.Immutable
        class Provider private constructor(label: Label?) : TransitiveInfoProvider {
            private val label: Label?

            init {
                this.label = label
            }

            fun getLabel(): Label? {
                return label
            }
        }

        public override fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition {
            return AspectDefinition.builder(this).applyToFiles(true).build()
        }

        @Throws(InterruptedException::class, ActionConflictException::class)
        public override fun create(
            targetLabel: Label?,
            ct: ConfiguredTarget,
            context: RuleContext?,
            parameters: AspectParameters?,
            toolsRepository: RepositoryName?
        ): ConfiguredAspect {
            return ConfiguredAspect.builder(context)
                .addProvider(Provider::class.java, AspectApplyingToFiles.Provider(ct.getLabel()))
                .build()
        }
    }
}
