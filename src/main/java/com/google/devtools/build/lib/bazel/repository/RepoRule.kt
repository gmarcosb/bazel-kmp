// Copyright 2025 The Bazel Authors. All rights reserved.
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
//
package com.google.devtools.build.lib.bazel.repository

import com.google.devtools.build.lib.bazel.bzlmod.AttributeValues

/**
 * Represents a fully-loaded repo rule, ready to be run.
 * 
 * @param recordedRepoMappingEntries The repo mapping entries recorded during the loading of the
 * repo rule's impl function.
 * @param environ The predeclared set of environment variables this repo rule depends on. Note that
 * using `repository_ctx.getenv` is preferred.
 */
@AutoCodec
class RepoRule(
    id: RepoRuleId?,
    transitiveBzlDigest: ByteString?,
    recordedRepoMappingEntries: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>?,
    impl: net.starlark.java.eval.StarlarkCallable?,
    doc: java.util.Optional<String?>?,
    attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>?,
    attributeIndices: com.google.common.collect.ImmutableMap<String?, Int?>?,
    local: Boolean,
    configure: Boolean,
    remotable: Boolean,
    environ: com.google.common.collect.ImmutableSet<String?>?
) {
    /** Supplies a [RepoRule] instance.  */
    interface Supplier {
        val repoRule: RepoRule?
    }

    /**
     * Instantiates a repo.
     * 
     * @param kwargs The attributes supplied to the repo rule invocation. Note that the `name`
     * faux-attribute isn't actually stored as an attribute, but `kwargs` can contain it as
     * per repo rule invocation convention.
     * @param repoMappingWhere See [AttributeUtils.typeCheckAttrValues]
     */
    @Throws(ExternalDepsException::class)
    fun instantiate(
        kwargs: MutableMap<String?, Any?>,
        callStack: com.google.common.collect.ImmutableList<net.starlark.java.eval.StarlarkThread.CallStackEntry?>,
        labelConverter: LabelConverter?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        repoMappingWhere: String?
    ): RepoSpec {
        try {
            val maybeName: String? =
                if (kwargs.get("name") is String) " with name '%s'".formatted(name) else ""
            val attrValues: com.google.common.collect.ImmutableList<Any?> =
                AttributeUtils.typeCheckAttrValues(
                    attributes,
                    attributeIndices,
                    com.google.common.collect.Maps.filterKeys<String?, Any?>(
                        kwargs,
                        com.google.common.base.Predicate { k: String? -> !RESERVED_ATTRIBUTES.contains(k) }),
                    labelConverter,
                    ExternalDeps.Code.EXTENSION_EVAL_ERROR,
                    callStack,
                    "call to '%s' repo rule%s".formatted(id.ruleName(), maybeName),
                    repoMappingWhere
                )
            val attrDict: net.starlark.java.eval.Dict.Builder<String?, Any?> =
                net.starlark.java.eval.Dict.builder<String?, Any?>()
            for (kwarg in kwargs.entrySet()) {
                // Only store explicitly-specified attributes.
                if (!RESERVED_ATTRIBUTES.contains(kwarg.getKey())
                    && !net.starlark.java.eval.Starlark.isNullOrNone(kwarg.getValue())
                ) {
                    attrDict.put(kwarg.getKey(), attrValues.get(attributeIndices.get(kwarg.getKey())))
                }
            }
            return RepoSpec(id, AttributeValues.create(attrDict.buildImmutable()))
        } catch (e: ExternalDepsException) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.error(
                    callStack.getLast().location,
                    e.getMessage()
                )
            )
            throw e
        }
    }

    /** Builder type for [RepoRule].  */
    @AutoBuilder
    abstract class Builder {
        var attrNames: MutableSet<String?> = HashSet<String?>()

        abstract fun idBuilder(): RepoRuleId.Builder?

        abstract fun transitiveBzlDigest(value: ByteString?): Builder?

        abstract fun recordedRepoMappingEntries(
            value: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>?
        ): Builder?

        abstract fun impl(value: net.starlark.java.eval.StarlarkCallable?): Builder?

        abstract fun doc(value: java.util.Optional<String?>?): Builder?

        abstract fun attributesBuilder(): com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.packages.Attribute?>?

        abstract fun attributeIndicesBuilder(): com.google.common.collect.ImmutableMap.Builder<String?, Int?>?

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAttribute(attribute: com.google.devtools.build.lib.packages.Attribute): Builder {
            attributesBuilder().add(attribute)
            attributeIndicesBuilder().put(attribute.getPublicName(), attrNames.size())
            attrNames.add(attribute.getPublicName())
            return this
        }

        fun hasAttribute(attrName: String?): Boolean {
            return attrNames.contains(attrName) || RESERVED_ATTRIBUTES.contains(attrName)
        }

        abstract fun local(value: Boolean): Builder?

        abstract fun configure(value: Boolean): Builder?

        abstract fun remotable(value: Boolean): Builder?

        abstract fun environ(value: com.google.common.collect.ImmutableSet<String?>?): Builder?

        abstract fun build(): RepoRule?
    }

    val id: RepoRuleId?
    val transitiveBzlDigest: ByteString?
    val recordedRepoMappingEntries: com.google.common.collect.ImmutableTable<RepositoryName?, String?, RepositoryName?>?
    val impl: net.starlark.java.eval.StarlarkCallable?
    val doc: java.util.Optional<String?>?
    val attributes: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.packages.Attribute?>?
    val attributeIndices: com.google.common.collect.ImmutableMap<String?, Int?>?
    val local: Boolean
    val configure: Boolean
    val remotable: Boolean
    val environ: com.google.common.collect.ImmutableSet<String?>?

    init {
        this.id = id
        this.transitiveBzlDigest = transitiveBzlDigest
        this.recordedRepoMappingEntries = recordedRepoMappingEntries
        this.impl = impl
        this.doc = doc
        this.attributes = attributes
        this.attributeIndices = attributeIndices
        this.local = local
        this.configure = configure
        this.remotable = remotable
        this.environ = environ
    }

    companion object {
        /** A list of reserved attribute names.  */
        private val RESERVED_ATTRIBUTES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("name")

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return AutoBuilder_RepoRule_Builder()
        }
    }
}
