// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkdocextract

import com.google.devtools.build.lib.analysis.starlark.StarlarkRuleClassFunctions.MacroFunction

/** API documentation extractor for a compiled, loaded Starlark module.  */
class ModuleInfoExtractor(isWantedQualifiedName: java.util.function.Predicate<String?>, labelRenderer: LabelRenderer) {
    private val isWantedQualifiedName: java.util.function.Predicate<String?>
    private val labelRenderer: LabelRenderer
    private var allowUnusedDocComments = false

    /**
     * Constructs an instance of `ModuleInfoExtractor`.
     * 
     * @param isWantedQualifiedName a predicate to filter the module's qualified names. A qualified
     * name is documented if and only if (1) each component of the qualified name is public (in
     * other words, the first character of each component of the qualified name is alphabetic) and
     * (2) the qualified name, or one of its ancestor qualified names, satisfies the wanted
     * predicate.
     * @param labelRenderer a string renderer for labels.
     */
    init {
        this.isWantedQualifiedName = isWantedQualifiedName
        this.labelRenderer = labelRenderer
    }

    /** Allows unused doc comments in modules.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun allowUnusedDocComments(): ModuleInfoExtractor {
        this.allowUnusedDocComments = true
        return this
    }

    /** Extracts structured documentation for the loadable symbols of a given module.  */
    @Throws(ExtractionException::class)
    fun extractFrom(
        module: net.starlark.java.eval.Module,
        docCommentsMap: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.DocComments?>,
        unusedDocCommentLines: com.google.common.collect.ImmutableList<net.starlark.java.syntax.Comment?>
    ): ModuleInfo {
        if (!allowUnusedDocComments && !unusedDocCommentLines.isEmpty()) {
            throw ExtractionException(
                module,
                String.format(
                    "unexpected or conflicting doc comments on line%s %s; a doc comment must be attached"
                            + " to the declaration of a global variable",
                    if (unusedDocCommentLines.size > 1) "s" else "",
                    com.google.common.base.Joiner.on(", ")
                        .join(
                            unusedDocCommentLines.stream()
                                .map<Int?> { c: net.starlark.java.syntax.Comment? -> c.getStartLocation().line() }
                                .iterator())))
        }
        val builder: ModuleInfo.Builder = ModuleInfo.newBuilder()
        java.util.Optional.ofNullable<String?>(module.getDocumentation())
            .map<String?>(java.util.function.Function { s: String? -> StringEncoding.internalToUnicode(s) })
            .ifPresent(builder::setModuleDocstring)
        java.util.Optional.ofNullable<T?>(BazelModuleContext.of(module))
            .map<String?>(
                java.util.function.Function { bazelModuleContext: T? ->
                    StringEncoding.internalToUnicode(
                        labelRenderer.render(
                            bazelModuleContext.label()
                        )
                    )
                })
            .ifPresent(builder::setFile)

        // We do two traversals over the module's globals: (1) find qualified names (including any
        // nesting structs) for providers loadable from this module; (2) build the documentation
        // proto, using the information from traversal 1 for provider names references by rules and
        // attributes.
        val providerQualifiedNameCollector =
            ProviderQualifiedNameCollector(module)
        providerQualifiedNameCollector.traverse()
        val documentationExtractor =
            DocumentationExtractor(
                module,
                docCommentsMap,
                builder,
                isWantedQualifiedName,
                ExtractorContext.Companion.builder()
                    .labelRenderer(labelRenderer)
                    .providerQualifiedNames(providerQualifiedNameCollector.buildQualifiedNames())
                    .build(),
                allowUnusedDocComments
            )
        documentationExtractor.traverse()
        return builder.build()
    }

    @Throws(ExtractionException::class)
    fun extractFrom(module: net.starlark.java.eval.Module, program: net.starlark.java.syntax.Program): ModuleInfo {
        return extractFrom(module, program.getDocCommentsMap(), program.getUnusedDocCommentLines())
    }

    @Throws(ExtractionException::class)
    fun extractFrom(module: net.starlark.java.eval.Module): ModuleInfo {
        val moduleContext: BazelModuleContext =
            checkNotNull(
                BazelModuleContext.of(module), "Module %s does not have a BazelModuleContext", module
            )
        return extractFrom(
            module, moduleContext.docCommentsMap, moduleContext.unusedDocCommentLines
        )
    }

