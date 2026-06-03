// Copyright 2017 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Artifact

/** Information about attributes of a rule an aspect is applied to.  */
class StarlarkAttributesCollection private constructor(
    starlarkRuleContext: StarlarkRuleContext,
    ruleClassName: String?,
    attrs: MutableMap<String?, Any?>?,
    executables: MutableMap<String?, Any?>?,
    singleFiles: MutableMap<String?, Any?>?,
    files: MutableMap<String?, Any?>?,
    executableRunfilesMap: com.google.common.collect.ImmutableMap<Artifact?, FilesToRunProvider?>?,
    ruleVariables: Dict<String?, String?>?
) : StarlarkAttributesCollectionApi {
    private val starlarkRuleContext: StarlarkRuleContext
    private val attrObject: StructImpl?
    private val executableObject: StructImpl?
    private val fileObject: StructImpl?
    private val filesObject: StructImpl?
    private val executableRunfilesMap: com.google.common.collect.ImmutableMap<Artifact?, FilesToRunProvider?>?
    private val ruleClassName: String?
    private val ruleVariables: Dict<String?, String?>?

    init {
        this.starlarkRuleContext = starlarkRuleContext
        this.ruleClassName = ruleClassName
        attrObject = StructProvider.STRUCT.create(attrs, ERROR_MESSAGE_FOR_NO_ATTR)
        executableObject =
            StructProvider.STRUCT.create(
                executables,
                "No attribute '%s' in executable. Make sure there is a label type attribute marked "
                        + "as 'executable' with this name"
            )
        fileObject =
            StructProvider.STRUCT.create(
                singleFiles,
                "No attribute '%s' in file. Make sure there is a label type attribute marked "
                        + "as 'allow_single_file' with this name"
            )
        filesObject =
            StructProvider.STRUCT.create(
                files,
                "No attribute '%s' in files. Make sure there is a label or label_list type attribute "
                        + "with this name"
            )
        this.executableRunfilesMap = executableRunfilesMap
        this.ruleVariables = ruleVariables
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    private fun checkMutable(attrName: String?) {
        starlarkRuleContext.checkMutable("rule." + attrName)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getAttr(): StructImpl? {
        checkMutable("attr")
        return attrObject
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getExecutable(): StructImpl? {
        checkMutable("executable")
        return executableObject
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFile(): StructImpl? {
        checkMutable("file")
        return fileObject
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getFiles(): StructImpl? {
        checkMutable("files")
        return filesObject
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun getRuleClassName(): String? {
        checkMutable("kind")
        return ruleClassName
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun toolchains(): ToolchainContextApi {
        checkMutable("toolchains")
        if ((starlarkRuleContext.getRuleContext() as AspectContext).getBaseTargetToolchainContexts()
            == null
        ) {
            return StarlarkToolchainContext.TOOLCHAINS_NOT_VALID
        }
        val aspectContext: AspectContext = (starlarkRuleContext.getRuleContext() as AspectContext)

        return StarlarkToolchainContext.create(
            aspectContext
                .getBaseTargetToolchainContexts()
                .getDefaultToolchainContext()
                .targetDescription(),  /* resolveToolchainDataFunc= */
            { toolchainType: Label? -> aspectContext.getToolchainTarget(toolchainType) },  /* resolvedToolchainTypeLabels= */
            aspectContext.getRequestedToolchainTypesLabels()
        )
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    public override fun execGroups(): ExecGroupCollectionApi {
        checkMutable("exec_groups")
        if ((starlarkRuleContext.getRuleContext() as AspectContext).getBaseTargetToolchainContexts()
            == null
        ) {
            return StarlarkExecGroupCollection.EXEC_GROUP_COLLECTION_NOT_VALID
        }
        // Create a thin wrapper around the toolchain collection, to expose the Starlark API.
        return StarlarkExecGroupCollection.create(
            (starlarkRuleContext.getRuleContext() as AspectContext).getBaseTargetToolchainContexts()
        )
    }

    fun getExecutableRunfilesMap(): com.google.common.collect.ImmutableMap<Artifact?, FilesToRunProvider?>? {
        return executableRunfilesMap
    }

    public override fun isImmutable(): Boolean {
        return starlarkRuleContext.isImmutable()
    }

    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<rule collection for " + starlarkRuleContext.getRuleLabelCanonicalName() + ">")
    }

    /** A builder for [StarlarkAttributesCollection].  */
    class Builder private constructor(
        ruleContext: StarlarkRuleContext,
        prerequisitesCollection: PrerequisitesCollection
    ) {
        private val context: StarlarkRuleContext
        private val prerequisites: PrerequisitesCollection

        private val attrBuilder: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        private val executableBuilder: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        private val executableRunfilesbuilder: com.google.common.collect.ImmutableMap.Builder<Artifact?, FilesToRunProvider?> =
            com.google.common.collect.ImmutableMap.builder<Artifact?, FilesToRunProvider?>()
        private val fileBuilder: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        private val filesBuilder: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
        private val seenExecutables: HashSet<Artifact?> = HashSet<Artifact?>()
        private val ruleVariablesBuilder: net.starlark.java.eval.Dict.Builder<String?, String?> =
            net.starlark.java.eval.Dict.Builder<String?, String?>()

        init {
            this.context = ruleContext
            this.prerequisites = prerequisitesCollection
        }

        fun addAttribute(a: Attribute, `val`: Any?) {
            val type: Type<*> = a.getType()
            val skyname: String? = a.getPublicName()

            // The first attribute with the same name wins.
            if (attrBuilder.containsKey(skyname)) {
                return
            }

            val starlarkVal: Any? =
                com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertAttributeValue(
                    java.util.function.Supplier { prerequisites.getPrerequisites(a.getName()) },
                    a,
                    `val`
                )
            if (starlarkVal == null) {
                return
            }

            attrBuilder.put(skyname, starlarkVal)
            if (type.getLabelClass() !== LabelClass.DEPENDENCY) {
                return
            }

            val files: NestedSet<Artifact?> = PrerequisiteArtifacts.Companion.nestedSet(prerequisites, a.getName())
            filesBuilder.put(
                skyname,
                if (files.isEmpty())
                    StarlarkList.empty<Any?>()
                else
                    StarlarkList.lazyImmutable<Artifact?>(
                        files::toList as SerializableListSupplier<Artifact?>
                    )
            )

            if (a.isExecutable()) {
                // In a Starlark-defined rule, only LABEL type attributes (not LABEL_LIST) can have the
                // Executable flag. However, we could be here because we're creating a StarlarkRuleContext
                // for a native rule for builtins injection, in which case we may see an executable
                // LABEL_LIST. In that case omit the attribute as if it weren't executable.
                if (type === BuildType.LABEL) {
                    val provider: FilesToRunProvider? = prerequisites.getExecutablePrerequisite(a.getName())
                    if (provider != null && provider.getExecutable() != null) {
                        val executable: Artifact? = provider.getExecutable()
                        executableBuilder.put(skyname, executable)
                        if (!seenExecutables.contains(executable)) {
                            // todo(dslomov,laurentlb): In general, this is incorrect.
                            // We associate the first encountered FilesToRunProvider with
                            // the executable (this provider is later used to build the spawn).
                            // However ideally we should associate a provider with the attribute name,
                            // and pass the correct FilesToRunProvider to the spawn depending on
                            // what attribute is used to access the executable.
                            executableRunfilesbuilder.put(executable, provider)
                            seenExecutables.add(executable)
                        }
                    } else {
                        executableBuilder.put(skyname, Starlark.NONE)
                    }
                }
            }
            if (a.isSingleArtifact()) {
                // In Starlark only label (not label list) type attributes can have the SingleArtifact flag.
                val artifact: Artifact? = prerequisites.getPrerequisiteArtifact(a.getName())
                if (artifact != null) {
                    fileBuilder.put(skyname, artifact)
                } else {
                    fileBuilder.put(skyname, Starlark.NONE)
                }
            }
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun putAllRuleVariables(`var`: Dict<String?, String?>?): Builder {
            this.ruleVariablesBuilder.putAll(`var`)
            return this
        }

        fun build(): StarlarkAttributesCollection {
            return StarlarkAttributesCollection(
                context,
                context.getRuleContext().getRule().getRuleClass(),
                attrBuilder,
                executableBuilder,
                fileBuilder,
                filesBuilder,
                executableRunfilesbuilder.buildOrThrow(),
                ruleVariablesBuilder.buildImmutable()
            )
        }

        companion object {
            private fun buildPrequisiteMap(
                prerequisites: MutableList<out TransitiveInfoCollection>
            ): MutableMap<Label?, TransitiveInfoCollection?> {
                val prerequisiteMap: MutableMap<Label?, TransitiveInfoCollection?> =
                    com.google.common.collect.Maps.newHashMapWithExpectedSize<Label?, TransitiveInfoCollection?>(
                        prerequisites.size()
                    )
                for (prereq in prerequisites) {
                    prerequisiteMap.put(AliasProvider.Companion.getDependencyLabel(prereq), prereq)
                }
                return prerequisiteMap
            }

            fun convertStringToLabelMap(
                unconfiguredValue: MutableMap<String?, Label?>,
                prerequisites: MutableList<out TransitiveInfoCollection>
            ): Dict<String?, TransitiveInfoCollection?>? {
                val prerequisiteMap: MutableMap<Label?, TransitiveInfoCollection?> =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.buildPrequisiteMap(
                        prerequisites
                    )
                val builder: com.google.common.collect.ImmutableMap.Builder<String?, TransitiveInfoCollection?> =
                    com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, TransitiveInfoCollection?>(
                        unconfiguredValue.size()
                    )
                unconfiguredValue.forEach(java.util.function.BiConsumer { key: String?, label: Label? ->
                    builder.put(
                        key,
                        prerequisiteMap.get(label)
                    )
                })
                return Dict.immutableCopyOf<String?, TransitiveInfoCollection?>(builder.buildOrThrow())
            }

            fun convertStringToLabelListMap(
                unconfiguredValue: MutableMap<String?, MutableList<Label?>?>,
                prerequisites: MutableList<out TransitiveInfoCollection>
            ): Dict<String?, StarlarkList<TransitiveInfoCollection?>?>? {
                val prerequisiteMap: MutableMap<Label?, TransitiveInfoCollection?> =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.buildPrequisiteMap(
                        prerequisites
                    )
                val builder: com.google.common.collect.ImmutableMap.Builder<String?, StarlarkList<TransitiveInfoCollection?>?> =
                    com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, StarlarkList<TransitiveInfoCollection?>?>(
                        unconfiguredValue.size()
                    )
                unconfiguredValue.forEach(
                    java.util.function.BiConsumer { key: String?, labels: MutableList<Label?>? ->
                        builder.put(
                            key,
                            StarlarkList.immutableCopyOf<TransitiveInfoCollection?>(
                                com.google.common.collect.Lists.transform<Label?, TransitiveInfoCollection?>(
                                    labels,
                                    com.google.common.base.Function { key: Label? -> prerequisiteMap.get(key) })
                            )
                        )
                    })
                return Dict.immutableCopyOf<String?, StarlarkList<TransitiveInfoCollection?>?>(builder.buildOrThrow())
            }

            private fun shouldIgnore(a: Attribute): Boolean {
                val type: Type<*>? = a.getType()
                val skyname: String? = a.getPublicName()

                // Some legacy native attribute types do not have a valid Starlark type. Avoid exposing
                // these to Starlark.
                if (type === BuildType.TRISTATE) {
                    return true
                }

                // Don't expose invalid attributes via the rule ctx.attr. Ordinarily, this case cannot happen,
                // and currently only applies to subrule attributes
                // TODO: b/293304174 - let subrules explicitly mark attributes as not-visible-to-starlark
                if (!net.starlark.java.syntax.Identifier.isValid(skyname)) {
                    return true
                }

                // Don't expose exec_group_compatible_with to Starlark. There is no reason for it to be used
                // by the rule implementation function and its type (LABEL_LIST_DICT) is not available to
                // Starlark.
                if (a.getName().equals(RuleClass.EXEC_GROUP_COMPATIBLE_WITH_ATTR)) {
                    return true
                }

                return false
            }

            private fun maybeDirectVal(a: Attribute, `val`: Any?): Any? {
                val type: Type<*> = a.getType()

                if (type === BuildType.DORMANT_LABEL) {
                    return if (`val` == null)
                        Starlark.NONE
                    else
                        DormantDependency(BuildType.DORMANT_LABEL.cast(`val`))
                }

                if (type === BuildType.DORMANT_LABEL_LIST) {
                    val dormantDeps: StarlarkList<DormantDependency?>? =
                        StarlarkList.immutableCopyOf<T?>(
                            BuildType.DORMANT_LABEL_LIST.cast(`val`).stream()
                                .map({ DormantDependency() })
                                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                        )
                    return dormantDeps
                }

                if (type.getLabelClass() !== LabelClass.DEPENDENCY) {
                    // Attribute values should be type safe
                    return Attribute.valueToStarlark(`val`)
                }

                return null
            }

            fun convertAttributeValueForAspectPropagationFunc(
                depLabelsSupplier: java.util.function.Supplier<MutableCollection<Label?>?>, a: Attribute, `val`: Any?
            ): Any? {
                if (com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.shouldIgnore(
                        a
                    )
                ) {
                    return null
                }

                val maybeVal: Any? =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.maybeDirectVal(
                        a,
                        `val`
                    )
                if (maybeVal != null) {
                    return maybeVal
                }

                val type: Type<*>? = a.getType()

                val prerequisites: MutableCollection<Label?>? = depLabelsSupplier.get()

                if (a.isMaterializing() || prerequisites == null || prerequisites.contains(null)) {
                    return Starlark.NONE
                }

                if (type === BuildType.LABEL && !a.getTransitionFactory().isSplit()) {
                    return if (prerequisites.isEmpty()) Starlark.NONE else prerequisites.iterator().next()
                } else if (type === BuildType.LABEL_LIST
                    || (type === BuildType.LABEL && a.getTransitionFactory().isSplit())
                ) {
                    return StarlarkList.immutableCopyOf<Label?>(prerequisites)
                } else if (type === BuildType.LABEL_DICT_UNARY || type === BuildType.LABEL_KEYED_STRING_DICT) {
                    return `val` // return the same map as the labels are not configured to targets
                } else if (type === BuildType.LABEL_LIST_DICT) {
                    // The type of the inner lists has to be converted to Starlark.
                    return Dict.immutableCopyOf<K?, V?>(
                        com.google.common.collect.Maps.transformValues(
                            BuildType.LABEL_LIST_DICT.cast(`val`),
                            { elems: Iterable<out T?>? -> StarlarkList.immutableCopyOf(elems) })
                    )
                } else {
                    throw java.lang.IllegalArgumentException(
                        ("Can't transform attribute "
                                + a.getName()
                                + " of type "
                                + type
                                + " to a Starlark object")
                    )
                }
            }

            fun convertAttributeValue(
                prerequisiteSupplier: java.util.function.Supplier<MutableList<out TransitiveInfoCollection?>?>,
                a: Attribute,
                `val`: Any?
            ): Any? {
                if (com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.shouldIgnore(
                        a
                    )
                ) {
                    return null
                }

                val maybeVal: Any? =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.maybeDirectVal(
                        a,
                        `val`
                    )
                if (maybeVal != null) {
                    return maybeVal
                }

                val type: Type<*>? = a.getType()

                if (type === BuildType.LABEL && !a.getTransitionFactory().isSplit()) {
                    val prerequisites: MutableList<out TransitiveInfoCollection?> = prerequisiteSupplier.get()
                    return if (prerequisites.isEmpty()) Starlark.NONE else prerequisites.get(0)
                } else if (type === BuildType.LABEL_LIST
                    || (type === BuildType.LABEL && a.getTransitionFactory().isSplit())
                ) {
                    val allPrereq: MutableList<*>? = prerequisiteSupplier.get()
                    return StarlarkList.immutableCopyOf(allPrereq)
                } else if (type === BuildType.LABEL_DICT_UNARY) {
                    return com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertStringToLabelMap(
                        BuildType.LABEL_DICT_UNARY.cast(`val`), prerequisiteSupplier.get()
                    )
                } else if (type === BuildType.LABEL_KEYED_STRING_DICT) {
                    val original: MutableMap<Label?, String?> = BuildType.LABEL_KEYED_STRING_DICT.cast(`val`)
                    val builder: com.google.common.collect.ImmutableMap.Builder<TransitiveInfoCollection?, String?> =
                        com.google.common.collect.ImmutableMap.builderWithExpectedSize<TransitiveInfoCollection?, String?>(
                            original.size()
                        )
                    val allPrereq: MutableList<out TransitiveInfoCollection?> = prerequisiteSupplier.get()
                    for (prereq in allPrereq) {
                        builder.put(prereq, original.get(AliasProvider.Companion.getDependencyLabel(prereq)))
                    }
                    return Dict.immutableCopyOf<TransitiveInfoCollection?, String?>(builder.buildOrThrow())
                } else if (type === BuildType.LABEL_LIST_DICT) {
                    return com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder.Companion.convertStringToLabelListMap(
                        BuildType.LABEL_LIST_DICT.cast(`val`), prerequisiteSupplier.get()
                    )
                } else {
                    throw java.lang.IllegalArgumentException(
                        ("Can't transform attribute "
                                + a.getName()
                                + " of type "
                                + type
                                + " to a Starlark object")
                    )
                }
            }
        }
    }

    @Throws(net.starlark.java.eval.EvalException::class, java.lang.InterruptedException::class)
    public override fun `var`(): Dict<String?, String?>? {
        return this.ruleVariables
    }

    companion object {
        const val ERROR_MESSAGE_FOR_NO_ATTR: String =
            "No attribute '%s' in attr. Make sure you declared a rule attribute with this name."

        fun builder(
            ruleContext: StarlarkRuleContext, prerequisitesCollection: PrerequisitesCollection
        ): Builder {
            return com.google.devtools.build.lib.analysis.starlark.StarlarkAttributesCollection.Builder(
                ruleContext,
                prerequisitesCollection
            )
        }
    }
}
