// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.starlark.FunctionTransitionUtil.applyAndValidate

/**
 * This class implements [TransitionFactory] to provide a starlark-defined transition that
 * rules can apply to their dependencies' configurations. This transition has access to (1) the map
 * of the current configuration's build settings and (2) the configured attributes of the given rule
 * (not its dependencies').
 * 
 * 
 * For starlark defined rule class transitions, see [StarlarkRuleTransitionProvider].
 * 
 * 
 * TODO(bazel-team): Consider allowing dependency-typed attributes to actually return providers
 * instead of just labels (see [StarlarkAttributesCollection.addAttribute]).
 */
class StarlarkAttributeTransitionProvider
    (starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition) : TransitionFactory<AttributeTransitionData?>,
    SplitTransitionProviderApi {
    private val starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition

    init {
        this.starlarkDefinedConfigTransition = starlarkDefinedConfigTransition
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStarlarkDefinedConfigTransitionForTesting(): StarlarkDefinedConfigTransition {
        return starlarkDefinedConfigTransition
    }

    override fun hashCode(): Int {
        return starlarkDefinedConfigTransition.hashCode()
    }

    public override fun create(data: AttributeTransitionData): SplitTransition? {
        val attributeMap: AttributeMap? = data.attributes()
        com.google.common.base.Preconditions.checkArgument(
            attributeMap == null || attributeMap is ConfiguredAttributeMapper
        )
        // TODO(bazel-team): consider caching transition instances to save CPU time, similar to what's
        // done in StarlarkRuleTransitionProvider. This could benefit builds that apply transitions over
        // many build graph edges.
        return FunctionSplitTransition(
            starlarkDefinedConfigTransition, attributeMap as ConfiguredAttributeMapper?
        )
    }

    fun allowImmutableFlagChanges(): Boolean {
        return false
    }

    fun isExecTransitionProvider(): Boolean {
        return false
    }

    public override fun transitionType(): TransitionType {
        return TransitionType.ATTRIBUTE
    }

    public override fun isSplit(): Boolean {
        return true
    }

    public override fun repr(printer: net.starlark.java.eval.Printer, semantics: StarlarkSemantics?) {
        printer.append("<transition object>")
    }

    internal inner class FunctionSplitTransition private constructor(
        starlarkDefinedConfigTransition: StarlarkDefinedConfigTransition?,
        attributeMap: ConfiguredAttributeMapper?
    ) : StarlarkTransition(starlarkDefinedConfigTransition), SplitTransition {
        private val attrObject: StructImpl?
        private val hashCode: Int

        init {
            val attributes: LinkedHashMap<String?, Any?> = LinkedHashMap<String?, Any?>()
            if (attributeMap != null) {
                for (attribute in attributeMap.getAttributeNames()) {
                    val `val`: Any? = attributeMap.get(attribute, attributeMap.getAttributeType(attribute))
                    try {
                        val starlarkVal: Any? = Attribute.valueToStarlark(`val`)
                        attributes.put(Attribute.getStarlarkName(attribute), starlarkVal)
                    } catch (e: InvalidStarlarkValueException) {
                        // This is only possible for native targets, since Starlark targets by definition have
                        // Starlark-readable attributes. The only Starlark transition that can apply to native
                        // targets is the exec transition (ExecutionTransitionFactory). Since that's
                        // experimental
                        // we don't need to do anything further.
                        // TODO(b/288258583): encode this more cleanly than a universally swallowed exception.
                    }
                }
            }
            attrObject = StructProvider.STRUCT.create(
                attributes,
                StarlarkAttributesCollection.Companion.ERROR_MESSAGE_FOR_NO_ATTR
            )
            this.hashCode = java.util.Objects.hash(attrObject, super.hashCode())
        }

        /**
         * @return the post-transition build options or a clone of the original build options if an
         * error was encountered during transition application/validation.
         */
        @Throws(java.lang.InterruptedException::class)
        public override fun split(
            buildOptionsView: BuildOptionsView, eventHandler: com.google.devtools.build.lib.events.EventHandler?
        ): com.google.common.collect.ImmutableMap<String?, BuildOptions?> {
            // Starlark transitions already have logic to enforce they only access declared inputs and
            // outputs. Rather than complicate BuildOptionsView with more access points to BuildOptions,
            // we just use the original BuildOptions and trust the transition's enforcement logic.
            val buildOptions: BuildOptions = buildOptionsView.underlying()
            val res: com.google.common.collect.ImmutableMap<String?, BuildOptions?>? =
                applyAndValidate(
                    buildOptions,
                    starlarkDefinedConfigTransition,
                    allowImmutableFlagChanges(),
                    isExecTransitionProvider(),
                    attrObject,
                    eventHandler
                )
            if (res == null) {
                return com.google.common.collect.ImmutableMap.of<String?, BuildOptions?>("error", buildOptions.clone())
            }
            return res
        }

        public override fun isExecTransition(): Boolean {
            return isExecTransitionProvider()
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            }
            if (`object` !is FunctionSplitTransition) {
                return false
            }
            return attrObject == `object`.attrObject && super.equals(`object`)
        }

        override fun hashCode(): Int {
            return hashCode
        }
    }
}
