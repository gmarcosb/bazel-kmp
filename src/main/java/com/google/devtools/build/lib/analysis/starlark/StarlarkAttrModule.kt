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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/**
 * A helper class to provide Attr module in Starlark.
 * 
 * 
 * It exposes functions (for example, 'attr.string', 'attr.label_list', etc.) to Starlark users.
 * The functions are executed through reflection. As everywhere in Starlark, arguments are
 * type-checked with the signature and cannot be null.
 */
class StarlarkAttrModule : StarlarkAttrModuleApi {
    private class MaterializationContext : StarlarkThreadContext(null)

    /** The object available as the `ctx` argument of materializers.  */
    private class StarlarkMaterializerContext(label: Label?, attributeMap: MutableMap<String?, Any?>?) : StarlarkValue {
        private val label: Label?
        private val attrs: StructImpl?

        init {
            this.label = label
            attrs =
                StructProvider.STRUCT.create(
                    attributeMap,
                    "attribute '%s' not available in materializer (it's not an attribute of the rule or"
                            + " it's not marked with 'for_dependency_resolution')"
                )
        }

        @StarlarkMethod(
            name = "attr",
            structField = true,
            doc = "A struct to access the attributes of the rule in a materializer function."
        )
        fun getAttr(): StructApi? {
            return attrs
        }

        @StarlarkMethod(
            name = "label",
            structField = true,
            doc = "The label of the rule whose attribute the materializer is computing."
        )
        fun getLabel(): Label? {
            return label
        }
    }

