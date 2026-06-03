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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.cmdline.Label

/** Helpers for aspect resolution.  */
object AspectResolutionHelpers {
    /**
     * Computes the set of aspects that could be applied to a dependency.
     * 
     * 
     * This is composed of two parts:
     * 
     * 
     *  1. The aspects that are visible to this aspect being evaluated, if any. If another aspect is
     * visible on the configured target, it should also be visible on the dependencies for
     * consistency. This is the argument `aspectsPath`.
     *  1. The aspects propagated by the attributes of this configured target / aspect.
     * 
     * 
     * 
     * The presence of an aspect here does not necessarily mean that it will be available on a
     * dependency: it can still be filtered out because it requires a provider that the configured
     * target it should be attached to it doesn't advertise. This is taken into account in [ ][.computeAspectCollection].
     */
    fun computePropagatingAspects(
        kind: DependencyKind,
        aspectsPath: com.google.common.collect.ImmutableList<Aspect>,
        computedAttributeAspects: com.google.common.collect.ImmutableMultimap<Aspect?, String?>,
        computedToolchainsAspects: com.google.common.collect.ImmutableMultimap<Aspect?, Label?>,
        rule: Rule?,
        baseTargetToolchainContext: ToolchainCollection<UnloadedToolchainContext?>?
    ): com.google.common.collect.ImmutableList<Aspect?> {
        if (DependencyKind.isBaseTargetToolchain(kind)) {
            return computePropagatingAspectsToToolchainDep(
                kind as DependencyKind.BaseTargetToolchainDependencyKind?,
                aspectsPath,
                computedToolchainsAspects,
                baseTargetToolchainContext
            )
        }

        val attribute: Attribute? = kind.getAttribute()
        if (attribute == null) {
            return com.google.common.collect.ImmutableList.of<Aspect?>()
        }
        val aspectsBuilder: com.google.common.collect.ImmutableList.Builder<Aspect?> =
            com.google.common.collect.ImmutableList.Builder<Aspect?>().addAll(attribute.getAspects(rule))
        collectPropagatingAspects(
            aspectsPath,
            computedAttributeAspects,
            attribute.getName(),
            kind.getOwningAspect(),
            aspectsBuilder
        )
        return aspectsBuilder.build()
    }

    /**
     * Compute the set of aspects propagating to the given [BaseTargetToolchainDependencyKind]
     * based on the `toolchains_aspects` of each aspect in the `aspectsPath`.
     */
    private fun computePropagatingAspectsToToolchainDep(
        kind: DependencyKind.BaseTargetToolchainDependencyKind,
        aspectsPath: com.google.common.collect.ImmutableList<Aspect>,
        computedToolchainsAspects: com.google.common.collect.ImmutableMultimap<Aspect?, Label?>,
        baseTargetToolchainContext: ToolchainCollection<UnloadedToolchainContext?>?
    ): com.google.common.collect.ImmutableList<Aspect?> {
        val toolchainContext: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            baseTargetToolchainContext.getToolchainContext(kind.getExecGroupName())
        val toolchainType: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            toolchainContext.requestedLabelToToolchainType().get(kind.getToolchainType())

        // Since the label of the toolchain type can be an alias, we need to get all the labels that
        // point to the same toolchain type to compare them against the toolchain types that the aspects
        // can propagate.
        val allToolchainTypelabels: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            toolchainContext.requestedLabelToToolchainType().asMultimap().inverse().get(toolchainType)

        val filteredAspectPath: java.util.ArrayList<Aspect> = java.util.ArrayList<Aspect>()

        val aspectsCount: Int = aspectsPath.size
        for (i in aspectsCount - 1 downTo 0) {
            val aspect: Aspect = aspectsPath.get(i)
            if (allToolchainTypelabels.stream()
                    .anyMatch({ t -> AspectResolutionHelpers.propagatesTo<T?>(t, aspect, computedToolchainsAspects) })
                || isAspectRequired(aspect, filteredAspectPath)
            ) {
                // Adds the aspect if it propagates to the toolchain type or it is
                // required by an aspect already in the {@code filteredAspectPath}.
                filteredAspectPath.add(aspect)
            }
        }
        Collections.reverse(filteredAspectPath)

        return com.google.common.collect.ImmutableList.copyOf<Aspect?>(filteredAspectPath)
    }

