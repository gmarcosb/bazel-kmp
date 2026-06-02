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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.collect.*
import com.google.common.hash.HashFunction
import com.google.common.io.BaseEncoding
import com.google.devtools.build.lib.query2.proto.proto2api.Build.Target.Discriminator.ENVIRONMENT_GROUP
import java.io.OutputStream
import java.lang.String
import kotlin.Any
import kotlin.AssertionError
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.IllegalArgumentException
import kotlin.collections.ArrayList
import kotlin.collections.Iterable
import kotlin.collections.MutableCollection
import kotlin.collections.MutableList
import kotlin.collections.MutableMap
import kotlin.toString

/**
 * An output formatter that outputs a protocol buffer representation of a query result and outputs
 * the proto bytes to the output print stream. By taking the bytes and calling `mergeFrom()`
 * on a `Build.QueryResult` object the full result can be reconstructed.
 */
open class ProtoOutputFormatter : AbstractUnorderedFormatter() {
    private var aspectResolver: AspectResolver? = null
    private var dependencyFilter: DependencyFilter? = null
    private var packageGroupIncludesDoubleSlash = false
    private var relativeLocations = false
    private var includeDefaultValues = true
    private var ruleAttributePredicate: Predicate<String?> = Predicates.alwaysTrue<String?>()
    private var flattenSelects = true
    private var includeLocations = true
    private var includeRuleInputsAndOutputs = true
    private var includeSyntheticAttributeHash = false
    private var includeInstantiationStack = false
    private var includeDefinitionStack = false
    private var includeStarlarkRuleEnv = true
    protected var includeAttributeSourceAspects: Boolean = false
    private var hashFunction: HashFunction? = null

    /** Non-null if and only if --proto:rule_classes option is set.  */
    private var ruleClassInfoFormatter: RuleClassInfoFormatter? = null

    private var eventHandler: EventHandler? = null

    override fun getName(): String? {
        return "proto"
    }

    override fun setOptions(
        options: CommonQueryOptions, aspectResolver: AspectResolver?, hashFunction: HashFunction?
    ) {
        super.setOptions(options, aspectResolver, hashFunction)
        this.aspectResolver = aspectResolver
        this.dependencyFilter = FormatUtils.getDependencyFilter(options)
        this.packageGroupIncludesDoubleSlash = options.getIncompatiblePackageGroupIncludesDoubleSlash()
        this.relativeLocations = options.getRelativeLocations()
        this.includeDefaultValues = options.getProtoIncludeDefaultValues()
        this.ruleAttributePredicate = newAttributePredicate(options.getProtoOutputRuleAttributes())
        this.flattenSelects = options.getProtoFlattenSelects()
        this.includeLocations = options.getProtoIncludeLocations()
        this.includeRuleInputsAndOutputs = options.getProtoIncludeRuleInputsAndOutputs()
        this.includeSyntheticAttributeHash = options.getProtoIncludeSyntheticAttributeHash()
        this.includeInstantiationStack = options.getProtoIncludeInstantiationStack()
        this.includeDefinitionStack = options.getProtoIncludeDefinitionStack()
        this.includeAttributeSourceAspects = options.getProtoIncludeAttributeSourceAspects()
        this.includeStarlarkRuleEnv = options.getProtoIncludeStarlarkRuleEnv()
        this.hashFunction = hashFunction
        this.ruleClassInfoFormatter =
            if (options.getProtoRuleClasses()) RuleClassInfoFormatter() else null
    }

    override fun setEventHandler(eventHandler: EventHandler?) {
        this.eventHandler = eventHandler
    }

    override fun createPostFactoStreamCallback(
        out: OutputStream, options: QueryOptions?, labelPrinter: LabelPrinter
    ): OutputFormatterCallback<Target?>? {
        return StreamedQueryResultFormatter(out, labelPrinter)
    }

    override fun createStreamCallback(
        out: OutputStream, options: QueryOptions?, env: QueryEnvironment<*>
    ): ThreadSafeOutputFormatterCallback<Target?> {
        return SynchronizedDelegatingOutputFormatterCallback<Target?>(
            createPostFactoStreamCallback(out, options, env.getLabelPrinter())
        )
    }

    /** Converts a logical [Target] object into a [Build.Target] protobuffer.  */
    @Throws(InterruptedException::class)
    fun toTargetProtoBuffer(target: Target, labelPrinter: LabelPrinter): Build.Target {
        return toTargetProtoBuffer(target, labelPrinter,  /* extraDataForAttrHash= */"")
    }