    /**
     * A stateful visitor which traverses a Starlark module's documentable globals, recursing into
     * structs.
     */
    private abstract class GlobalsVisitor {
        @Throws(ExtractionException::class)
        fun traverse() {
            for (entry in this.module.getGlobals().entries) {
                val globalSymbol: String = entry.key
                if (ExtractorContext.Companion.isPublicName(globalSymbol)) {
                    maybeVisit(globalSymbol, entry.value,  /* shouldVisitVerifiedForAncestor= */false)
                }
            }
        }

        abstract val module: net.starlark.java.eval.Module?

        /**
         * Returns whether the visitor should visit (and possibly recurse into) the value with the given
         * qualified name. Note that the visitor will not visit global names and struct fields for which
         * [.isPublicName] is false, regardless of `shouldVisit`.
         */
        abstract fun shouldVisit(qualifiedName: String?): Boolean

        /**
         * @param qualifiedName the name under which the value may be accessed by a user of the module;
         * for example, "foo.bar" for field bar of global struct foo
         * @param value the Starlark value
         * @param shouldVisitVerifiedForAncestor whether [.shouldVisit] was verified true for an
         * ancestor struct's qualified name; e.g. `qualifiedName` is "a.b.c.d" and `shouldVisit("a.b") == true`
         */
        @Throws(ExtractionException::class)
        fun maybeVisit(
            qualifiedName: String?, value: Any?, shouldVisitVerifiedForAncestor: Boolean
        ) {
            if (shouldVisitVerifiedForAncestor || shouldVisit(qualifiedName)) {
                if (value is StarlarkExportable && !value.isExported()) {
                    // Unexported StarlarkExportables are not usable and therefore do not need to have docs
                    // generated.
                    return
                }
                if (value is StarlarkRuleFunction) {
                    visitRule(qualifiedName, value)
                } else if (value is MacroFunction) {
                    visitMacroFunction(qualifiedName, value)
                } else if (value is StarlarkProvider) {
                    visitProvider(qualifiedName, value)
                } else if (value is net.starlark.java.eval.StarlarkFunction) {
                    visitFunction(qualifiedName, value)
                } else if (value is StarlarkDefinedAspect) {
                    visitAspect(qualifiedName, value)
                } else if (value is StarlarkRepoRule) {
                    visitRepositoryRule(qualifiedName, value.getRepoRule())
                } else if (value is ModuleExtension) {
                    visitModuleExtension(qualifiedName, value)
                } else {
                    maybeVisitOtherSymbol(qualifiedName, value)
                    if (value is net.starlark.java.eval.Structure) {
                        recurseIntoStructure(
                            qualifiedName, value,  /* shouldVisitVerifiedForAncestor= */true
                        )
                    }
                }
            } else if (value is net.starlark.java.eval.Structure) {
                recurseIntoStructure(qualifiedName, value,  /* shouldVisitVerifiedForAncestor= */false)
            }
            // TODO(b/276733504): should we recurse into dicts to search for documentable values? Note
            // that dicts (unlike structs!) can have reference cycles, so we would need to track the set
            // of traversed entities.
        }

        @Throws(ExtractionException::class)
        open fun visitRule(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") value: StarlarkRuleFunction?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitMacroFunction(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") value: MacroFunction?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitProvider(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") value: StarlarkProvider?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitFunction(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") value: net.starlark.java.eval.StarlarkFunction?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitAspect(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") aspect: StarlarkDefinedAspect?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitModuleExtension(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") moduleExtension: ModuleExtension?
        ) {
        }

        @Throws(ExtractionException::class)
        open fun visitRepositoryRule(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") repoRule: RepoRule?
        ) {
        }

        open fun maybeVisitOtherSymbol(
            @Suppress("unused") qualifiedName: String?,
            @Suppress("unused") value: Any?
        ) {
        }