    private fun <T> propagatesTo(
        edge: T?, aspect: Aspect?, computedEdges: com.google.common.collect.ImmutableMultimap<Aspect?, T?>
    ): Boolean {
        return computedEdges.containsEntry(aspect, edge)
                || computedEdges.containsEntry(aspect, "*")
                || computedEdges.containsEntry(aspect, AspectPropagationEdgesSupplier.ALL_TOOLCHAINS)
    }

    /**
     * Computes the way aspects should be computed for the direct dependencies.
     * 
     * 
     * This is done by filtering the aspects that can be propagated on any attribute according to
     * the providers advertised by direct dependencies and by creating the [AspectCollection]
     * that tells how to compute the final set of providers based on the interdependencies between the
     * propagating aspects.
     */
    @Throws(
        InconsistentAspectOrderException::class,
        java.lang.InterruptedException::class,
        net.starlark.java.eval.EvalException::class
    )
    fun computeAspectCollection(
        aspects: com.google.common.collect.ImmutableList<Aspect>,
        advertisedProviders: AdvertisedProviderSet?,
        targetLabel: Label?,
        ruleDefinitionEnvironmentLabel: Label?,
        ruleClassName: String?,
        tags: com.google.common.collect.ImmutableList<String?>?,
        targetLocation: net.starlark.java.syntax.Location?,
        eventHandler: ExtendedEventHandler?
    ): AspectCollection {
        val filteredAspectPath: java.util.ArrayList<Aspect> = java.util.ArrayList<Aspect>()

        val aspectsCount: Int = aspects.size
        for (i in aspectsCount - 1 downTo 0) {
            val aspect: Aspect = aspects.get(i)
            if (AspectDefinition.satisfies(aspect, advertisedProviders)
                || isAspectRequired(aspect, filteredAspectPath)
            ) {
                // Considers the aspect if the target satisfies its required providers or it is
                // required by an aspect already in the {@code filteredAspectPath}.
                if (evaluatePropagationPredicate(
                        aspect,
                        targetLabel,
                        ruleDefinitionEnvironmentLabel,
                        ruleClassName,
                        tags,
                        eventHandler
                    )
                ) {
                    // Only add the aspect if its propagation predicate is satisfied by the target.
                    filteredAspectPath.add(aspect)
                }
            }
        }

        Collections.reverse(filteredAspectPath)
        return computeAspectCollectionNoAspectsFiltering(
            com.google.common.collect.ImmutableList.copyOf<Aspect?>(filteredAspectPath), targetLabel, targetLocation
        )
    }