    /** Converts a logical [Target] object into a [Build.Target] protobuffer.  */
    @Throws(InterruptedException::class)
    fun toTargetProtoBuffer(
        target: Target, labelPrinter: LabelPrinter, extraDataForAttrHash: Any?
    ): Build.Target {
        val targetPb: Build.Target.Builder = Build.Target.newBuilder()

        if (target is Rule) {
            val rulePb: Build.Rule.Builder =
                Build.Rule.newBuilder()
                    .setName(StringEncoding.internalToUnicode(labelPrinter.toString(target.getLabel())))
                    .setRuleClass(StringEncoding.internalToUnicode(target.getRuleClass()))
            if (includeLocations) {
                rulePb.setLocation(StringEncoding.internalToUnicode(FormatUtils.getLocation(target, relativeLocations)))
            }
            addAttributes(rulePb, target, extraDataForAttrHash, labelPrinter)
            val transitiveDigest: ByteArray? = target.getRuleClassObject().ruleDefinitionEnvironmentDigest
            if (transitiveDigest != null && includeRuleDefinitionEnvironment()) {
                // The RuleDefinitionEnvironment is always defined for Starlark rules and
                // always null for non Starlark rules.
                rulePb.addAttribute(
                    Build.Attribute.newBuilder()
                        .setName(RULE_IMPLEMENTATION_HASH_ATTR_NAME)
                        .setType(ProtoUtils.getDiscriminatorFromType(Type.STRING))
                        .setStringValue(
                            BaseEncoding.base16().lowerCase().encode(transitiveDigest)
                        )
                ) // hexify
            }

            val aspectsDependencies: ImmutableMap<Aspect?, ImmutableMultimap<Attribute?, Label?>?> =
                aspectResolver.computeAspectDependencies(target, dependencyFilter)
            if (!aspectsDependencies.isEmpty()) {
                // Add information about additional attributes from aspects.
                val attributes: MutableList<Build.Attribute?> = ArrayList<Build.Attribute?>()
                for (aspectAttributes in aspectsDependencies.entries) {
                    val aspect: Aspect? = aspectAttributes.key
                    for (entry in aspectAttributes.value!!.asMap().entries) {
                        val attribute: Attribute = entry.key
                        val labels: MutableCollection<Label?> = entry.value
                        if (!includeAspectAttribute(attribute, labels)) {
                            continue
                        }
                        val attributeValue = getAspectAttributeValue(target, attribute, labels)
                        val serializedAttribute: Build.Attribute? =
                            AttributeFormatter.getAttributeProto(
                                attribute,
                                attributeValue,  /* explicitlySpecified= */
                                false,  /* encodeBooleanAndTriStateAsIntegerAndString= */
                                true,  /* sourceAspect= */
                                aspect,
                                includeAttributeSourceAspects,
                                labelPrinter
                            )
                        attributes.add(serializedAttribute)
                    }
                }

                rulePb.addAllAttribute(
                    attributes.stream().distinct().sorted(ATTRIBUTE_NAME).collect(Collectors.toList())
                )
            }
            if (includeRuleInputsAndOutputs) {
                // Add all deps from aspects as rule inputs of current target.
                if (!aspectsDependencies.isEmpty()) {
                    aspectsDependencies.values.stream()
                        .flatMap<Label?> { m: ImmutableMultimap<Attribute?, Label?>? -> m!!.values().stream() }
                        .distinct()
                        .forEach { dep: Label? ->
                            rulePb.addRuleInput(
                                StringEncoding.internalToUnicode(
                                    labelPrinter.toString(
                                        dep
                                    )
                                )
                            )
                        }
                }
                // Include explicit elements for all direct inputs and outputs of a rule; this goes beyond
                // what is available from the attributes above, since it may also (depending on options)
                // include implicit outputs, exec-configuration outputs, and default values.
                target.getSortedLabels(dependencyFilter)
                    .forEach({ input -> rulePb.addRuleInput(StringEncoding.internalToUnicode(labelPrinter.toString(input))) })
                target.getOutputFiles().stream()
                    .distinct()
                    .forEach(
                        { output ->
                            rulePb.addRuleOutput(
                                StringEncoding.internalToUnicode(labelPrinter.toString(output.getLabel()))
                            )
                        })
            }
            for (feature in target.getPackageDeclarations().getPackageArgs().features().toStringList()) {
                rulePb.addDefaultSetting(StringEncoding.internalToUnicode(feature))
            }

            if (includeInstantiationStack) {
                for (fr in target.reconstructCallStack()) {
                    // Always report relative locations.
                    // (New fields needn't honor relativeLocations.)
                    rulePb.addInstantiationStack(
                        StringEncoding.internalToUnicode(
                            (FormatUtils.getRootRelativeLocation(fr.location, target.getPackageMetadata())
                                .toString() + ": "
                                    + fr.name)
                        )
                    )
                }
            }

            if (includeDefinitionStack && target.getRuleClassObject().isStarlark) {
                for (fr in target.getRuleClassObject().getCallStack()) {
                    // Always report relative locations.
                    // (New fields needn't honor relativeLocations.)
                    rulePb.addDefinitionStack(
                        StringEncoding.internalToUnicode(
                            (FormatUtils.getRootRelativeLocation(fr.location, target.getPackageMetadata())
                                .toString() + ": "
                                    + fr.name)
                        )
                    )
                }
            }

            if (ruleClassInfoFormatter != null) {
                ruleClassInfoFormatter!!.addRuleClassKeyAndInfoIfNeeded(rulePb, target)
            }
            targetPb.setType(RULE)
            targetPb.setRule(rulePb)
        } else if (target is OutputFile) {
            val label: Label? = target.getLabel()

            val generatingRule: Rule = target.getGeneratingRule()
            val output: GeneratedFile.Builder =
                GeneratedFile.newBuilder()
                    .setGeneratingRule(
                        StringEncoding.internalToUnicode(labelPrinter.toString(generatingRule.getLabel()))
                    )
                    .setName(StringEncoding.internalToUnicode(labelPrinter.toString(label)))

            if (includeLocations) {
                output.setLocation(StringEncoding.internalToUnicode(FormatUtils.getLocation(target, relativeLocations)))
            }
            targetPb.setType(GENERATED_FILE)
            targetPb.setGeneratedFile(output.build())
        } else if (target is InputFile) {
            val label: Label? = target.getLabel()

            val input: Build.SourceFile.Builder =
                Build.SourceFile.newBuilder().setName(StringEncoding.internalToUnicode(labelPrinter.toString(label)))

            if (includeLocations) {
                input.setLocation(StringEncoding.internalToUnicode(FormatUtils.getLocation(target, relativeLocations)))
            }

            if (target.getName().equals("BUILD")) {
                val starlarkLoadLabels: Iterable<Label?> =
                    if (aspectResolver == null)
                        target.getPackageDeclarations().getOrComputeTransitivelyLoadedStarlarkFiles()
                    else
                        aspectResolver.computeBuildFileDependencies(target)

                for (starlarkLoadLabel in starlarkLoadLabels) {
                    input.addSubinclude(StringEncoding.internalToUnicode(labelPrinter.toString(starlarkLoadLabel)))
                }

                for (feature in target.getPackageDeclarations().getPackageArgs().features().toStringList()) {
                    input.addFeature(StringEncoding.internalToUnicode(feature))
                }

                input.setPackageContainsErrors(target.getPackageoid().containsErrors())
            }

            // TODO(bazel-team): We're being inconsistent about whether we include the package's
            // default_visibility in the target. For files we do, but for rules we don't.
            for (visibilityDependency in target.getVisibilityDependencyLabels()) {
                input.addPackageGroup(StringEncoding.internalToUnicode(labelPrinter.toString(visibilityDependency)))
            }

            for (visibilityDeclaration in target.getVisibilityDeclaredLabels()) {
                input.addVisibilityLabel(StringEncoding.internalToUnicode(labelPrinter.toString(visibilityDeclaration)))
            }

            targetPb.setType(SOURCE_FILE)
            targetPb.setSourceFile(input)
        } else if (target is FakeLoadTarget) {
            val label: Label? = target.getLabel()
            val input: SourceFile.Builder =
                SourceFile.newBuilder().setName(StringEncoding.internalToUnicode(labelPrinter.toString(label)))

            if (includeLocations) {
                input.setLocation(StringEncoding.internalToUnicode(FormatUtils.getLocation(target, relativeLocations)))
            }
            targetPb.setType(SOURCE_FILE)
            targetPb.setSourceFile(input.build())
        } else if (target is PackageGroup) {
            val packageGroupPb: Build.PackageGroup.Builder =
                Build.PackageGroup.newBuilder()
                    .setName(StringEncoding.internalToUnicode(labelPrinter.toString(target.getLabel())))
            for (containedPackage in target.getContainedPackages(packageGroupIncludesDoubleSlash)) {
                packageGroupPb.addContainedPackage(StringEncoding.internalToUnicode(containedPackage))
            }
            for (include in target.getIncludes()) {
                packageGroupPb.addIncludedPackageGroup(StringEncoding.internalToUnicode(labelPrinter.toString(include)))
            }

            targetPb.setType(PACKAGE_GROUP)
            targetPb.setPackageGroup(packageGroupPb)
        } else if (target is EnvironmentGroup) {
            val envGroupPb: Build.EnvironmentGroup.Builder =
                Build.EnvironmentGroup.newBuilder()
                    .setName(StringEncoding.internalToUnicode(labelPrinter.toString(target.getLabel())))
            for (env in target.getEnvironments()) {
                envGroupPb.addEnvironment(StringEncoding.internalToUnicode(labelPrinter.toString(env)))
            }
            for (defaultEnv in target.getDefaults()) {
                envGroupPb.addDefault(StringEncoding.internalToUnicode(labelPrinter.toString(defaultEnv)))
            }
            targetPb.setType(ENVIRONMENT_GROUP)
            targetPb.setEnvironmentGroup(envGroupPb)
        } else {
            throw IllegalArgumentException(target.toString())
        }

        return targetPb.build()
    }

