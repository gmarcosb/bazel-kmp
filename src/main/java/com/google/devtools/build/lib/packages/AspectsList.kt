// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.packages.Aspect
import com.google.devtools.build.lib.packages.AspectClass
import com.google.devtools.build.lib.packages.AspectParameters
import com.google.devtools.build.lib.packages.NativeAspectClass
import com.google.devtools.build.lib.packages.RuleClass
import com.google.devtools.build.lib.packages.StarlarkAspect
import com.google.devtools.build.lib.packages.StarlarkAspectClass
import com.google.devtools.build.lib.packages.StarlarkDefinedAspect
import com.google.devtools.build.lib.packages.StarlarkNativeAspect
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import java.util.HashMap
import java.util.LinkedHashMap

/**
 * AspectsList represents the list of aspects specified via --aspects command line option or
 * declared in attribute aspects list. The class is responsible for wrapping the information
 * necessary for constructing those aspects.
 */
class AspectsList private constructor(aspects: com.google.common.collect.ImmutableList<AspectDetails<*>>) {
    private val aspects: com.google.common.collect.ImmutableList<AspectDetails<*>>

    fun hasAspects(): Boolean {
        return !aspects.isEmpty()
    }

    /** Returns the list of aspects required for dependencies through this attribute.  */
    fun getAspects(rule: com.google.devtools.build.lib.packages.Rule?): com.google.common.collect.ImmutableList<Aspect?> {
        if (aspects.isEmpty()) {
            return com.google.common.collect.ImmutableList.of<Aspect?>()
        }
        var builder: com.google.common.collect.ImmutableList.Builder<Aspect?>? = null
        for (aspect in aspects) {
            val a: Aspect? = aspect.getAspect(rule)
            if (a != null) {
                if (builder == null) {
                    builder = com.google.common.collect.ImmutableList.builder<Aspect?>()
                }
                builder.add(a)
            }
        }
        return if (builder == null) com.google.common.collect.ImmutableList.of<Aspect?>() else builder.build()
    }

    fun getAspectClasses(): com.google.common.collect.ImmutableList<AspectClass?> {
        val result: com.google.common.collect.ImmutableList.Builder<AspectClass?> =
            com.google.common.collect.ImmutableList.builder<AspectClass?>()
        for (aspect in aspects) {
            result.add(aspect.getAspectClass())
        }
        return result.build()
    }

