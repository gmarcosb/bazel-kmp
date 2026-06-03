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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Represents aspects that should be applied to a configured target as part of [Dependency].
 * 
 * 
 * One can consider the configured target graph as being a DAG in two dimensions: one is the DAG
 * analogous to the target graph and the other is a DAG between aspects applied to the same
 * configured target. This class represents the latter. The full "aspect dependency graph" is
 * computed when traversing the configured target graph. The analysis of the aspects attached to the
 * same configured target is done by simply unwrapping the graph of [AspectDeps] instances.
 * 
 * 
 * [Dependency] encapsulates all information that is needed to analyze an edge between an
 * AspectValue or a ConfiguredTargetValue and their direct dependencies, and [ ] represents an aspect-related part of this information.
 * 
 * 
 * Analysis arrives to a particular node in target graph with an ordered list of aspects that
 * need to be applied. Some of those aspects should visible to the node in question; some of them
 * are not directly visible, but are visible to other aspects, as specified by [ ][com.google.devtools.build.lib.packages.AspectDefinition.getRequiredProvidersForAspects].
 * 
 * 
 * As an example, of all these things in interplay, consider android_binary rule depending on
 * java_proto_library rule depending on proto_library rule; consider further that we analyze the
 * android_binary with some ide_info aspect:
 * 
 * <pre>
 * proto_library(name = "pl") + ide_info_aspect
 * ^
 * | [java_proto_aspect]
 * java_proto_library(name = "jpl") + ide_info_aspect
 * ^
 * | [DexArchiveAspect]
 * android_binary(name = "ab") + ide_info_aspect
</pre> * 
 * 
 * ide_info_aspect is interested in java_proto_aspect, but not in DexArchiveAspect.
 * 
 * 
 * Let's look is the [AspectCollection] for a Dependency representing a jpl->pl edge for
 * ide_info_aspect application to target `jpl`:
 * 
 * 
 *  * the full list of aspects is [java_proto_aspect, DexArchiveAspect, ide_info_aspect] in this
 * order (the order is determined by the order in which aspects originate on `ab->...->pl` path).
 *  * however, DexArchiveAspect is not visible to either ide_info_aspect or java_proto_aspect, so
 * the reduced list(and a result of [.getUsedAspects] ) will be [java_proto_aspect,
 * ide_info_aspect]
 *  * both java_proto_aspect and ide_info_aspect will be visible to `jpl + ide_info_aspect
` *  node: the former because java_proto_library originates java_proto_aspect, and the
 * aspect applied to the node sees the same dependencies; and the latter because the aspect
 * sees itself on all targets it propagates to. So [.getUsedAspects] will return both
 * of them.
 *  * Since ide_info_aspect declared its interest in java_proto_aspect and the latter comes
 * before it in the order, [AspectDeps] for ide_info_aspect will contain
 * java_proto_aspect (so the application of ide_info_aspect to `pl` target will see
 * java_proto_aspect as well).
 * 
 * 
 * More details on members of [AspectCollection] follow, as well as more examples of aspect
 * visibility rules.
 * 
 * 
 * [AspectDeps] is a class that represents an aspect and all aspects that are directly
 * visible to it.
 * 
 * 
 * [.getUsedAspects] return all aspects that should be applied to the target, in
 * topological order.
 * 
 * 
 * In the following scenario, consider rule r<sub>i</sub> sending an aspect a<sub>i</sub> to its
 * dependency:
 * 
 * <pre>
 * [r0]
 * ^
 * (a1) |
 * [r1]
 * (a2) |
 * [r2]
 * (a3) |
 * [r3]
</pre> * 
 * 
 * When a3 is propagated to target r0, the analysis arrives there with a path [a1, a2, a3]. Since we
 * analyse the propagation of aspect a3, the only visible aspect is a3.
 * 
 * 
 * Let's first assume that aspect a3 wants to see aspects a1 and a2, but aspects a1 and a2 are
 * not interested in each other (according to their [ ][com.google.devtools.build.lib.packages.AspectDefinition.getRequiredProvidersForAspects]).
 * 
 * 
 * Since a3 is interested in all aspects, the result of [.getUsedAspects] will be [a1,
 * a2, a3], and [AspectCollection] will be:
 * 
 * 
 *  * a3 -> [a1, a2]
 *  * a2 -> []
 *  * a1 -> []
 * 
 * 
 * 
 * Now what happens if a3 is interested in a2 but not a1, and a2 is interested in a1? Again, all
 * aspects are transitively interesting to a visible a3, so [.getUsedAspects] will be [a1,
 * a2, a3], but [AspectCollection] will now be:
 * 
 * 
 *  * a3 -> [a2]
 *  * a2 -> [a1]
 *  * a1 -> []
 * 
 * 
 * 
 * As a final example, what happens if a3 is interested in a1, and a1 is interested in a2, but a3
 * is not interested in a2? Now the result of [.getUsedAspects] will be [a1, a3]. a1 is
 * interested in a2, but a2 comes later in the path than a1, so a1 does not see it (a1 only started
 * propagating on r1 -> r0 edge, and there is now a2 originating on that path). And [ ] will now be:
 * 
 * 
 *  * a3 -> [a1]
 *  * a1 -> []
 * 
 * 
 * Note that is does not matter if a2 is interested in a1 or not - since no one after it in the path
 * is interested in it, a2 is filtered out.
 */