    protected open fun addAttributes(
        rulePb: Build.Rule.Builder,
        rule: Rule,
        extraDataForAttrHash: Any?,
        labelPrinter: LabelPrinter?
    ) {
        val serializedAttributes: MutableMap<Attribute?, Build.Attribute?> = HashMap<Attribute?, Build.Attribute?>()
        val attributeMapper: AggregatingAttributeMapper = AggregatingAttributeMapper.of(rule)
        for (attr in rule.getAttributes()) {
            if (!shouldIncludeAttribute(rule, attr)) {
                continue
            }
            val attributeValue: Any?
            if (flattenSelects || !attributeMapper.isConfigurable(attr.name)) {
                attributeValue = getFlattenedAttributeValues(attr.getType(), rule, attr)
            } else {
                attributeValue = attributeMapper.getSelectorList(attr.name, attr.getType())
            }
            val serializedAttribute: Build.Attribute? =
                AttributeFormatter.getAttributeProto(
                    attr,
                    attributeValue,
                    rule.isAttributeValueExplicitlySpecified(attr),  /* encodeBooleanAndTriStateAsIntegerAndString= */
                    true,  /* sourceAspect= */
                    null,
                    includeAttributeSourceAspects,
                    labelPrinter
                )
            serializedAttributes.put(attr, serializedAttribute)
        }
        rulePb.addAllAttribute(
            serializedAttributes.values.stream()
                .distinct()
                .sorted(ATTRIBUTE_NAME)
                .collect(Collectors.toList())
        )

        if (includeSyntheticAttributeHash) {
            rulePb.addAttribute(
                Build.Attribute.newBuilder()
                    .setName("\$internal_attr_hash")
                    .setStringValue(
                        SyntheticAttributeHashCalculator.compute(
                            rule,
                            serializedAttributes,
                            extraDataForAttrHash,
                            hashFunction,
                            includeAttributeSourceAspects,
                            includeStarlarkRuleEnv
                        )
                    )
                    .setType(Discriminator.STRING)
            )
        }
    }