    /** Returns a list of Aspect objects for top level aspects.  */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun buildAspects(aspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>): com.google.common.collect.ImmutableList<Aspect?> {
        com.google.common.base.Preconditions.checkArgument(
            aspectsParameters != null,
            "aspectsParameters cannot be null"
        )

        val aspectsList: com.google.common.collect.ImmutableList.Builder<Aspect?> =
            com.google.common.collect.ImmutableList.builder<Aspect?>()
        for (aspect in aspects) {
            aspectsList.add(aspect.getTopLevelAspect(aspectsParameters))
        }
        return aspectsList.build()
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateRulePropagatedAspectsParameters(ruleClass: RuleClass) {
        for (aspect in aspects) {
            val requiredAspectParameters: com.google.common.collect.ImmutableSet<String?> =
                aspect.getRequiredParameters()
            for (aspectAttribute in aspect.getAspectAttributes()) {
                val aspectAttrName: String? = aspectAttribute.getPublicName()
                val aspectAttrType: com.google.devtools.build.lib.packages.Type<*>? = aspectAttribute.getType()

                // When propagated from a rule, explicit aspect attributes must be of type boolean, int
                // or string. Integer and string attributes must have the `values` restriction.
                if (!aspectAttribute.isImplicit() && !aspectAttribute.isLateBound()) {
                    if (aspectAttrType !== com.google.devtools.build.lib.packages.Type.Companion.BOOLEAN && !aspectAttribute.checkAllowedValues()) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Aspect %s: Aspect parameter attribute '%s' must use the 'values' restriction.",
                            aspect.getName(), aspectAttrName
                        )
                    }
                }

                // Required aspect parameters must be specified by the rule propagating the aspect with
                // the same parameter type.
                if (requiredAspectParameters.contains(aspectAttrName)) {
                    if (!ruleClass.getAttributeProvider().hasAttr(aspectAttrName, aspectAttrType)) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Aspect %s requires rule %s to specify attribute '%s' with type %s.",
                            aspect.getName(), ruleClass.getName(), aspectAttrName, aspectAttrType
                        )
                    }
                }
            }
        }
    }

    /**
     * Validates top-level aspects parameters and reports error in the following cases:
     * 
     * 
     * If a parameter name is specified in command line but no aspect has a parameter with that
     * name.
     * 
     * 
     * If a mandatory aspect attribute is not given a value in the top-level parameters list.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun validateTopLevelAspectsParameters(aspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>) {
        com.google.common.base.Preconditions.checkArgument(
            aspectsParameters != null,
            "aspectsParameters cannot be null"
        )

        val usedParametersBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()
        for (aspectDetails in aspects) {
            if (aspectDetails is StarlarkAspectDetails) {
                val aspectAttributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> =
                    aspectDetails.aspect.getAttributes()
                for (attr in aspectAttributes) {
                    if (attr.isImplicit() || attr.isLateBound()) {
                        continue
                    }
                    val attrName: String? = attr.getName()
                    if (aspectsParameters.containsKey(attrName)) {
                        usedParametersBuilder.add(attrName)
                    } else if (attr.isMandatory()) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "Missing mandatory attribute '%s' for aspect '%s'.",
                            attrName, aspectDetails.getName()
                        )
                    }
                }
            }
        }
        val usedParameters: com.google.common.collect.ImmutableSet<String?> = usedParametersBuilder.build()
        val unusedParameters: com.google.common.collect.ImmutableList<String?> =
            aspectsParameters.keySet().stream()
                .filter(java.util.function.Predicate { p: String? -> !usedParameters.contains(p) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
        if (!unusedParameters.isEmpty()) {
            throw net.starlark.java.eval.Starlark.errorf(
                "Parameters '%s' are not parameters of any of the top-level aspects but they are"
                        + " specified in --aspects_parameters.",
                unusedParameters
            )
        }
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val aspectsList = o as AspectsList
        return aspects == aspectsList.aspects
    }

    override fun hashCode(): Int {
        return aspects.hashCode()
    }

    /** Wraps the information necessary to construct an Aspect.  */
    private abstract class AspectDetails<C : AspectClass?> {
        val aspectClass: C?
        val parametersExtractor: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>
        val requiredByAspect: String?

        private constructor(
            aspectClass: C?,
            parametersExtractor: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>
        ) {
            this.aspectClass = aspectClass
            this.parametersExtractor = parametersExtractor
            this.requiredByAspect = null
        }

        private constructor(
            aspectClass: C?,
            parametersExtractor: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>,
            requiredByAspect: String?
        ) {
            this.aspectClass = aspectClass
            this.parametersExtractor = parametersExtractor
            this.requiredByAspect = requiredByAspect
        }

        fun getName(): String? {
            return this.aspectClass.getName()
        }

        open fun getRequiredParameters(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.of<String?>()
        }

        open fun getAspectAttributes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> {
            return com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.packages.Attribute?>()
        }

        abstract fun getAspect(rule: com.google.devtools.build.lib.packages.Rule?): Aspect?

        @Throws(net.starlark.java.eval.EvalException::class)
        abstract fun getTopLevelAspect(aspectParameters: com.google.common.collect.ImmutableMap<String?, String?>?): Aspect?

        fun getAspectClass(): C? {
            return aspectClass
        }
    }

    private class NativeAspectDetails : AspectDetails<NativeAspectClass?> {
        internal constructor(
            aspectClass: NativeAspectClass?,
            parametersExtractor: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>
        ) : super(aspectClass, parametersExtractor)

        internal constructor(
            aspectClass: NativeAspectClass?,
            parametersExtractor: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?>,
            requiredByAspect: String?
        ) : super(aspectClass, parametersExtractor, requiredByAspect)

        override fun getAspect(rule: com.google.devtools.build.lib.packages.Rule?): Aspect? {
            val params: AspectParameters? = parametersExtractor.apply(rule)
            return if (params == null) null else Aspect.Companion.forNative(aspectClass, params)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        protected override fun getTopLevelAspect(aspectParameters: com.google.common.collect.ImmutableMap<String?, String?>?): Aspect? {
            // Native aspects ignore their top-level parameters values for now.
            return Aspect.Companion.forNative(aspectClass, AspectParameters.Companion.EMPTY)
        }
    }

    private class StarlarkAspectDetails(aspect: StarlarkDefinedAspect, requiredByAspect: String?) :
        AspectDetails<StarlarkAspectClass?>(
            aspect.getAspectClass(),
            aspect.getDefaultParametersExtractor(),
            requiredByAspect
        ) {
        private val aspect: StarlarkDefinedAspect

        init {
            this.aspect = aspect
        }

        override fun getRequiredParameters(): com.google.common.collect.ImmutableSet<String?>? {
            return aspect.getParamAttributes()
        }

        override fun getAspectAttributes(): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute> {
            return aspect.getAttributes()
        }

        override fun getAspect(rule: com.google.devtools.build.lib.packages.Rule?): Aspect? {
            val params: AspectParameters? = parametersExtractor.apply(rule)
            return Aspect.Companion.forStarlark(aspectClass, aspect.getDefinition(params), params)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getTopLevelAspect(aspectParameters: com.google.common.collect.ImmutableMap<String?, String?>?): Aspect? {
            val params: AspectParameters? = aspect.extractTopLevelParameters(aspectParameters)
            return Aspect.Companion.forStarlark(aspectClass, aspect.getDefinition(params), params)
        }
    }

    /** Aspect details that just wrap a pre-existing Aspect that doesn't vary with the Rule.  */
    private class PredefinedAspectDetails(aspect: Aspect) : AspectDetails<AspectClass?>(aspect.getAspectClass(), null) {
        private val aspect: Aspect

        init {
            this.aspect = aspect
        }

        override fun getAspect(rule: com.google.devtools.build.lib.packages.Rule?): Aspect {
            return aspect
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun getTopLevelAspect(aspectParameters: com.google.common.collect.ImmutableMap<String?, String?>?): Aspect {
            return aspect
        }
    }

    init {
        this.aspects = aspects
    }

    /** A builder for AspectsList  */
    class Builder {
        private val aspects: HashMap<String?, AspectDetails<*>?> = LinkedHashMap<String?, AspectDetails<*>?>()

        constructor()

        constructor(aspectsList: AspectsList) {
            for (aspect in aspectsList.aspects) {
                aspects.put(aspect.getName(), aspect)
            }
        }

        fun build(): AspectsList {
            return AspectsList(com.google.common.collect.ImmutableList.copyOf<AspectDetails<*>?>(aspects.values()))
        }

        /**
         * Adds a native aspect with its parameters extraction function to the aspects list.
         * 
         * @param aspect the native aspect to be added
         * @param evaluator function that extracts aspect parameters from rule.
         */
        /**
         * Adds a native aspect that does not need a parameters extractor to the aspects list.
         * 
         * @param aspect the native aspect to be added
         */
        @kotlin.jvm.JvmOverloads
        fun addAspect(
            aspect: NativeAspectClass?,
            evaluator: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?> = EMPTY_FUNCTION
        ) {
            val nativeAspectDetails = NativeAspectDetails(aspect, evaluator)
            this.aspects.put(nativeAspectDetails.getName(), nativeAspectDetails)
        }

        /** Attaches this aspect and its required aspects  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun addAspect(starlarkAspect: StarlarkAspect) {
            addAspect(starlarkAspect, null)
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun addAspect(starlarkAspect: StarlarkAspect, requiredByAspect: String?) {
            if (starlarkAspect is StarlarkDefinedAspect) {
                if (!starlarkAspect.isExported()) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Aspects should be top-level values in extension files that define them."
                    )
                }

                for (requiredAspect in starlarkAspect.getRequiredAspects()) {
                    addAspect(requiredAspect, starlarkAspect.getName())
                }
            }

            val needsToAdd = needsToBeAdded(starlarkAspect.getName(), requiredByAspect)
            if (needsToAdd) {
                val aspectDetails: AspectDetails<*>

                if (starlarkAspect is StarlarkDefinedAspect) {
                    aspectDetails =
                        StarlarkAspectDetails(starlarkAspect as StarlarkDefinedAspect, requiredByAspect)
                } else if (starlarkAspect is StarlarkNativeAspect) {
                    aspectDetails =
                        NativeAspectDetails(
                            starlarkAspect as StarlarkNativeAspect,
                            starlarkAspect.getDefaultParametersExtractor(),
                            requiredByAspect
                        )
                } else {
                    throw java.lang.IllegalArgumentException()
                }
                this.aspects.put(starlarkAspect.getName(), aspectDetails)
            }
        }

        /** Should only be used for deserialization.  */
        fun addAspect(aspect: Aspect) {
            val predefinedAspectDetails = PredefinedAspectDetails(aspect)
            this.aspects.put(predefinedAspectDetails.getName(), predefinedAspectDetails)
        }

        /**
         * Adds all aspect from the list.
         * 
         * 
         * The function is intended for extended Starlark rules, where aspect list is already built
         * and may include aspects required by other aspects.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun addAspects(aspectsList: AspectsList) {
            for (aspect in aspectsList.aspects) {
                val needsToAdd = needsToBeAdded(aspect.getName(), aspect.requiredByAspect)
                if (needsToAdd) {
                    aspects.put(aspect.getName(), aspect)
                }
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun needsToBeAdded(aspectName: String?, requiredByAspect: String?): Boolean {
            val oldAspect: AspectDetails<*>? = this.aspects.get(aspectName)

            if (oldAspect != null) {
                if (requiredByAspect != null) {
                    // If the aspect to be added already exists and it is required by another aspect, no need
                    // to
                    // add it again.
                    return false
                } else {
                    // If the aspect to be added is not required by another aspect, then we should throw error
                    val oldAspectBaseAspectName = oldAspect.requiredByAspect
                    if (oldAspectBaseAspectName != null) {
                        throw net.starlark.java.eval.Starlark.errorf(
                            "aspect %s was added before as a required aspect of aspect %s",
                            oldAspect.getName(), oldAspectBaseAspectName
                        )
                    }
                    throw net.starlark.java.eval.Starlark.errorf("aspect %s added more than once", oldAspect.getName())
                }
            }

            return true // we need to add the new aspect
        }
    }

    companion object {
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val EMPTY_FUNCTION: com.google.common.base.Function<com.google.devtools.build.lib.packages.Rule?, AspectParameters?> =
            com.google.common.base.Function { input: com.google.devtools.build.lib.packages.Rule? -> AspectParameters.Companion.EMPTY }
    }
}