@Immutable
class AspectCollection private constructor(usedAspects: com.google.common.collect.ImmutableSet<AspectDeps>) {
    /** aspects that should be visible to a dependency  */
    private val usedAspects: com.google.common.collect.ImmutableSet<AspectDeps>

    init {
        this.usedAspects = usedAspects
    }

    fun getUsedAspects(): com.google.common.collect.ImmutableSet<AspectDeps> {
        return usedAspects
    }

    fun isEmpty(): Boolean {
        return usedAspects.isEmpty()
    }

    override fun toString(): String {
        return "AspectCollection{" + usedAspects + "}"
    }

    override fun hashCode(): Int {
        return usedAspects.hashCode()
    }

    override fun equals(obj: Any?): Boolean {
        if (obj !is AspectCollection) {
            return false
        }
        return this.usedAspects == obj.usedAspects
    }

    fun createAspectKeys(baseKey: ConfiguredTargetKey?): com.google.common.collect.ImmutableList<AspectKey?> {
        val descriptorToAspectKey: MutableMap<AspectDescriptor?, AspectKey?> = HashMap<AspectDescriptor?, AspectKey?>()
        for (aspectDeps in getUsedAspects()) {
            buildAspectKey(aspectDeps, descriptorToAspectKey, baseKey)
        }
        return com.google.common.collect.ImmutableList.copyOf<AspectKey?>(descriptorToAspectKey.values())
    }

    /**
     * Represents an aspect with all the aspects it depends on (within an [AspectCollection].
     * 
     * 
     * We preserve the order of aspects to correspond to the order originally specified in the call
     * to [AspectCollection.create], although that is not strictly needed semantically.
     * 
     * 
     * This data structure cannot be a simple list. Consider the case when four aspects [a1, a2,
     * a3, a4] are attached and a4 is interested in a3, a3 in a2 and a2 in a1.
     * 
     * 
     * In this case, when analyzing a3, only a2 will be in its direct dependencies (since we don't
     * want to merge in the dependencies of a1), but then a2 would have no way of knowing that a1 was
     * also propagated.
     * 
     * 
     * (a list of (dependent aspect, visible) pairs would work, though and the code would probably
     * be somewhat simpler)
     */
    class AspectDeps(aspect: AspectDescriptor?, usedAspects: com.google.common.collect.ImmutableList<AspectDeps>) {
        val aspect: AspectDescriptor?
        val usedAspects: com.google.common.collect.ImmutableList<AspectDeps>

        init {
            this.usedAspects = usedAspects
            this.aspect = aspect
            java.util.Objects.requireNonNull<Any?>(aspect, "aspect")
            java.util.Objects.requireNonNull<com.google.common.collect.ImmutableList<AspectDeps?>?>(
                usedAspects,
                "usedAspects"
            )
        }

        companion object {
            private fun create(
                aspect: AspectDescriptor?, usedAspects: com.google.common.collect.ImmutableList<AspectDeps>
            ): AspectDeps {
                return AspectDeps(aspect, usedAspects)
            }
        }
    }

    /**
     * Signals an inconsistency on aspect path: an aspect occurs twice on the path and the second
     * occurrence sees a different set of aspects.
     * 
     * 
     * [.getAspect] is the aspect occurring twice, and [.getPreviousAspect] is the
     * aspect that the second occurrence sees but the first does not.
     */
    class AspectCycleOnPathException(aspect: AspectDescriptor, previousAspect: AspectDescriptor) : java.lang.Exception(
        java.lang.String.format(
            "Aspect %s is applied twice, both before and after aspect %s",
            aspect.getDescription(), previousAspect.getDescription()
        )
    ) {
        private val aspect: AspectDescriptor
        private val previousAspect: AspectDescriptor

        init {
            this.aspect = aspect
            this.previousAspect = previousAspect
        }

        fun getAspect(): AspectDescriptor {
            return aspect
        }

        fun getPreviousAspect(): AspectDescriptor {
            return previousAspect
        }
    }