    protected fun shouldIncludeAttribute(rule: Rule, attr: Attribute): Boolean {
        return (includeDefaultValues || rule.isAttributeValueExplicitlySpecified(attr))
                && ruleAttributePredicate.apply(attr.name)
    }

    private fun getAspectAttributeValue(
        target: Target, attribute: Attribute, labels: MutableCollection<Label?>
    ): Any? {
        val attributeType: Type<*> = attribute.getType()
        if (attributeType.equals(BuildType.LABEL)) {
            Preconditions.checkState(labels.size == 1, "attribute=%s, labels=%s", attribute, labels)
            return Iterables.getOnlyElement<Label?>(labels)
        } else if (attributeType.equals(BuildType.LABEL_KEYED_STRING_DICT)) {
            // Ideally we'd support LABEL_KEYED_STRING_DICT by getting the value directly from the aspect
            // definition vs. trying to reverse-construct it from the flattened labels as this method
            // does. Unfortunately any proper support surfaces a latent bug between --output=proto and
            // aspect attributes: "{@code labels} isn't the set of labels for a single attribute value but
            // for all values of all attributes with the same name. We can have multiple attributes with
            // the same name because multiple aspects may attach to a rule, and nothing is stopping them
            // from defining the same attribute names. That means the "Attribute" proto message doesn't
            // really represent a single attribute, in spite of its documented purpose. This all calls for
            // an API design upgrade to properly consider these relationships. Details at b/149982967.
            if (eventHandler != null) {
                eventHandler.handle(
                    Event.error(
                        String.format(
                            "Target \"%s\", aspect attribute \"%s\": type \"%s\" not yet supported with"
                                    + " --output=proto.",
                            target.getLabel(), attribute.name, BuildType.LABEL_KEYED_STRING_DICT
                        )
                    )
                )
            }
            // This return value is misleading when the above error isn't get triggered: it implies an
            // empty result with no signal that that result isn't accurate.
            // TODO(bazel-team): either make the result accurate or trigger an error universally. Letting
            // OutputFormatter.output() throw a QueryException is a promising approach.
            return ImmutableMap.of<Any?, Any?>()
        } else {
            Preconditions.checkState(
                attributeType.equals(BuildType.LABEL_LIST),
                "attribute=%s, type=%s, labels=%s",
                attribute,
                attributeType,
                labels
            )
            return labels
        }
    }