    private class StarlarkMaterializer<ValueT>
        (type: Type<ValueT?>, semantics: StarlarkSemantics?, implementation: StarlarkFunction?) :
        MaterializingDefault.Resolver<ValueT?, com.google.common.collect.ImmutableMap<String?, out TransitiveInfoCollection?>?> {
        private val type: Type<ValueT?>
        private val semantics: StarlarkSemantics?
        private val implementation: StarlarkFunction?

        init {
            this.type = type
            this.semantics = semantics
            this.implementation = implementation
        }

        fun computeAttributesForMaterializer(
            rule: Rule,
            attributeMap: AttributeMap,
            prerequisiteMap: MutableMap<String?, out TransitiveInfoCollection?>
        ): StarlarkMaterializerContext {
            val result: MutableMap<String?, Any?> = TreeMap<String?, Any?>()

            for (attribute in rule.getAttributes()) {
                if (attribute.getType().getLabelClass() === LabelClass.DEPENDENCY
                    && !attribute.isForDependencyResolution()
                ) {
                    continue
                }

                val value: Any? = attributeMap.get(attribute.getName(), attribute.getType())
                val starlarkValue: Any? =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertAttributeValue(
                        java.util.function.Supplier { prerequisiteMap.get(attribute.getName()) as MutableList<ConfiguredTarget?>? },
                        attribute,
                        value
                    )
                if (starlarkValue == null) {
                    continue
                }

                result.put(attribute.getPublicName(), starlarkValue)
            }

            return StarlarkMaterializerContext(
                rule.getLabel(),
                com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(result)
            )
        }

        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        public override fun resolve(
            rule: Rule,
            attributes: AttributeMap,
            prerequisiteMap: com.google.common.collect.ImmutableMap<String?, out TransitiveInfoCollection?>,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): ValueT? {
            // First compute the attributes for the materializer by merging the attribute map with the
            // prerequisite map...
            val ctx =
                computeAttributesForMaterializer(rule, attributes, prerequisiteMap)

            /** ...then call the implementation... */
            val starlarkResult = runMaterializer(ctx, eventHandler)

            // ...finally, convert the result to the appropriate type.
            if (type === BuildType.LABEL) {
                return when (starlarkResult) {
                    -> null
                    -> type.cast(d.label())
                    else -> throw net.starlark.java.eval.EvalException("Expected a single dormant dependency or None")
                }
            } else if (type === BuildType.LABEL_LIST) {
                val sequence: net.starlark.java.eval.Sequence<DormantDependency?> =
                    net.starlark.java.eval.Sequence.cast<DormantDependency?>(
                        starlarkResult,
                        DormantDependency::class.java,
                        "return value of materializer"
                    )
                val result: com.google.common.collect.ImmutableList<Label?> =
                    sequence.stream()
                        .map<Any?>(DormantDependency::getLabel)
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
                return type.cast(result)
            } else {
                throw java.lang.IllegalStateException()
            }
        }

        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        fun runMaterializer(ctx: Any?, eventHandler: com.google.devtools.build.lib.events.EventHandler?): Any? {
            Mutability.create("eval_starlark_materialization").use { mu ->
                val thread: StarlarkThread = StarlarkThread.createTransient(mu, semantics)
                thread.setPrintHandler(com.google.devtools.build.lib.events.Event.makeDebugPrintHandler(eventHandler))

                MaterializationContext().storeInThread(thread)
                return Starlark.positionalOnlyCall(thread, implementation, ctx)
            }
        }
    }

    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<attr>")
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun intAttribute(
        configurable: Any?,
        defaultValue: StarlarkInt?,
        doc: Any?,
        mandatory: Boolean?,
        values: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        // TODO(bazel-team): Replace literal strings with constants.
        checkContext(thread, "attr.int()")
        return createAttrDescriptor(
            "int",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                VALUES_ARG,
                values
            ),
            Type.INTEGER,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun stringAttribute(
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        mandatory: Boolean?,
        values: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.string()")
        return createAttrDescriptor(
            "string",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                VALUES_ARG,
                values
            ),
            Type.STRING,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun labelAttribute(
        configurable: Any?,
        defaultValue: Any?,  // Label | String | LateBoundDefaultApi | StarlarkFunction
        materializer: Any?,
        doc: Any?,
        executable: Boolean?,
        allowFiles: Any?,
        allowSingleFile: Any?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        allowRules: Any?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        flags: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.label()")

        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.LABEL,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                optionMap(
                    CONFIGURABLE_ARG,
                    configurable,
                    DEFAULT_ARG,
                    defaultValue,
                    MATERIALIZER_ARG,
                    materializer,
                    EXECUTABLE_ARG,
                    executable,
                    ALLOW_FILES_ARG,
                    allowFiles,
                    ALLOW_SINGLE_FILE_ARG,
                    allowSingleFile,
                    MANDATORY_ARG,
                    mandatory,
                    SKIP_VALIDATIONS_ARG,
                    skipValidations,
                    PROVIDERS_ARG,
                    providers,
                    FOR_DEPENDENCY_RESOLUTION_ARG,
                    forDependencyResolution,
                    ALLOW_RULES_ARG,
                    allowRules,
                    CONFIGURATION_ARG,
                    cfg,
                    ASPECTS_ARG,
                    aspects,
                    FLAGS_ARG,
                    flags
                ),
                thread,
                "label"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor("label", attribute)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun stringListAttribute(
        mandatory: Boolean?,
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,
        doc: Any?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.string_list()")
        return createAttrDescriptor(
            "string_list",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty
            ),
            Types.STRING_LIST,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun intListAttribute(
        mandatory: Boolean?,
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: net.starlark.java.eval.Sequence<*>?,
        doc: Any?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.int_list()")
        return createAttrDescriptor(
            "int_list",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty
            ),
            Types.INTEGER_LIST,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun labelListAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,  // Sequence | StarlarkFunction
        materializer: Any?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.label_list()")
        val kwargs =
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MATERIALIZER_ARG,
                materializer,
                ALLOW_FILES_ARG,
                allowFiles,
                ALLOW_RULES_ARG,
                allowRules,
                PROVIDERS_ARG,
                providers,
                FOR_DEPENDENCY_RESOLUTION_ARG,
                forDependencyResolution,
                FLAGS_ARG,
                flags,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty,
                CONFIGURATION_ARG,
                cfg,
                ASPECTS_ARG,
                aspects,
                SKIP_VALIDATIONS_ARG,
                skipValidations
            )
        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.LABEL_LIST,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                kwargs,
                thread,
                "label_list"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor("label_list", attribute)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun dormantLabelAttribute(
        defaultValue: Any?, doc: Any?, mandatory: Boolean?, thread: StarlarkThread
    ): StarlarkAttrModuleApi.Descriptor {
        checkContext(thread, "attr.dormant_label()")

        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.DORMANT_LABEL,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory),
                thread,
                "dormant_label"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor("dormant_label", attribute)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun dormantLabelListAttribute(
        allowEmpty: Boolean?, defaultValue: Any?, doc: Any?, mandatory: Boolean?, thread: StarlarkThread
    ): StarlarkAttrModuleApi.Descriptor {
        checkContext(thread, "attr.dormant_label_list()")
        val kwargs =
            optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory, ALLOW_EMPTY_ARG, allowEmpty)
        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.DORMANT_LABEL_LIST,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                kwargs,
                thread,
                "dormant_label_list"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
            "dormant_label_list",
            attribute
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun stringKeyedLabelDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,  // Dict | StarlarkFunction
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.string_keyed_label_dict()")
        val kwargs =
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                ALLOW_FILES_ARG,
                allowFiles,
                ALLOW_RULES_ARG,
                allowRules,
                PROVIDERS_ARG,
                providers,
                FOR_DEPENDENCY_RESOLUTION_ARG,
                forDependencyResolution,
                FLAGS_ARG,
                flags,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty,
                CONFIGURATION_ARG,
                cfg,
                ASPECTS_ARG,
                aspects
            )
        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.LABEL_DICT_UNARY,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                kwargs,
                thread,
                "string_keyed_label_dict"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
            "string_keyed_label_dict",
            attribute
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun labelKeyedStringDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Any?,  // Dict | StarlarkFunction
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.label_keyed_string_dict()")
        val kwargs =
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                ALLOW_FILES_ARG,
                allowFiles,
                ALLOW_RULES_ARG,
                allowRules,
                PROVIDERS_ARG,
                providers,
                FOR_DEPENDENCY_RESOLUTION_ARG,
                forDependencyResolution,
                FLAGS_ARG,
                flags,
                MANDATORY_ARG,
                mandatory,
                SKIP_VALIDATIONS_ARG,
                skipValidations,
                ALLOW_EMPTY_ARG,
                allowEmpty,
                CONFIGURATION_ARG,
                cfg,
                ASPECTS_ARG,
                aspects
            )
        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.LABEL_KEYED_STRING_DICT,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                kwargs,
                thread,
                "label_keyed_string_dict"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
            "label_keyed_string_dict",
            attribute
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun labelListDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Dict<*, *>?,
        doc: Any?,
        allowFiles: Any?,
        allowRules: Any?,
        providers: net.starlark.java.eval.Sequence<*>?,
        forDependencyResolution: Any?,
        flags: net.starlark.java.eval.Sequence<*>?,
        mandatory: Boolean?,
        skipValidations: Boolean?,
        cfg: Any?,
        aspects: net.starlark.java.eval.Sequence<*>?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.label_list_dict()")
        val kwargs =
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                ALLOW_FILES_ARG,
                allowFiles,
                ALLOW_RULES_ARG,
                allowRules,
                PROVIDERS_ARG,
                providers,
                FOR_DEPENDENCY_RESOLUTION_ARG,
                forDependencyResolution,
                FLAGS_ARG,
                flags,
                MANDATORY_ARG,
                mandatory,
                SKIP_VALIDATIONS_ARG,
                skipValidations,
                ALLOW_EMPTY_ARG,
                allowEmpty,
                CONFIGURATION_ARG,
                cfg,
                ASPECTS_ARG,
                aspects
            )
        val attribute: ImmutableAttributeFactory =
            createAttributeFactory(
                BuildType.LABEL_LIST_DICT,
                Starlark.toJavaOptional<String?>(doc, String::class.java),
                kwargs,
                thread,
                "label_list_dict"
            )
        return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
            "label_list_dict",
            attribute
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun boolAttribute(
        configurable: Any?,
        defaultValue: Boolean?,
        doc: Any?,
        mandatory: Boolean?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.bool()")
        return createAttrDescriptor(
            "bool",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG, configurable, DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory
            ),
            Type.BOOLEAN,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun outputAttribute(doc: Any?, mandatory: Boolean?, thread: StarlarkThread): Descriptor {
        checkContext(thread, "attr.output()")

        return createNonconfigurableAttrDescriptor(
            "output",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(MANDATORY_ARG, mandatory),
            BuildType.OUTPUT,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun outputListAttribute(
        allowEmpty: Boolean?, doc: Any?, mandatory: Boolean?, thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.output_list()")
        // The resulting Attribute does not have the nonconfigurable bit set, but is still
        // nonconfigurable in practice because Attribute#isConfigurable specifically checks
        // whether the attribute has LabelClass.OUTPUT.
        // TODO(b/337841229): Consider calling createNonconfigurableAttrDescriptor()
        // here, for symmetry with outputAttribute() above.
        return createAttrDescriptor(
            "output_list",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(MANDATORY_ARG, mandatory, ALLOW_EMPTY_ARG, allowEmpty),
            BuildType.OUTPUT_LIST,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun stringDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Dict<*, *>?,
        doc: Any?,
        mandatory: Boolean?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.string_dict()")
        return createAttrDescriptor(
            "string_dict",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty
            ),
            Types.STRING_DICT,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun stringListDictAttribute(
        allowEmpty: Boolean?,
        configurable: Any?,
        defaultValue: Dict<*, *>?,
        doc: Any?,
        mandatory: Boolean?,
        thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.string_list_dict()")
        return createAttrDescriptor(
            "string_list_dict",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(
                CONFIGURABLE_ARG,
                configurable,
                DEFAULT_ARG,
                defaultValue,
                MANDATORY_ARG,
                mandatory,
                ALLOW_EMPTY_ARG,
                allowEmpty
            ),
            Types.STRING_LIST_DICT,
            thread
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun licenseAttribute(
        defaultValue: Any?, doc: Any?, mandatory: Boolean?, thread: StarlarkThread
    ): Descriptor {
        checkContext(thread, "attr.license()")
        return createNonconfigurableAttrDescriptor(
            "license",
            Starlark.toJavaOptional<String?>(doc, String::class.java),
            optionMap(DEFAULT_ARG, defaultValue, MANDATORY_ARG, mandatory),
            BuildType.LICENSE,
            thread
        )
    }

    /** A descriptor of an attribute defined in Starlark.  */
    class Descriptor private constructor(private val name: String?, attributeFactory: ImmutableAttributeFactory?) :
        StarlarkAttrModuleApi.Descriptor {
        private val attributeFactory: ImmutableAttributeFactory

        init {
            this.attributeFactory =
                com.google.common.base.Preconditions.checkNotNull<ImmutableAttributeFactory>(attributeFactory)
        }

        fun hasDefault(): Boolean {
            return attributeFactory.isValueSet()
        }

        fun getValueSource(): AttributeValueSource {
            return attributeFactory.getValueSource()
        }

        fun getType(): Type<*> {
            return attributeFactory.getType()
        }

        fun build(name: String?): Attribute {
            return attributeFactory.build(name)
        }

        public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
            printer.append("<attr." + name + ">")
        }

        // Value equality semantics - same as for native Attribute.
        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Descriptor) {
                return false
            }
            return name == o.name
                    && attributeFactory == o.attributeFactory
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(name, attributeFactory)
        }

        fun getTransitionFactory(): TransitionFactory<AttributeTransitionData?> {
            return attributeFactory.getTransitionFactory()
        }
    }

    companion object {
        // Arguments
        // TODO(adonovan): opt: this class does a lot of redundant hashtable lookups.
        /**
         * Throws [EvalException] if we're not in a Starlark evaluation context suitable for
         * creating attribute descriptors.
         * 
         * 
         * Currently, we restrict attribute descriptor creation to the same environments as the ones in
         * which rule classes may be defined. Namely, these are threads that do 1) .bzl initialization,
         * and 2) BUILD evaluation. The latter is only needed for `analysis_test`.
         * 
         * 
         * In principle, we could probably relax this to be any Starlark environment where the caller's
         * innermost stack frame is a .bzl file. But there seems to be no use case for this.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkContext(thread: StarlarkThread, what: String?) {
            if (thread.getThreadLocal<RuleDefinitionEnvironment?>(RuleDefinitionEnvironment::class.java) != null) {
                // BUILD initialization.
                return
            }
            try {
                BzlInitThreadContext.fromOrFail(thread,  /* what= */"<UNUSED>")
            } catch (unused: net.starlark.java.eval.EvalException) {
                throw Starlark.errorf(
                    "%s can only be used during .bzl initialization (top-level evaluation) or package"
                            + " evaluation (a BUILD file or macro)",
                    what
                )
            }
        }

        private fun containsNonNoneKey(arguments: MutableMap<String?, Any?>, key: String?): Boolean {
            return arguments.containsKey(key) && arguments.get(key) !== Starlark.NONE
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun setAllowedFileTypes(
            attr: String?, fileTypesObj: Any?, builder: Attribute.Builder<*>
        ) {
            if (fileTypesObj === java.lang.Boolean.TRUE) {
                builder.allowedFileTypes(FileTypeSet.ANY_FILE)
            } else if (fileTypesObj === java.lang.Boolean.FALSE) {
                builder.allowedFileTypes(FileTypeSet.NO_FILE)
            } else if (fileTypesObj is net.starlark.java.eval.Sequence) {
                val arg: com.google.common.collect.ImmutableList<String?> =
                    com.google.common.collect.ImmutableList.copyOf<String?>(
                        net.starlark.java.eval.Sequence.cast<String?>(
                            fileTypesObj,
                            String::class.java,
                            "allow_files argument"
                        )
                    )
                builder.allowedFileTypes(FileType.of(arg))
            } else {
                throw Starlark.errorf("%s should be a boolean or a string list", attr)
            }
        }

        // TODO(brandjon): Our treatment of attribute names is very confusing.
        //
        //   - The `name` in the Descriptor is an attribute type, e.g. "attr.label_list", but the `name`
        //     in an Attribute.Builder or ImmutableAttributeFactory is the actual attribute name, e.g.
        //     "srcs". These should be better distinguished in the variable identifier.
        //
        //   - The practice of using an empty string for the attr name in createAttributeFactory and
        //     createNonconfigurableAttrDescriptor is a code smell and is confusing. (The comment also
        //     gives insufficient context.) It looks like the name is unimportant because we use
        //     Attribute.Builder#buildPartial, which ignores the name. But it's unclear whether the name
        //     is still used in the Builder for error messages in Precondition checks. If it truly is
        //     unused then we should make it @Nullable (and do checkNotNull() in the regular non-partial
        //     #build() method).
        //
        //   - In createAttributeFactory, we're currently inconsistent about whether we pass in an empty
        //     attribute name (as in the wrapping overload) or the descriptor type (e.g. "label_list" in
        //     labelListAttribute()).
        // TODO(b/236456122): Instead of passing a StarlarkThread around, unwrap its LabelConverter and
        // StarlarkSemantics and pass those directly. Validate that we're in the right Starlark evaluation
        // context (BzlInitThreadContext.fromOrFail()) at the time of the unwrapping.
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createAttributeFactory(
            type: Type<*>,
            doc: java.util.Optional<String?>,
            arguments: MutableMap<String?, Any?>,
            thread: StarlarkThread
        ): ImmutableAttributeFactory {
            // We use an empty name now so that we can set it later.
            // This trick makes sense only in the context of Starlark (builtin rules should not use it).
            return createAttributeFactory(type, doc, arguments, thread, "")
        }

        // TODO(brandjon): Inline into its sole caller, createAttrDescriptor().
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createAttributeFactory(
            type: Type<*>,
            doc: java.util.Optional<String?>,
            arguments: MutableMap<String?, Any?>,
            thread: StarlarkThread,
            name: String?
        ): ImmutableAttributeFactory {
            return createAttribute(type, doc, arguments, thread, name).buildPartial()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createAttribute(
            type: Type<*>,
            doc: java.util.Optional<String?>,
            arguments: MutableMap<String?, Any?>,
            thread: StarlarkThread,
            name: String?
        ): Attribute.Builder<*> {
            val builder: Attribute.Builder<*> = Attribute.attr(name, type).starlarkDefined()
            doc.map<String?>(java.util.function.Function { docString: String? -> Starlark.trimDocString(docString) })
                .ifPresent(builder::setDoc)

            val defaultValue = arguments.get(DEFAULT_ARG)
            val materializer = arguments.get(MATERIALIZER_ARG)
            val isMandatory =
                containsNonNoneKey(arguments, MANDATORY_ARG) && arguments.get(MANDATORY_ARG) as Boolean?
            val configurableParamSet =
                containsNonNoneKey(arguments, CONFIGURABLE_ARG)
                        && arguments.get(CONFIGURABLE_ARG) !== Starlark.UNBOUND

            if (!Starlark.isNullOrNone(materializer)) {
                if (materializer !is StarlarkFunction) {
                    throw Starlark.errorf(
                        "Expected a function in 'materializer' parameter, got '%s'",
                        Starlark.type(materializer)
                    )
                }

                // defaultValue.equals(type.getDefaultValue()) doesn't work because defaultValue is
                // a StarlarkImmutableList whose equality checks if the other object is also a
                // StarlarkImmutableList. Using Objects.equal() would be brittle because that would rely on
                // it doing the equality check the right way.
                if ((type.getDefaultValue() == null && defaultValue != null)
                    || (type.getDefaultValue() != null && !type.getDefaultValue().equals(defaultValue))
                ) {
                    throw Starlark.errorf("The 'materializer' and 'default' parameters are incompatible")
                }

                if (isMandatory) {
                    throw Starlark.errorf("The 'materializer' and 'mandatory' parameters are incompatible")
                }

                if (configurableParamSet) {
                    throw Starlark.errorf("The 'materializer' and 'configurable' parameters are incompatible")
                }

                // This method doesn't have a type parameter so we can't supply one to
                // MaterializingDefault, either.
                val starlarkMaterializer: StarlarkMaterializer<*> =
                    StarlarkMaterializer<Any?>(type, thread.getSemantics(), materializer as StarlarkFunction)
                builder.value(
                    MaterializingDefault(
                        type,
                        com.google.common.collect.ImmutableMap::class.java,
                        starlarkMaterializer
                    )
                )
            } else if (!Starlark.isNullOrNone(defaultValue)) {
                if (defaultValue is StarlarkFunction) {
                    // Computed attribute. Non label type attributes already caused a type check error.
                    val callback: StarlarkCallbackHelper =
                        StarlarkCallbackHelper(defaultValue as StarlarkFunction, thread.getSemantics())
                    // StarlarkComputedDefaultTemplate needs to know the names of all attributes that it depends
                    // on. However, this method does not know anything about other attributes.
                    // We solve this problem by asking the StarlarkCallbackHelper for the parameter names used
                    // in the function definition, which must be the names of attributes used by the callback.
                    builder.value(
                        StarlarkComputedDefaultTemplate(type, callback.getParameterNames(), callback)
                    )
                } else if (defaultValue is StarlarkLateBoundDefault) {
                    builder.value(defaultValue as StarlarkLateBoundDefault?) // unchecked cast
                } else if (defaultValue is NativeComputedDefaultApi) {
                    // TODO(b/200065655#comment3): This hack exists until default_copts and default_hdrs_check
                    //  in package() is replaced by proper package defaults. We don't check the particular
                    //  instance to avoid adding a dependency to the C++ package.
                    builder.value(defaultValue as NativeComputedDefaultApi?)
                } else {
                    builder.defaultValue(
                        defaultValue, LabelConverter.forBzlEvaluatingThread(thread), DEFAULT_ARG
                    )
                }
            }

            val flagsArg = arguments.get(FLAGS_ARG)
            if (flagsArg != null) {
                for (flag in net.starlark.java.eval.Sequence.noneableCast<String?>(
                    flagsArg,
                    String::class.java,
                    FLAGS_ARG
                )) {
                    builder.setPropertyFlag(flag)
                }
            }

            if (isMandatory) {
                builder.setPropertyFlag("MANDATORY")
            }

            if (arguments.containsKey(FOR_DEPENDENCY_RESOLUTION_ARG)
                && arguments.get(FOR_DEPENDENCY_RESOLUTION_ARG) !== Starlark.UNBOUND
            ) {
                builder.setPropertyFlag("FOR_DEPENDENCY_RESOLUTION_EXPLICITLY_SET")
                if (arguments.get(FOR_DEPENDENCY_RESOLUTION_ARG) === java.lang.Boolean.TRUE) {
                    builder.setPropertyFlag("FOR_DEPENDENCY_RESOLUTION")
                } else {
                    builder.removePropertyFlag("FOR_DEPENDENCY_RESOLUTION")
                }
            }

            if (configurableParamSet) {
                builder.configurableAttrWasUserSet()
                if (!(arguments.get(CONFIGURABLE_ARG) as Boolean?)!!) {
                    // output, output_list, and license type attributes don't support the configurable= arg,
                    // so no need to worry about calling nonconfigurable() twice.
                    builder.nonconfigurable("This attribute was marked as nonconfigurable")
                }
            }

            if (containsNonNoneKey(arguments, SKIP_VALIDATIONS_ARG)
                && arguments.get(SKIP_VALIDATIONS_ARG) as Boolean?
            ) {
                builder.setPropertyFlag("SKIP_VALIDATIONS")
            }

            if (containsNonNoneKey(arguments, ALLOW_EMPTY_ARG)
                && !arguments.get(ALLOW_EMPTY_ARG) as Boolean?
            ) {
                builder.setPropertyFlag("NON_EMPTY")
            }

            if (containsNonNoneKey(arguments, EXECUTABLE_ARG) && arguments.get(EXECUTABLE_ARG) as Boolean?) {
                builder.setPropertyFlag("EXECUTABLE")
                if (!containsNonNoneKey(arguments, CONFIGURATION_ARG)) {
                    throw Starlark.errorf(
                        ("cfg parameter is mandatory when executable=True is provided. Please see "
                                + "https://bazel.build/extending/rules#configurations "
                                + "for more details.")
                    )
                }
            }

            if (containsNonNoneKey(arguments, ALLOW_FILES_ARG)
                && containsNonNoneKey(arguments, ALLOW_SINGLE_FILE_ARG)
            ) {
                throw Starlark.errorf("Cannot specify both allow_files and allow_single_file")
            }

            if (containsNonNoneKey(arguments, ALLOW_FILES_ARG)) {
                val fileTypesObj = arguments.get(ALLOW_FILES_ARG)
                setAllowedFileTypes(ALLOW_FILES_ARG, fileTypesObj, builder)
            } else if (containsNonNoneKey(arguments, ALLOW_SINGLE_FILE_ARG)) {
                val fileTypesObj = arguments.get(ALLOW_SINGLE_FILE_ARG)
                setAllowedFileTypes(ALLOW_SINGLE_FILE_ARG, fileTypesObj, builder)
                builder.setPropertyFlag("SINGLE_ARTIFACT")
            } else if (type.getLabelClass() === LabelClass.DEPENDENCY) {
                builder.allowedFileTypes(FileTypeSet.NO_FILE)
            }

            val ruleClassesObj = arguments.get(ALLOW_RULES_ARG)
            if (ruleClassesObj != null && ruleClassesObj !== Starlark.NONE) {
                builder.allowedRuleClasses(
                    net.starlark.java.eval.Sequence.cast<T?>(
                        ruleClassesObj, String::class.java, "allowed rule classes for attribute definition"
                    )
                )
            }

            val valuesArg = arguments.get(VALUES_ARG)
            if (valuesArg != null) {
                val values: MutableList<Any?> =
                    net.starlark.java.eval.Sequence.noneableCast<Any?>(valuesArg, Any::class.java, VALUES_ARG)
                if (!values.isEmpty()) {
                    builder.allowedValues(AllowedValueSet(values))
                }
            }

            if (containsNonNoneKey(arguments, PROVIDERS_ARG)) {
                val obj = arguments.get(PROVIDERS_ARG)
                val providersList: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> =
                    buildProviderPredicate(
                        net.starlark.java.eval.Sequence.cast<Any?>(
                            obj,
                            Any::class.java,
                            PROVIDERS_ARG
                        ), PROVIDERS_ARG
                    )

                // If there is at least one empty set, there is no restriction.
                if (providersList.stream()
                        .noneMatch(java.util.function.Predicate { obj: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>? -> obj.isEmpty() })
                ) {
                    builder.mandatoryProvidersList(providersList)
                }
            }

            if (containsNonNoneKey(arguments, CONFIGURATION_ARG)) {
                val trans = arguments.get(CONFIGURATION_ARG)
                val transitionFactory: TransitionFactory<AttributeTransitionData?> = convertCfg(thread, trans)

                // Check whether something is attempting an invalid late bound transition.
                val isSplit: Boolean = transitionFactory.isSplit()
                if (isSplit && defaultValue is StarlarkLateBoundDefault) {
                    throw Starlark.errorf(
                        "late-bound attributes must not have a split configuration transition"
                    )
                }

                if (isSplit && defaultValue is MaterializingDefault<*, *>) {
                    throw Starlark.errorf(
                        "materializing attributes must not have a split configuration transition"
                    )
                }

                // Check if this transition includes an analysis test or a Starlark transition.
                transitionFactory.visit(
                    { factory ->
                        if (factory is StarlarkAttributeTransitionProvider) {
                            if (factory.getStarlarkDefinedConfigTransitionForTesting().isForAnalysisTesting()) {
                                builder.hasAnalysisTestTransition()
                            } else {
                                builder.hasStarlarkDefinedTransition()
                            }
                        }
                    })

                builder.cfg(transitionFactory)
            }

            if (containsNonNoneKey(arguments, ASPECTS_ARG)) {
                val obj = arguments.get(ASPECTS_ARG)
                for (aspect in net.starlark.java.eval.Sequence.cast<StarlarkAspect?>(
                    obj,
                    StarlarkAspect::class.java,
                    "aspects"
                )) {
                    builder.aspect(aspect)
                }
            }

            return builder
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun convertCfg(
            thread: StarlarkThread, trans: Any?
        ): TransitionFactory<AttributeTransitionData?> {
            // The most common case is no transition.
            if (trans == "target" || trans == Starlark.NONE) {
                return NoTransition.getFactory()
            }
            // TODO(b/203203933): remove after removing --incompatible_disable_starlark_host_transitions.
            if (trans == "host") {
                val disableStarlarkHostTransitions: Boolean =
                    thread
                        .getSemantics()
                        .getBool(BuildLanguageOptions.INCOMPATIBLE_DISABLE_STARLARK_HOST_TRANSITIONS)
                if (disableStarlarkHostTransitions) {
                    throw net.starlark.java.eval.EvalException(
                        "'cfg = \"host\"' is deprecated and should no longer be used. Please use "
                                + "'cfg = \"exec\"' instead."
                    )
                }
                return ExecutionTransitionFactory.Companion.createFactory()
            }
            if (trans == "exec") {
                return ExecutionTransitionFactory.Companion.createFactory()
            }
            if (trans is StarlarkDefinedConfigTransition) {
                return StarlarkAttributeTransitionProvider(trans)
            }
            if (trans is ConfigurationTransitionApi) {
                // Every ConfigurationTransitionApi must be a TransitionFactory instance to be usable.
                if (trans is TransitionFactory<*>) {
                    if (cta.transitionType().isCompatibleWith(TransitionType.ATTRIBUTE)) {
                        val attrTransition: TransitionFactory<AttributeTransitionData?> =
                            cta as TransitionFactory<AttributeTransitionData?>
                        return attrTransition
                    }
                } else {
                    throw java.lang.IllegalStateException(
                        "Every ConfigurationTransitionApi must be a TransitionFactory instance"
                    )
                }
            }

            // We don't actively advertise the hard-coded but exposed transitions like
            // android_split_transition because users of those transitions should already know about
            // them.
            throw Starlark.errorf(
                "cfg must be either 'target', 'exec' or a starlark defined transition defined by the "
                        + "exec() or transition() functions."
            )
        }

        /**
         * Builds a list of sets of accepted providers from Starlark list `obj`. The list can either
         * be a list of providers (in that case the result is a list with one set) or a list of lists of
         * providers (then the result is the list of sets).
         * 
         * @param argumentName used in error messages.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun buildProviderPredicate(
            obj: net.starlark.java.eval.Sequence<*>, argumentName: String?
        ): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> {
            if (obj.isEmpty()) {
                return com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>()
            }
            var isListOfProviders = true
            for (o in obj) {
                if (o !is Provider) {
                    isListOfProviders = false
                    break
                }
            }
            if (isListOfProviders) {
                return com.google.common.collect.ImmutableList.of<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>(
                    getStarlarkProviderIdentifiers(obj, argumentName)
                )
            } else {
                val listOfLists:  // safe
                        net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<*>?> =
                    net.starlark.java.eval.Sequence.cast<net.starlark.java.eval.Sequence?>(
                        obj,
                        net.starlark.java.eval.Sequence::class.java,
                        argumentName
                    ) as net.starlark.java.eval.Sequence
                return getProvidersList(listOfLists, argumentName)
            }
        }

        /** Converts Starlark identifiers of providers to their internal representations.  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun getStarlarkProviderIdentifiers(
            listArg: net.starlark.java.eval.Sequence<*>?, argumentName: String?
        ): com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?> {
            val list: net.starlark.java.eval.Sequence<Provider> =
                net.starlark.java.eval.Sequence.cast<Provider>(listArg, Provider::class.java, argumentName)

            val result: com.google.common.collect.ImmutableList.Builder<StarlarkProviderIdentifier?> =
                com.google.common.collect.ImmutableList.builder<StarlarkProviderIdentifier?>()
            for (constructor in list) {
                if (!constructor.isExported()) {
                    throw Starlark.errorf(
                        "Providers should be top-level values in extension files that define them."
                    )
                }
                result.add(StarlarkProviderIdentifier.forKey(constructor.getKey()))
            }
            return com.google.common.collect.ImmutableSet.copyOf<StarlarkProviderIdentifier?>(result.build())
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getProvidersList(
            starlarkList: net.starlark.java.eval.Sequence<net.starlark.java.eval.Sequence<*>?>, argumentName: String?
        ): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> {
            val providersList: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?> =
                com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>()
            for (sublist in starlarkList) {
                providersList.add(getStarlarkProviderIdentifiers(sublist, argumentName))
            }
            return providersList.build()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createAttrDescriptor(
            name: String?,
            doc: java.util.Optional<String?>,
            kwargs: MutableMap<String?, Any?>,
            type: Type<*>,
            thread: StarlarkThread
        ): Descriptor {
            try {
                return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
                    name,
                    createAttributeFactory(type, doc, kwargs, thread)
                )
            } catch (e: ConversionException) {
                throw net.starlark.java.eval.EvalException(e.getMessage())
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun createNonconfigurableAttrDescriptor(
            name: String?,
            doc: java.util.Optional<String?>,
            kwargs: MutableMap<String?, Any?>,
            type: Type<*>,
            thread: StarlarkThread
        ): Descriptor {
            val whyNotConfigurableReason: String =
                com.google.common.base.Preconditions.checkNotNull(BuildType.maybeGetNonConfigurableReason(type), type)
            try {
                // We use an empty name now so that we can set it later.
                // This trick makes sense only in the context of Starlark (builtin rules should not use it).
                return com.google.devtools.build.lib.analysis.starlark.StarlarkAttrModule.Descriptor(
                    name,
                    createAttribute(type, doc, kwargs, thread, "")
                        .nonconfigurable(whyNotConfigurableReason)
                        .buildPartial()
                )
            } catch (e: ConversionException) {
                throw net.starlark.java.eval.EvalException(e.getMessage())
            }
        }

        // Returns an immutable map from a list of alternating name/value pairs,
        // skipping values that are null or None. Keys must be unique.
        private fun optionMap(vararg pairs: Any?): MutableMap<String?, Any?> {
            com.google.common.base.Preconditions.checkArgument(pairs.size % 2 == 0)
            val b: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
                com.google.common.collect.ImmutableMap.Builder<String?, Any?>()
            var i = 0
            while (i < pairs.size) {
                val key = com.google.common.base.Preconditions.checkNotNull<Any?>(pairs[i]) as String
                val value: Any? = pairs[i + 1]
                if (value != null && value !== Starlark.NONE) {
                    b.put(key, value)
                }
                i += 2
            }
            return b.buildOrThrow()
        }
    }
}
