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
package com.google.devtools.build.lib.analysis.config.transitions

import com.google.devtools.build.lib.analysis.config.BuildOptions

/**
 * A transition that runs two other transitions independently on the same input and compares their
 * results. For Bazel developer debugging.
 */
class ComparingTransition(
    activeTransition: ConfigurationTransition,
    activeTransitionDesc: String?,
    altTransition: ConfigurationTransition,
    altTransitionDesc: String?,
    runBoth: java.util.function.Predicate<BuildOptions?>
) : PatchTransition {
    private val activeTransition: ConfigurationTransition
    private val activeTransitionDesc: String?

    private val altTransition: ConfigurationTransition
    private val altTransitionDesc: String?

    private val runBoth: java.util.function.Predicate<BuildOptions?>

    /**
     * Creates a transition that applies `activeTransition` and possibly compares with `alternativeTransition`.
     * 
     * @param activeTransition the transition this one delegates to. If `runBoth` is false, the
     * comparing transition is a pure alias of this.
     * @param activeTransitionDesc user-friendly description of the active transition
     * @param altTransition An alternative transition to compare against `activeTransition`..
     * This only runs when `runBoth` is true. In that case, both this and `activeTransition` run independently and their results are compared.
     * @param altTransitionDesc user-friendly description of the alternative transition
     * @param runBoth User-supplied predicate that determines if this should run in comparison mode.
     * This can be used to toggle debug output with a build flag.
     */
    init {
        this.activeTransition = activeTransition
        this.activeTransitionDesc = activeTransitionDesc
        this.altTransition = altTransition
        this.altTransitionDesc = altTransitionDesc
        this.runBoth = runBoth
    }

    @Throws(java.lang.InterruptedException::class)
    override fun patch(
        buildOptions: BuildOptionsView,
        eventHandler: com.google.devtools.build.lib.events.EventHandler
    ): BuildOptions? {
        val activeOptions: MutableMap.MutableEntry<String?, BuildOptions?>? =
            com.google.common.collect.Iterables.getOnlyElement<MutableMap.MutableEntry<String?, BuildOptions?>?>(
                activeTransition.apply(buildOptions, eventHandler).entries
            )
        if (activeOptions!!.key == "error") {
            eventHandler.handle(com.google.devtools.build.lib.events.Event.error(activeTransitionDesc + " transition failed"))
        } else if (runBoth.test(buildOptions.underlying())) {
            compare(
                buildOptions.underlying(),
                activeOptions.value,
                com.google.common.collect.Iterables.getOnlyElement<BuildOptions?>(
                    altTransition.apply(
                        buildOptions,
                        eventHandler
                    ).values
                ),
                eventHandler
            )
        }
        return activeOptions.value
    }

    /** Shows differences between two [BuildOptions] as debugging terminal output.  */
    private fun compare(
        fromOptions: BuildOptions,
        activeOptions: BuildOptions,
        altOptions: BuildOptions,
        eventHandler: com.google.devtools.build.lib.events.EventHandler
    ) {
        // Log fragments that only exist in one output.
        val onlyInActive: com.google.common.collect.Sets.SetView<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.Sets.difference<E?>(
                activeOptions.getFragmentClasses(),
                altOptions.getFragmentClasses()
            )
        val onlyInAlt: com.google.common.collect.Sets.SetView<java.lang.Class<out FragmentOptions?>?> =
            com.google.common.collect.Sets.difference<E?>(
                altOptions.getFragmentClasses(),
                activeOptions.getFragmentClasses()
            )
        val s: java.util.StringJoiner = java.util.StringJoiner("\n")
        s.add("------------------------------------------")
        s.add(String.format("ComparingTransition(%s, %s):", activeTransitionDesc, altTransitionDesc))
        s.add(
            java.lang.String.format(
                "- from: %s, %s to: %s, %s to: %s",
                fromOptions.shortId(),
                activeTransitionDesc,
                activeOptions.shortId(),
                altTransitionDesc,
                altOptions.shortId()
            )
        )
        s.add(
            String.format(
                "- unique fragments in %s mode: %s",
                activeTransitionDesc,
                if (onlyInActive.isEmpty())
                    "none"
                else
                    onlyInActive.stream()
                        .map<String?> { c: java.lang.Class<out FragmentOptions?>? -> prettyClassName(c) }
                        .collect(Collectors.joining())))
        s.add(
            String.format(
                "- unique fragments in %s mode: %s",
                altTransitionDesc,
                if (onlyInAlt.isEmpty())
                    "none"
                else
                    onlyInAlt.stream().map<String?> { c: java.lang.Class<out FragmentOptions?>? -> prettyClassName(c) }
                        .collect(Collectors.joining())))

        val activeMap: com.google.common.collect.ImmutableMap<String?, String?> = serialize(activeOptions)
        val altMap: com.google.common.collect.ImmutableMap<String?, String?> = serialize(altOptions)

        // For every option, compute { optionName: <activeValue, altValue> }.
        val combinedMap: TreeMap<String?, com.google.devtools.build.lib.util.Pair<String?, String?>?> =
            TreeMap<String?, com.google.devtools.build.lib.util.Pair<String?, String?>?>()
        for (o in activeMap.entries) {
            combinedMap.put(
                o.key,
                com.google.devtools.build.lib.util.Pair.of<String?, String?>(o.value, "DOES NOT EXIST")
            )
        }
        for (o in altMap.entries) {
            if (!combinedMap.containsKey(o.key)) {
                combinedMap.put(
                    o.key,
                    com.google.devtools.build.lib.util.Pair.of<String?, String?>("DOES NOT EXIST", o.value)
                )
            } else {
                val newMapValue: String? = combinedMap.get(o.key).getFirst()
                combinedMap.put(
                    o.key,
                    com.google.devtools.build.lib.util.Pair.of<String?, String?>(newMapValue, o.value)
                )
            }
        }

        // Print options with different values.
        val s2: java.util.StringJoiner = java.util.StringJoiner("\n")
        var diffs = 0
        for (combined in combinedMap.entries) {
            val option: String? = combined.key
            val activeVal: String? = combined.value.getFirst()
            val altVal: String? = combined.value.getSecond()
            if (activeVal == "DOES NOT EXIST") {
                s2.add(String.format("   only in %s mode: --%s=%s", altTransitionDesc, option, altVal))
                diffs++
            } else if (altVal == "DOES NOT EXIST") {
                s2.add(
                    String.format("   only in %s mode: --%s=%s", activeTransitionDesc, option, activeVal)
                )
                diffs++
            } else if (activeVal != altVal) {
                s2.add(
                    String.format(
                        "   --%s: %s mode=%s, %s mode=%s",
                        option, activeTransitionDesc, activeVal, altTransitionDesc, altVal
                    )
                )
                diffs++
            }
        }

        // Summarize diff count both before and after the full diff for easy reading.
        s.add(String.format("- total option differences: %d", diffs))
        s.add(s2.toString())
        if (diffs > 1) {
            s.add(String.format("- total option differences: %d", diffs))
        }
        eventHandler.handle(com.google.devtools.build.lib.events.Event.info(s.toString()))
    }

    override fun reasonForOverride(): String {
        return "Adds ability to compare difference between native vs. Starlark transitions"
    }

    /**
     * Implement [ConfigurationTransition.visit]} so [ ] kicks in if this calls a
     * Starlark transition.
     * 
     * 
     * Reason: [com.google.devtools.build.lib.analysis.config.StarlarkTransitionCache] is
     * responsible for Starlark transition applications. Let it decisively own that responsibility vs.
     * writing a new ad hoc cache playing the same role for special corner cases. This keeps the
     * overall logic clearer.
     * 
     * 
     * If both the delegate transitions are native, we need some other way to avoid redundant
     * applications at a possible performance cost. In the long term if we eliminate native
     * transitions, we can eliminate this concern.
     */
    @Throws(E::class)
    override fun <E : java.lang.Exception?> visit(visitor: com.google.devtools.build.lib.analysis.config.transitions.ConfigurationTransition.Visitor<E?>) {
        this.activeTransition.visit<E?>(visitor)
        this.altTransition.visit<E?>(visitor)
    }

    companion object {
        private fun prettyClassName(clazz: java.lang.Class<*>): String {
            val full: String = clazz.getName()
            val dot: Int = full.lastIndexOf(".")
            return if (dot == -1) full else full.substring(dot + 1)
        }

        /**
         * Maps a [BuildOptions] to a user-friendly key=value string map.
         * 
         * 
         * Splits each `--define`, `--features` and `--host_features` into its own
         * key=value pair.
         */
        private fun serialize(o: BuildOptions): com.google.common.collect.ImmutableMap<String?, String?> {
            val ans: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            for (f in o.getNativeOptions()) {
                for (op in f.asMap().entrySet()) {
                    if (op.key == "define") {
                        ans.putAll(
                            serializeUserDefinedOption(
                                o.get(CoreOptions::class.java).getCommandLineBuildVariables().stream()
                                    .map({ d -> java.util.Map.entry<K?, V?>(d.getKey(), d.getValue()) })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()),
                                "define"
                            )
                        )
                    } else if (op.key == "features") {
                        ans.putAll(
                            serializeUserDefinedOption(
                                o.get(CoreOptions::class.java).getDefaultFeatures().stream()
                                    .map({ d -> java.util.Map.entry<K?, V?>(d, "") })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()),
                                "feature"
                            )
                        )
                    } else if (op.key == "host_features") {
                        ans.putAll(
                            serializeUserDefinedOption(
                                o.get(CoreOptions::class.java).getHostFeatures().stream()
                                    .map({ d -> java.util.Map.entry<K?, V?>(d, "") })
                                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()),
                                "host feature"
                            )
                        )
                    } else {
                        ans.put(
                            prettyClassName(f.getOptionsClass()) + " " + op.key,
                            op.value.toString()
                        )
                    }
                }
            }
            ans.putAll(
                serializeUserDefinedOption(
                    o.getStarlarkOptions().entrySet().stream()
                        .map({ d -> java.util.Map.entry<K?, V?>(d.getKey().toString(), d.getValue().toString()) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()),
                    ""
                )
            )
            return ans.buildOrThrow()
        }

        /**
         * Expands a [BuildOptions] native flag that represents a set of user-defined options.
         * 
         * 
         * For example: `--define` or `--features`.
         */
        private fun serializeUserDefinedOption(
            userDefinedOption: Iterable<MutableMap.MutableEntry<String?, String?>>, desc: String?
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val ans: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            var index = 0
            for (entry in userDefinedOption) {
                ans.put(
                    String.format("user-defined %s %s (index %d)", desc, entry.key, index),
                    entry.value
                )
                index++
            }
            return ans.buildOrThrow()
        }
    }
}