    /** Allow filtering of aspect attributes.  */
    protected fun includeAspectAttribute(attr: Attribute?, value: MutableCollection<Label?>?): Boolean {
        return true
    }

    protected fun includeRuleDefinitionEnvironment(): Boolean {
        return true
    }

    private class RuleClassInfoFormatter {
        private val ruleClassKeys: HashSet<kotlin.String?> = HashSet<kotlin.String?>()
        private val extractorContext: ExtractorContext? = ExtractorContext.builder()
            .labelRenderer(LabelRenderer.DEFAULT)
            .extractNativelyDefinedAttrs(true)
            .build()

        /**
         * Sets the rule_class_key field, and if the rule class key has not been seen before, also sets
         * the rule_class_info field.
         */
        fun addRuleClassKeyAndInfoIfNeeded(rulePb: Build.Rule.Builder, rule: Rule) {
            val ruleClassKey: kotlin.String? = rule.getRuleClassObject().getKey()
            rulePb.setRuleClassKey(StringEncoding.internalToUnicode(ruleClassKey))
            if (ruleClassKeys.add(ruleClassKey)) {
                // TODO(b/368091415): instead of rule.getRuleClass(), we should be using the rule's public
                // name. But to find the public name, we would need access to the globals dictionary of
                // the compiled Starlark module in which the rule class was defined, which would have to
                // be retrieved from skyframe.
                rulePb.setRuleClassInfo(
                    RuleInfoExtractor.buildRuleInfo(
                        extractorContext, rule.getRuleClass(), rule.getRuleClassObject()
                    )
                )
            }
        }
    }

    /**
     * Specialized [OutputFormatterCallback] implementation which produces a valid [ ] in streaming fashion. Internally this class makes some reasonably sound and stable
     * assumptions about the format of serialized protos in order to improve memory overhead and
     * performance.
     */
    private inner class StreamedQueryResultFormatter(out: OutputStream, labelPrinter: LabelPrinter) :
        OutputFormatterCallback<Target?>() {
        private val codedOut: CodedOutputStream
        private val labelPrinter: LabelPrinter

        init {
            this.codedOut = CodedOutputStream.newInstance(out, OUTPUT_BUFFER_SIZE)
            this.labelPrinter = labelPrinter
        }

        @Throws(IOException::class, InterruptedException::class)
        override fun processOutput(partialResult: Iterable<Target>) {
            // Write out targets with their tag (field number) as if they were serialized as part of a
            // QueryResult proto. The assumptions we make about this being compatible with actually
            // constructing and serializing a QueryResult proto are protected by test coverage and proto
            // best practices.
            for (target in partialResult) {
                codedOut.writeMessage(
                    QueryResult.TARGET_FIELD_NUMBER, toTargetProtoBuffer(target, labelPrinter)
                )
            }
        }

        @Throws(IOException::class)
        override fun close(failFast: Boolean) {
            codedOut.flush()
        }

        companion object {
            /**
             * Pseudo-arbitrarily chosen buffer size for output. Chosen to be large enough to fit a handful
             * of targets without needing to flush to the underlying output, which may not be buffered.
             */
            private const val OUTPUT_BUFFER_SIZE = 16384
        }
    }

