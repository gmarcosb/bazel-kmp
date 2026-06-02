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
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.repository.RepoDefinition
import java.util.stream.Collectors

/**
 * Event indicating that a repository rule was executed, together with the return value of the rule.
 */
class RepositoryResolvedEvent(repoDefinition: RepoDefinition, result: MutableMap<String?, Any>) {
    /**
     * True, if the return value of the repository rule contained new information with respect to the
     * way it was called.
     */
    val isNewInformationReturned: Boolean

    /** Message describing the event  */
    val message: String

    init {
        if (result.isEmpty()) {
            // Repo claims to be already reproducible, so wants to be called as is.
            this.isNewInformationReturned = false
            this.message = "Repo '" + repoDefinition.name + "' finished fetching."
        } else {
            // Repo claims that the returned (probably changed) arguments are a reproducible
            // version of itself. Diff them and report the changes, if any.
            val modifiedAttributes: java.util.stream.Stream<MutableMap.MutableEntry<String?, java.util.Optional<Any?>?>?>? =
                repoDefinition.getFieldNames()
                    .stream() // The "name" attribute is confusing as the value specified by the user is transformed
                    // to the canonical name for repository_ctx.attr.name. Since the name should never
                    // affect reproducibility, ignore it.
                    .filter(java.util.function.Predicate { name: String? -> name != "name" }) // Filter out implicit attributes, which can't be modified by the user.
                    .filter(java.util.function.Predicate { name: String? -> !name.startsWith("_") })
                    .map<MutableMap.MutableEntry<String?, java.util.Optional<Any?>?>?>(
                        java.util.function.Function { name: String? ->
                            var defaultValue: Any? =
                                repoDefinition
                                    .repoRule
                                    .attributes
                                    .get(repoDefinition.repoRule.attributeIndices.get(name))
                                    .getDefaultValueUnchecked()
                            // Label attributes report a default of null rather than None.
                            if (defaultValue == null) {
                                defaultValue = net.starlark.java.eval.Starlark.NONE
                            }
                            val currentValue: Any? = repoDefinition.getValue(name)
                            val newValue = result.getOrDefault(name, defaultValue!!)
                            if (newValue == currentValue) {
                                return@map null
                            }
                            java.util.Map.entry<String?, java.util.Optional<Any?>?>(
                                name,
                                if (newValue == defaultValue) java.util.Optional.empty<Any?>() else java.util.Optional.of<Any?>(
                                    newValue
                                )
                            )
                        })
                    .filter(java.util.function.Predicate { obj: MutableMap.MutableEntry<String?, java.util.Optional<Any?>?>? ->
                        java.util.Objects.nonNull(
                            obj
                        )
                    })
            TODO(
                """
                |Cannot convert element
                |With text:
                |collect(<Map.Entry<String, Optional<Object>>, String, Optional<Object>>toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)
                """.trimMargin()
            )

            if (modifiedAttributes.isEmpty()) {
                this.isNewInformationReturned = false
                this.message = "Repo '" + repoDefinition.name + "' finished fetching."
            } else {
                this.isNewInformationReturned = true
                val modifiedToNonDefault: MutableMap<String?, java.util.Optional<Any?>?> =
                    com.google.common.collect.Maps.< String, Optional<Object>>filterValues<kotlin.String?, java.util.Optional<kotlin.Any?>?>(modifiedAttributes, com.google.common.base.Predicate { obj: java.util.Optional<kotlin.Any?>? -> obj.isPresent() })
                val dropped: MutableSet<String?> =
                    com.google.common.collect.Maps.< String, Optional<Object>>filterValues<kotlin.String?, java.util.Optional<kotlin.Any?>?>(modifiedAttributes, com.google.common.base.Predicate { obj: java.util.Optional<kotlin.Any?>? -> obj.isEmpty() }).keySet()
                if (modifiedToNonDefault.isEmpty()) {
                    this.message =
                        ("Repo '"
                                + repoDefinition.name
                                + "' indicated that a canonical reproducible form can be obtained by"
                                + " dropping arguments "
                                + net.starlark.java.eval.Starlark.repr(
                            dropped,
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT
                        ))
                } else if (dropped.isEmpty()) {
                    this.message =
                        ("Repo '"
                                + repoDefinition.name
                                + "' indicated that a canonical reproducible form can be obtained by"
                                + " modifying arguments "
                                + representModifications(
                            com.google.common.collect.Maps.transformValues<String?, java.util.Optional<Any?>?, Any?>(
                                modifiedToNonDefault,
                                com.google.common.base.Function { obj: java.util.Optional<kotlin.Any?>? -> obj.get() })
                        ))
                } else {
                    this.message =
                        ("Repo '"
                                + repoDefinition.name
                                + "' indicated that a canonical reproducible form can be obtained by"
                                + " modifying arguments "
                                + representModifications(
                            com.google.common.collect.Maps.transformValues<String?, java.util.Optional<Any?>?, Any?>(
                                modifiedToNonDefault,
                                com.google.common.base.Function { obj: java.util.Optional<kotlin.Any?>? -> obj.get() })
                        )
                                + " and dropping "
                                + net.starlark.java.eval.Starlark.repr(
                            dropped,
                            net.starlark.java.eval.StarlarkSemantics.DEFAULT
                        ))
                }
            }
        }
    }

    companion object {
        /** Returns an unstructured message explaining the origin of this rule.  */
        fun getRuleDefinitionInformation(repoDefinition: RepoDefinition): String? {
            // We used to output a call stack for repos defined in WORKSPACE, but in Bzlmod we always get an
            // empty stack -- for repos backing modules there's no call stack; for extension-generated
            // repos, the call stack is lost during the roundtrip to the lockfile.
            // TODO: store the call stack in the lockfile and output it here?
            return "Repo %s defined by rule %s in %s"
                .formatted(
                    repoDefinition.name,
                    repoDefinition.repoRule.id.ruleName(),
                    repoDefinition.repoRule.id.bzlFileLabel().getUnambiguousCanonicalForm()
                )
        }

        fun representModifications(changes: MutableMap<String?, Any?>): String? {
            return changes.entrySet().stream()
                .map<String?>(
                    java.util.function.Function { entry: MutableMap.MutableEntry<String?, Any?>? ->
                        "%s = %s"
                            .formatted(
                                entry.getKey(),
                                net.starlark.java.eval.Starlark.repr(
                                    entry.getValue(),
                                    net.starlark.java.eval.StarlarkSemantics.DEFAULT
                                )
                            )
                    })
                .collect(Collectors.joining(", "))
        }
    }
}
