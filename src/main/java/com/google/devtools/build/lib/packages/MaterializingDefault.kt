// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.events.EventHandler

/**
 * The default value of attributes with materializers.
 * 
 * 
 * It's just a reference to the function that does the materializing.
 */
class MaterializingDefault<ValueT, AnalysisContextT>(
    type: com.google.devtools.build.lib.packages.Type<ValueT?>,
    analysisContextClass: java.lang.Class<out AnalysisContextT?>,
    resolver: Resolver<ValueT?, AnalysisContextT?>
) {
    private val type: com.google.devtools.build.lib.packages.Type<ValueT?>
    private val resolver: Resolver<ValueT?, AnalysisContextT?>
    private val analysisContextClass: java.lang.Class<out AnalysisContextT?>

    init {
        com.google.common.base.Preconditions.checkArgument(type === BuildType.LABEL || type === BuildType.LABEL_LIST)
        this.type = type
        this.resolver = resolver
        this.analysisContextClass = analysisContextClass
    }

    fun getDefault(): ValueT? {
        // Materializers can only return dormant dependencies, which are already present in the
        // transitive closure. So we can safely return "nothing" for "bazel query": the invariant that
        // everything needed to build a target is returned by "bazel query deps()" holds because
        // whatever a materializer returns is reachable through dormant dependency edges, which are
        // traversed by "bazel query".
        return type.getDefaultValue()
    }

    /**
     * The implementation of the actual resolution of the late-bound default.
     * 
     * 
     * This is a separate interface because MaterializingDefault must be known to the loading phase
     * but its implementation necessarily deals with analysis-phase data structures.
     */
    interface Resolver<ValueT, PrerequisiteT> {
        /**
         * Resolves an attribute with a materializer.
         * 
         * 
         * param rule the rule whose attribute is to be resolved.
         * 
         * @param attributes the attributes of the rule, after resolving `select()` and the like
         * @param prerequisiteMap a map from attribute name to the prerequisites on that attribute. Only
         * those attributes are present that represent dependencies and which are available for
         * dependency resolution. The value of the map is in fact `List<? extends     TransitiveInfoCollection`, but we can't say that because this class needs to be available
         * in the loading phase.
         * @param eventHandler messages from Starlark should be reported here
         * @return the value of the resolved attribute.
         */
        @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
        fun resolve(
            rule: com.google.devtools.build.lib.packages.Rule?,
            attributes: com.google.devtools.build.lib.packages.AttributeMap?,
            prerequisiteMap: PrerequisiteT?,
            eventHandler: EventHandler?
        ): ValueT?
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun resolve(
        rule: com.google.devtools.build.lib.packages.Rule?,
        attributes: com.google.devtools.build.lib.packages.AttributeMap?,
        analysisContext: Any?,
        eventHandler: EventHandler?
    ): ValueT? {
        return resolver.resolve(
            rule, attributes, analysisContextClass.cast(analysisContext), eventHandler
        )
    }
}