    companion object {
        /** The name of the native aspect that collects validation outputs.  */
        const val VALIDATION_ASPECT_NAME: String = "ValidateTarget"

        val EMPTY: AspectCollection = AspectCollection(com.google.common.collect.ImmutableSet.of<AspectDeps?>())

        /**
         * Creates an [AspectKey] for the given root aspect, `aspectDeps`.
         * 
         * 
         * Converts the DAG of [AspectDescriptor]s rooted at `aspectDeps` into an
         * isomorphic DAG of [AspectKey] with corresponding [AspectKey.getAspectDescriptor]
         * values. All resulting [AspectKey]s have [AspectKey.getBaseConfiguredTargetKey]
         * equal to `baseKey`.
         * 
         * 
         * As a side effect, `visited` is populated with all the DAG nodes with each map entry
         * value's descriptor matching the map entry key.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun buildAspectKey(
            aspectDeps: AspectDeps,
            visited: MutableMap<AspectDescriptor?, AspectKey?>,
            baseKey: ConfiguredTargetKey?
        ): AspectKey? {
            val aspect: AspectDescriptor? = aspectDeps.aspect
            var aspectKey: AspectKey? = visited.get(aspect)
            if (aspectKey != null) {
                return aspectKey
            }

            val usedAspects: com.google.common.collect.ImmutableList<AspectDeps> = aspectDeps.usedAspects
            val usedAspectKeys: com.google.common.collect.ImmutableList.Builder<AspectKey?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<AspectKey?>(usedAspects.size())
            for (usedAspect in usedAspects) {
                usedAspectKeys.add(buildAspectKey(usedAspect, visited, baseKey))
            }

            aspectKey = AspectKeyCreator.createAspectKey(aspect, usedAspectKeys.build(), baseKey)
            visited.put(aspect, aspectKey)
            return aspectKey
        }

        fun createForTests(vararg descriptors: AspectDescriptor?): AspectCollection {
            return Companion.createForTests(com.google.common.collect.ImmutableSet.copyOf<AspectDescriptor?>(descriptors))
        }

        fun createForTests(descriptors: com.google.common.collect.ImmutableSet<AspectDescriptor?>): AspectCollection {
            val depsBuilder: com.google.common.collect.ImmutableSet.Builder<AspectDeps?> =
                com.google.common.collect.ImmutableSet.builder<AspectDeps?>()
            for (descriptor in descriptors) {
                depsBuilder.add(
                    AspectDeps.Companion.create(
                        descriptor,
                        com.google.common.collect.ImmutableList.of<AspectDeps?>()
                    )
                )
            }
            return AspectCollection(depsBuilder.build())
        }

        /**
         * Creates an [AspectCollection] from an ordered list of aspects and a set of visible
         * aspects.
         * 
         * 
         * The order of aspects is reverse to the order in which they originated, with the earliest
         * originating occurring last in the list.
         */
        @Throws(AspectCycleOnPathException::class)
        fun create(aspectPath: Iterable<Aspect>): AspectCollection {
            val aspectMap: LinkedHashMap<AspectDescriptor?, Aspect> = deduplicateAspects(aspectPath)
            val deps: LinkedHashMap<AspectDescriptor?, java.util.ArrayList<AspectDescriptor?>> =
                LinkedHashMap<AspectDescriptor?, java.util.ArrayList<AspectDescriptor?>>()

            // Calculate all needed aspects. Already discovered aspects are in key set of deps.
            // 1) Start from the end of the path. The aspect only sees other aspects that are
            //    before it
            // 2) Otherwise, check whether 'aspect' is visible to or required by any already seen aspects.
            // If it is visible to 'depAspect' or explicitly required by it, add the 'aspect' to a list of
            // aspects visible to 'depAspect'.
            // At the end of this algorithm, key set of 'deps' contains the original aspect list in reverse
            // (since we iterate the original list in reverse).
            //
            // deps[aspect] contains all aspects that 'aspect' needs, in reverse order.
            for (aspect in com.google.common.collect.ImmutableList.copyOf<MutableMap.MutableEntry<AspectDescriptor?, Aspect?>?>(
                aspectMap.entrySet()
            ).reverse()) {
                for (depAspectDescriptor in deps.keySet()) {
                    val depAspect: Aspect = aspectMap.get(depAspectDescriptor)
                    // As any aspect can add validation outputs, the special validation aspect that collects
                    // their outputs has to depend on all aspects.
                    if (depAspect
                            .getDefinition()
                            .getRequiredProvidersForAspects()
                            .isSatisfiedBy(aspect.getValue().getDefinition().getAdvertisedProviders())
                        || depAspect.getDefinition().requires(aspect.getValue())
                        || depAspect.getAspectClass().getName().equals(VALIDATION_ASPECT_NAME)
                    ) {
                        deps.get(depAspectDescriptor).add(aspect.getKey())
                    }
                }

                deps.put(aspect.getKey(), java.util.ArrayList<AspectDescriptor?>())
            }

            // Calculate the path for every directly required aspect
            val aspectPaths: HashMap<AspectDescriptor?, AspectDeps?> = HashMap<AspectDescriptor?, AspectDeps?>()
            val result: com.google.common.collect.ImmutableSet.Builder<AspectDeps?> =
                com.google.common.collect.ImmutableSet.builder<AspectDeps?>()
            for (aspect in aspectMap.keySet()) {
                result.add(buildAspectDeps(aspect, aspectPaths, deps))
            }
            return AspectCollection(result.build())
        }

        /**
         * Deduplicate aspects in path.
         * 
         * @throws AspectCycleOnPathException if an aspect occurs twice on the path and
         * the second occurrence sees a different set of aspects.
         */
        @Throws(AspectCycleOnPathException::class)
        private fun deduplicateAspects(
            aspectPath: Iterable<Aspect>
        ): LinkedHashMap<AspectDescriptor?, Aspect> {
            val aspectMap: LinkedHashMap<AspectDescriptor?, Aspect> = LinkedHashMap<AspectDescriptor?, Aspect>()
            val seenAspects: java.util.ArrayList<Aspect> = java.util.ArrayList<Aspect>()
            for (aspect in aspectPath) {
                if (!aspectMap.containsKey(aspect.getDescriptor())) {
                    aspectMap.put(aspect.getDescriptor(), aspect)
                    seenAspects.add(aspect)
                } else {
                    validateDuplicateAspect(aspect, seenAspects)
                }
            }
            return aspectMap
        }

        /**
         * Detect inconsistent duplicate occurrence of an aspect on the path. There is a previous
         * occurrence of `aspect` in `seenAspects`.
         * 
         * 
         * If in between that previous occurrence and the newly discovered occurrence there is an
         * aspect that is visible to or required by `aspect`, then the second occurrence is
         * inconsistent - the set of aspects it sees is different from the first one.
         */
        @Throws(AspectCycleOnPathException::class)
        private fun validateDuplicateAspect(aspect: Aspect, seenAspects: java.util.ArrayList<Aspect>) {
            for (i in seenAspects.indices.reversed()) {
                val seenAspect: Aspect = seenAspects.get(i)
                if (aspect.getDescriptor().equals(seenAspect.getDescriptor())) {
                    // This is a previous occurrence of the same aspect.
                    return
                }

                if (aspect
                        .getDefinition()
                        .getRequiredProvidersForAspects()
                        .isSatisfiedBy(seenAspect.getDefinition().getAdvertisedProviders())
                    || aspect.getDefinition().requires(seenAspect)
                ) {
                    throw AspectCycleOnPathException(aspect.getDescriptor(), seenAspect.getDescriptor())
                }
            }
        }

        private fun buildAspectDeps(
            descriptor: AspectDescriptor?,
            aspectPaths: HashMap<AspectDescriptor?, AspectDeps?>,
            deps: LinkedHashMap<AspectDescriptor?, java.util.ArrayList<AspectDescriptor?>>
        ): AspectDeps? {
            if (aspectPaths.containsKey(descriptor)) {
                return aspectPaths.get(descriptor)
            }

            val aspectPathBuilder: com.google.common.collect.ImmutableList.Builder<AspectDeps?> =
                com.google.common.collect.ImmutableList.builder<AspectDeps?>()
            val depList: java.util.ArrayList<AspectDescriptor?> = deps.get(descriptor)

            // deps[aspect] contains all aspects visible to 'aspect' in reverse order.
            for (i in depList.indices.reversed()) {
                aspectPathBuilder.add(buildAspectDeps(depList.get(i), aspectPaths, deps))
            }
            val aspectPath = AspectDeps.Companion.create(descriptor, aspectPathBuilder.build())
            aspectPaths.put(descriptor, aspectPath)
            return aspectPath
        }
    }
}