    companion object {
        /** A special attribute name for the rule implementation hash code.  */
        protected const val RULE_IMPLEMENTATION_HASH_ATTR_NAME: kotlin.String = "\$rule_implementation_hash"

        private val ATTRIBUTE_NAME: Comparator<Build.Attribute?>? =
            Comparator.comparing<Build.Attribute?, Any?>(Build.Attribute::getName)

        private val SCALAR_TYPES: ImmutableSet<Type<*>?> = ImmutableSet.of<Type<*>?>(
            Type.INTEGER,
            Type.STRING,
            BuildType.LABEL,
            BuildType.NODEP_LABEL,
            BuildType.DORMANT_LABEL,
            BuildType.OUTPUT,
            Type.BOOLEAN,
            BuildType.TRISTATE,
            BuildType.LICENSE
        )

        private fun newAttributePredicate(outputAttributes: MutableList<kotlin.String?>): Predicate<kotlin.String?> {
            if (outputAttributes == ImmutableList.of<kotlin.String?>("all")) {
                return Predicates.alwaysTrue<kotlin.String?>()
            } else if (outputAttributes.isEmpty()) {
                return Predicates.alwaysFalse<kotlin.String?>()
            } else {
                return Predicates.`in`<kotlin.String?>(ImmutableSet.copyOf<kotlin.String?>(outputAttributes))
            }
        }

        /**
         * Coerces the list `possibleValues` of values of type `attrType` to a single value of
         * that type, in the following way:
         * 
         * 
         * If the list contains a single value, return that value.
         * 
         * 
         * If the list contains zero or multiple values and the type is a scalar type, return `null`.
         * 
         * 
         * If the list contains zero or multiple values and the type is a collection or map type, merge
         * the collections/maps in the list and return the merged collection/map.
         */
        private fun getFlattenedAttributeValues(attrType: Type<*>?, rule: Rule?, attr: Attribute): Any? {
            val treatMultipleAsNone: Boolean = SCALAR_TYPES.contains(attrType)
            val possibleValues =
                PossibleAttributeValues.forRuleAndAttribute(rule, attr, treatMultipleAsNone)

            // If there is only one possible value, return it.
            if (Iterables.size(possibleValues) == 1) {
                return Iterables.getOnlyElement<Any?>(possibleValues)
            }

            // Otherwise, there are multiple possible values. To conform to the message shape expected by
            // query output's clients, we must transform the list of possible values. This transformation
            // will be lossy, but this is the best we can do.

            // If the attribute's type is not a collection type, return null. Query output's clients do
            // not support list values for scalar attributes.
            if (SCALAR_TYPES.contains(attrType)) {
                return null
            }

            // If the attribute's type is a collection type, merge the list of collections into a single
            // collection. This is a sensible solution for query output's clients, which are happy to get
            // the union of possible values.
            // TODO(bazel-team): replace below with "is ListType" check (or some variant)
            if (attrType === Types.STRING_LIST || attrType === BuildType.LABEL_LIST || attrType === BuildType.NODEP_LABEL_LIST || attrType === BuildType.DORMANT_LABEL_LIST || attrType === BuildType.OUTPUT_LIST || attrType === Types.INTEGER_LIST) {
                val builder = ImmutableList.builder<Any?>()
                for (possibleValue in possibleValues) {
                    val collection = possibleValue as MutableCollection<Any>
                    for (o in collection) {
                        builder.add(o)
                    }
                }
                return builder.build()
            }

            // Same for maps as for collections.
            if (attrType === Types.STRING_DICT || attrType === Types.STRING_LIST_DICT || attrType === BuildType.LABEL_LIST_DICT || attrType === BuildType.LABEL_DICT_UNARY || attrType === BuildType.LABEL_KEYED_STRING_DICT) {
                val mergedDict: MutableMap<Any?, Any?> = HashMap<Any?, Any?>()
                for (possibleValue in possibleValues) {
                    val stringDict = possibleValue as MutableMap<Any?, Any?>
                    for (entry in stringDict.entries) {
                        mergedDict.put(entry.key, entry.value)
                    }
                }
                return mergedDict
            }

            throw AssertionError("Unknown type: " + attrType)
        }
    }
}