    @Throws(InconsistentAspectOrderException::class)
    fun computeAspectCollectionNoAspectsFiltering(
        aspects: com.google.common.collect.ImmutableList<Aspect?>?,
        targetLabel: Label?,
        targetLocation: net.starlark.java.syntax.Location?
    ): AspectCollection {
        try {
            return AspectCollection.Companion.create(aspects)
        } catch (e: AspectCycleOnPathException) {
            throw InconsistentAspectOrderException(targetLabel, targetLocation, e)
        }
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    private fun evaluatePropagationPredicate(
        aspect: Aspect,
        label: Label?,
        ruleDefinitionEnvironmentLabel: Label?,
        ruleClassName: String?,
        tags: com.google.common.collect.ImmutableList<String?>?,
        eventHandler: ExtendedEventHandler?
    ): Boolean {
        if (aspect.getDefinition().getPropagationPredicate() == null) {
            return true
        }
        return aspect
            .getDefinition()
            .getPropagationPredicate()
            .evaluate(
                StarlarkAspectPropagationContext.Companion.createForPropagationPredicate(
                    aspect, label, ruleDefinitionEnvironmentLabel, ruleClassName, tags
                ),
                eventHandler
            )
    }

    /**
     * Collects the aspects from `aspectsPath` that need to be propagated along the attribute
     * `attributeName`.
     * 
     * 
     * It can happen that some of the aspects cannot be propagated if the dependency doesn't have a
     * provider that's required by them. These will be filtered out after the rule class of the
     * dependency is known.
     */
    private fun collectPropagatingAspects(
        aspectsPath: com.google.common.collect.ImmutableList<Aspect>,
        computedAttributeAspects: com.google.common.collect.ImmutableMultimap<Aspect?, String?>,
        attributeName: String?,
        aspectOwningAttribute: AspectClass?,
        allFilteredAspects: com.google.common.collect.ImmutableList.Builder<Aspect?>
    ) {
        val aspectsNum: Int = aspectsPath.size
        val filteredAspectsPath: java.util.ArrayList<Aspect> = java.util.ArrayList<Aspect>()

        // `aspectsPath` is ordered bottom up. Iterating backwards traverses top-down so the following
        // loop captures aspects that propagate along the given attribute and all their transitive
        // requirements.
        for (i in aspectsNum - 1 downTo 0) {
            val aspect: Aspect = aspectsPath.get(i)
            if (aspect.getAspectClass().equals(aspectOwningAttribute)) {
                // Do not propagate over the aspect's own attributes.
                continue
            }
            if (propagatesTo<String?>(attributeName, aspect, computedAttributeAspects)
                || isAspectRequired(aspect, filteredAspectsPath)
            ) {
                // Add the aspect if it can propagate over this {@code attributeName} based on its
                // attr_aspects or it is required by an aspect already in the {@code filteredAspectsPath}.
                filteredAspectsPath.add(aspect)
            }
        }
        // Reverse filteredAspectsPath to return it to the same order as the input aspectsPath.
        Collections.reverse(filteredAspectsPath)
        allFilteredAspects.addAll(filteredAspectsPath)
    }

    /** Checks if `aspect` is required by any [Aspect] in `aspectsPath`.  */
    private fun isAspectRequired(aspect: Aspect?, aspectsPath: Iterable<Aspect>): Boolean {
        for (existingAspect in aspectsPath) {
            if (existingAspect.getDefinition().requires(aspect)) {
                return true
            }
        }
        return false
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun computeAttributeAspects(
        aspects: com.google.common.collect.ImmutableList<Aspect>,
        target: Target,
        attributeMap: ConfiguredAttributeMapper?,
        dependencyLabels: OrderedSetMultimap<DependencyKind?, Label?>?,
        eventHandler: ExtendedEventHandler?
    ): com.google.common.collect.ImmutableMultimap<Aspect?, String?> {
        val result: com.google.common.collect.ImmutableMultimap.Builder<Aspect?, String?> =
            com.google.common.collect.ImmutableMultimap.builder<Aspect?, String?>()
        for (aspect in aspects) {
            val attributeAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                aspect.getDefinition().getAttributeAspects()

            when (attributeAspects) {
                -> result.putAll(aspect, s.getList())
                -> result.putAll(
                    aspect,
                    s.computeList(
                        StarlarkAspectPropagationContext.Companion.createForPropagationEdges(
                            aspect, target as Rule, attributeMap, dependencyLabels
                        ),
                        eventHandler
                    )
                )

                else -> {}
            }
        }
        return result.build()
    }

    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    fun computeToolchainsAspects(
        aspects: com.google.common.collect.ImmutableList<Aspect>,
        target: Target,
        attributeMap: ConfiguredAttributeMapper?,
        dependencyLabels: OrderedSetMultimap<DependencyKind?, Label?>?,
        eventHandler: ExtendedEventHandler?
    ): com.google.common.collect.ImmutableMultimap<Aspect?, Label?> {
        val result: com.google.common.collect.ImmutableMultimap.Builder<Aspect?, Label?> =
            com.google.common.collect.ImmutableMultimap.builder<Aspect?, Label?>()
        for (aspect in aspects) {
            val toolchainsAspects: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                aspect.getDefinition().getToolchainsAspects()
            when (toolchainsAspects) {
                -> result.putAll(aspect, s.getList())
                -> result.putAll(
                    aspect,
                    s.computeList(
                        StarlarkAspectPropagationContext.Companion.createForPropagationEdges(
                            aspect, target as Rule, attributeMap, dependencyLabels
                        ),
                        eventHandler
                    )
                )

                else -> {}
            }
        }
        return result.build()
    }
}