        @Throws(ExtractionException::class)
        fun recurseIntoStructure(
            qualifiedName: String?, structure: net.starlark.java.eval.Structure, shouldVisitVerifiedForAncestor: Boolean
        ) {
            for (fieldName in structure.getFieldNames()) {
                if (ExtractorContext.Companion.isPublicName(fieldName)) {
                    try {
                        val fieldValue: Any? = structure.getValue(fieldName)
                        if (fieldValue != null) {
                            maybeVisit(
                                String.format("%s.%s", qualifiedName, fieldName),
                                fieldValue,
                                shouldVisitVerifiedForAncestor
                            )
                        }
                    } catch (e: net.starlark.java.eval.EvalException) {
                        throw ExtractionException(
                            this.module,
                            String.format(
                                "in struct %s field %s: failed to read value", qualifiedName, fieldName
                            ),
                            e
                        )
                    }
                }
            }
        }
    }

    /**
     * A [GlobalsVisitor] which finds the qualified names (including any nesting structs) for
     * providers loadable from this module.
     */
    private class ProviderQualifiedNameCollector(module: net.starlark.java.eval.Module?) : GlobalsVisitor() {
        private val module: net.starlark.java.eval.Module?
        private val qualifiedNames: LinkedHashMap<StarlarkProvider.Key?, String?> =
            LinkedHashMap<StarlarkProvider.Key?, String?>()

        init {
            this.module = module
        }

        override fun getModule(): net.starlark.java.eval.Module? {
            return module
        }

        /**
         * Builds a map from the keys of the Starlark providers which were walked via [.traverse]
         * to the qualified names (including any structs) under which those providers may be accessed by
         * a user of this module.
         * 
         * 
         * If the same provider is accessible under multiple names, the first documentable name wins.
         */
        fun buildQualifiedNames(): com.google.common.collect.ImmutableMap<StarlarkProvider.Key?, String?> {
            return com.google.common.collect.ImmutableMap.copyOf<StarlarkProvider.Key?, String?>(qualifiedNames)
        }

        /**
         * Returns true always.
         * 
         * 
         * [ProviderQualifiedNameCollector] traverses all loadable providers, not filtering by
         * ModuleInfoExtractor#isWantedQualifiedName, because a non-wanted provider symbol may still be
         * referred to by a wanted rule; we do not want the provider names emitted in rule documentation
         * to vary when we change the isWantedQualifiedName filter.
         */
        override fun shouldVisit(qualifiedName: String?): Boolean {
            return true
        }

        override fun visitProvider(qualifiedName: String?, value: StarlarkProvider) {
            qualifiedNames.putIfAbsent(value.getKey(), qualifiedName)
        }
    }

    /** A [GlobalsVisitor] which extracts documentation for symbols in this module.  */
    private class DocumentationExtractor(
        module: net.starlark.java.eval.Module?,
        docCommentsMap: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.DocComments?>,
        moduleInfoBuilder: ModuleInfo.Builder,
        isWantedQualifiedName: java.util.function.Predicate<String?>,
        context: ExtractorContext,
        allowUnusedDocComments: Boolean
    ) : GlobalsVisitor() {
        private val module: net.starlark.java.eval.Module?
        private val docCommentsMap: com.google.common.collect.ImmutableMap<String?, net.starlark.java.syntax.DocComments?>
        private val moduleInfoBuilder: ModuleInfo.Builder
        private val isWantedQualifiedName: java.util.function.Predicate<String?>
        private val context: ExtractorContext
        private val allowUnusedDocComments: Boolean

        /**
         * @param moduleInfoBuilder builder to which [.traverse] adds extracted documentation
         * @param isWantedQualifiedName a predicate to filter the module's qualified names. A qualified
         * name is documented if and only if (1) each component of the qualified name is public (in
         * other words, the first character of each component of the qualified name is alphabetic)
         * and (2) the qualified name, or one of its ancestor qualified names, satisfies the wanted
         * predicate.
         * @param labelRenderer a function for stringifying labels
         * @param providerQualifiedNames a map from the keys of documentable Starlark providers loadable
         * from this module to the qualified names (including structure namespaces) under which
         * those providers are accessible to a user of this module
         */
        init {
            this.module = module
            this.docCommentsMap = docCommentsMap
            this.moduleInfoBuilder = moduleInfoBuilder
            this.isWantedQualifiedName = isWantedQualifiedName
            this.context = context
            this.allowUnusedDocComments = allowUnusedDocComments
        }

        override fun getModule(): net.starlark.java.eval.Module? {
            return module
        }

        override fun shouldVisit(qualifiedName: String?): Boolean {
            return isWantedQualifiedName.test(qualifiedName)
        }

        @Throws(ExtractionException::class)
        fun checkNoDocComments(qualifiedName: String?, what: String?, expected: String?) {
            val docComments: net.starlark.java.syntax.DocComments? = docCommentsMap.get(qualifiedName)
            if (docComments != null && !allowUnusedDocComments) {
                throw ExtractionException(
                    module,
                    String.format(
                        "unexpected doc comment for %s on line %s; API documentation for a %s must be"
                                + " provided in %s",
                        qualifiedName, docComments.getStartLocation().line(), what, expected
                    )
                )
            }
        }

        @Throws(ExtractionException::class)
        override fun visitFunction(qualifiedName: String?, function: net.starlark.java.eval.StarlarkFunction?) {
            checkNoDocComments(qualifiedName, "function", "a docstring at the top of the function body")
            moduleInfoBuilder.addFuncInfo(
                StarlarkFunctionInfoExtractor.Companion.fromNameAndFunction(
                    qualifiedName, function, context.labelRenderer
                )
            )
        }

        @Throws(ExtractionException::class)
        override fun visitRule(qualifiedName: String?, ruleFunction: StarlarkRuleFunction) {
            checkNoDocComments(qualifiedName, "rule", "the doc argument to rule()")
            moduleInfoBuilder.addRuleInfo(
                RuleInfoExtractor.buildRuleInfo(context, qualifiedName, ruleFunction.getRuleClass())
            )
        }

        @Throws(ExtractionException::class)
        override fun visitMacroFunction(qualifiedName: String?, macroFunction: MacroFunction) {
            checkNoDocComments(qualifiedName, "macro", "the doc argument to macro()")
            val macroInfoBuilder: MacroInfo.Builder = MacroInfo.newBuilder()
            // Record the name under which this symbol is made accessible, which may differ from the
            // symbol's exported name
            macroInfoBuilder.setMacroName(StringEncoding.internalToUnicode(qualifiedName))
            // ... but record the origin rule key for cross references.
            macroInfoBuilder.setOriginKey(
                OriginKey.newBuilder()
                    .setName(StringEncoding.internalToUnicode(macroFunction.getName()))
                    .setFile(
                        StringEncoding.internalToUnicode(
                            context.labelRenderer.render(macroFunction.getExtensionLabel())
                        )
                    )
            )
            macroFunction
                .getDocumentation()
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(macroInfoBuilder::setDocString)

            val macroClass: MacroClass = macroFunction.getMacroClass()
            if (macroClass.isFinalizer) {
                macroInfoBuilder.setFinalizer(true)
            }
            // For symbolic macros, always extract non-Starlark attributes (to support inherit_attrs).
            val contextForImplicitMacroAttributes: ExtractorContext? =
                if (context.extractNativelyDefinedAttrs)
                    context
                else
                    context.toBuilder().extractNativelyDefinedAttrs(true).build()
            AttributeInfoExtractor.addDocumentableAttributes(
                contextForImplicitMacroAttributes,
                IMPLICIT_MACRO_ATTRIBUTES,
                macroClass.getAttributeProvider().getAttributes(),
                macroInfoBuilder::addAttribute
            )

            moduleInfoBuilder.addMacroInfo(macroInfoBuilder)
        }

        @Throws(ExtractionException::class)
        override fun visitProvider(qualifiedName: String?, provider: StarlarkProvider) {
            checkNoDocComments(qualifiedName, "provider", "the doc argument to provider()")
            val providerInfoBuilder: ProviderInfo.Builder = ProviderInfo.newBuilder()
            // Record the name under which this symbol is made accessible, which may differ from the
            // symbol's exported name.
            // Note that it's possible that qualifiedName != getDocumentedProviderName() if the same
            // provider symbol is made accessible under more than one qualified name.
            // TODO(b/276733504): if a provider (or any other documentable entity) is made accessible
            // under two different public qualified names, record them in a repeated field inside a single
            // ProviderInfo (or other ${FOO}Info for documentable entity ${FOO}) message, instead of
            // producing a separate ${FOO}Info message for each alias. That requires adding an "alias"
            // field to ${FOO}Info messages (making the existing "${FOO}_name" field repeated would break
            // existing Stardoc templates). Note that for backwards compatibility,
            // ProviderNameGroup.provider_name would still need to refer to only the first qualified name
            // under which a given provider is made accessible by the module.
            providerInfoBuilder.setProviderName(StringEncoding.internalToUnicode(qualifiedName))
            // Record the origin provider key for cross references.
            providerInfoBuilder.setOriginKey(
                OriginKey.newBuilder()
                    .setName(StringEncoding.internalToUnicode(provider.getName()))
                    .setFile(
                        StringEncoding.internalToUnicode(
                            context.labelRenderer.render(provider.getKey().getExtensionLabel())
                        )
                    )
            )
            provider
                .getDocumentation()
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(providerInfoBuilder::setDocString)
            val schema: com.google.common.collect.ImmutableMap<String?, java.util.Optional<String?>?>? =
                provider.getSchema()
            if (schema != null) {
                for (entry in schema.entries) {
                    if (ExtractorContext.Companion.isPublicName(entry.key)) {
                        val fieldInfoBuilder: ProviderFieldInfo.Builder = ProviderFieldInfo.newBuilder()
                        fieldInfoBuilder.setName(StringEncoding.internalToUnicode(entry.key))
                        entry
                            .value
                            .map<String?>(java.util.function.Function { s: String? -> StringEncoding.internalToUnicode(s) })
                            .ifPresent(fieldInfoBuilder::setDocString)
                        providerInfoBuilder.addFieldInfo(fieldInfoBuilder.build())
                    }
                }
            }
            // TODO(b/276733504): if init is a dict-returning native method (e.g. `dict`), do we document
            // it? (This is very unlikely to be useful at present, and would require parsing annotations
            // on the native method.)
            if (provider.getInit() is net.starlark.java.eval.StarlarkFunction) {
                providerInfoBuilder.setInit(
                    StarlarkFunctionInfoExtractor.Companion.fromNameAndFunction(
                        qualifiedName,
                        provider.getInit() as net.starlark.java.eval.StarlarkFunction?,
                        context.labelRenderer
                    )
                )
            }

            moduleInfoBuilder.addProviderInfo(providerInfoBuilder)
        }

        @Throws(ExtractionException::class)
        override fun visitAspect(qualifiedName: String?, aspect: StarlarkDefinedAspect) {
            checkNoDocComments(qualifiedName, "aspect", "the doc argument to aspect()")
            val aspectInfoBuilder: AspectInfo.Builder = AspectInfo.newBuilder()
            // Record the name under which this symbol is made accessible, which may differ from the
            // symbol's exported name
            aspectInfoBuilder.setAspectName(StringEncoding.internalToUnicode(qualifiedName))
            // ... but record the origin aspect key for cross references.
            aspectInfoBuilder.setOriginKey(
                OriginKey.newBuilder()
                    .setName(StringEncoding.internalToUnicode(aspect.getAspectClass().exportedName))
                    .setFile(
                        StringEncoding.internalToUnicode(
                            context
                                .labelRenderer
                                .render(aspect.getAspectClass().getExtensionLabel())
                        )
                    )
            )
            aspect
                .getDocumentation()
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(aspectInfoBuilder::setDocString)
            when (aspect.getAttributeAspects()) {
                -> for (aspectAttribute in s.getList()) {
                    if (ExtractorContext.Companion.isPublicName(aspectAttribute)) {
                        aspectInfoBuilder.addAspectAttribute(aspectAttribute)
                    }
                }

                -> {}
            }

            AttributeInfoExtractor.addDocumentableAttributes(
                context,
                com.google.common.collect.ImmutableMap.of<String?, AttributeInfo?>(),
                aspect.getAttributes(),
                aspectInfoBuilder::addAttribute
            )
            moduleInfoBuilder.addAspectInfo(aspectInfoBuilder)
        }

        @Throws(ExtractionException::class)
        override fun visitModuleExtension(qualifiedName: String?, moduleExtension: ModuleExtension) {
            checkNoDocComments(
                qualifiedName, "module extension", "the doc argument to module_extension()"
            )
            val moduleExtensionInfoBuilder: ModuleExtensionInfo.Builder = ModuleExtensionInfo.newBuilder()
            moduleExtensionInfoBuilder.setExtensionName(StringEncoding.internalToUnicode(qualifiedName))
            moduleExtensionInfoBuilder.setOriginKey(
                OriginKey.newBuilder() // TODO(arostovtsev): attempt to retrieve the name under which the module was
                    // originally defined so we can call setName() too. The easiest solution might be to
                    // make ModuleExtension a StarlarkExportable (partially reverting cl/513213080).
                    // Alternatively, we'd need to search the defining module's globals, similarly to what
                    // we do in FunctionUtil#getFunctionOriginKey.
                    .setFile(
                        StringEncoding.internalToUnicode(
                            context.labelRenderer.render(moduleExtension.definingBzlFileLabel())
                        )
                    )
            )
            moduleExtension
                .doc()
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(moduleExtensionInfoBuilder::setDocString)
            for (entry in moduleExtension.tagClasses().entrySet()) {
                val tagClassInfoBuilder: ModuleExtensionTagClassInfo.Builder =
                    ModuleExtensionTagClassInfo.newBuilder()
                tagClassInfoBuilder.setTagName(StringEncoding.internalToUnicode(entry.getKey()))
                entry
                    .getValue()
                    .doc()
                    .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                    .ifPresent(tagClassInfoBuilder::setDocString)
                AttributeInfoExtractor.addDocumentableAttributes(
                    context,
                    com.google.common.collect.ImmutableMap.of<String?, AttributeInfo?>(),
                    entry.getValue().attributes(),
                    tagClassInfoBuilder::addAttribute
                )
                moduleExtensionInfoBuilder.addTagClass(tagClassInfoBuilder)
            }
            moduleInfoBuilder.addModuleExtensionInfo(moduleExtensionInfoBuilder)
        }

        @Throws(ExtractionException::class)
        protected override fun visitRepositoryRule(qualifiedName: String?, repoRule: RepoRule) {
            checkNoDocComments(qualifiedName, "repository rule", "the doc argument to repository_rule()")
            val repositoryRuleInfoBuilder: RepositoryRuleInfo.Builder = RepositoryRuleInfo.newBuilder()
            repositoryRuleInfoBuilder.setRuleName(StringEncoding.internalToUnicode(qualifiedName))
            repoRule
                .doc()
                .map({ s: String? -> StringEncoding.internalToUnicode(s) })
                .ifPresent(repositoryRuleInfoBuilder::setDocString)
            repositoryRuleInfoBuilder.setOriginKey(
                OriginKey.newBuilder()
                    .setName(StringEncoding.internalToUnicode(repoRule.id().ruleName()))
                    .setFile(
                        StringEncoding.internalToUnicode(context.labelRenderer.render(repoRule.id().bzlFileLabel()))
                    )
            )
            AttributeInfoExtractor.addDocumentableAttributes(
                context,
                IMPLICIT_REPOSITORY_RULE_ATTRIBUTES,
                repoRule.attributes(),
                repositoryRuleInfoBuilder::addAttribute
            )
            for (env in repoRule.environ()) {
                repositoryRuleInfoBuilder.addEnviron(StringEncoding.internalToUnicode(env))
            }
            moduleInfoBuilder.addRepositoryRuleInfo(repositoryRuleInfoBuilder)
        }

        override fun maybeVisitOtherSymbol(qualifiedName: String?, value: Any) {
            val docComments: net.starlark.java.syntax.DocComments? = docCommentsMap.get(qualifiedName)
            if (docComments == null) {
                // Don't emit documentation for symbols without doc comments.
                return
            }
            moduleInfoBuilder.addStarlarkOtherSymbolInfo(
                StarlarkOtherSymbolInfo.newBuilder()
                    .setName(StringEncoding.internalToUnicode(qualifiedName))
                    .setDoc(StringEncoding.internalToUnicode(docComments.getText()))
                    .setTypeName(net.starlark.java.eval.Starlark.type(value))
            )
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val IMPLICIT_MACRO_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, AttributeInfo?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "name",
                AttributeInfo.newBuilder()
                    .setName("name")
                    .setType(AttributeType.NAME)
                    .setMandatory(true)
                    .setDocString(
                        ("A unique name for this macro instance. Normally, this is also the name for the"
                                + " macro's main or only target. The names of any other targets that this"
                                + " macro might create will be this name with a string suffix.")
                    )
                    .build(),
                "visibility",
                AttributeInfo.newBuilder()
                    .setName("visibility")
                    .setType(AttributeType.LABEL_LIST)
                    .setMandatory(false)
                    .setNonconfigurable(true)
                    .setNativelyDefined(true)
                    .setDocString(
                        ("The visibility to be passed to this macro's exported targets. It always"
                                + " implicitly includes the location where this macro is instantiated, so"
                                + " this attribute only needs to be explicitly set if you want the macro's"
                                + " targets to be additionally visible somewhere else.")
                    )
                    .build()
            )

        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val IMPLICIT_REPOSITORY_RULE_ATTRIBUTES: com.google.common.collect.ImmutableMap<String?, AttributeInfo?> =
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "name",
                AttributeInfo.newBuilder()
                    .setName("name")
                    .setType(AttributeType.NAME)
                    .setMandatory(true)
                    .setDocString("A unique name for this repository.")
                    .build()
            )
    }
}
