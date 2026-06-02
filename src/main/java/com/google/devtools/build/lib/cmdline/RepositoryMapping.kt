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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.cmdline.RepositoryName
import java.util.HashMap

/**
 * Stores the mapping from apparent repo name to canonical repo name, from the viewpoint of a
 * context repo.
 * 
 * 
 * This class must not implement [net.starlark.java.eval.StarlarkValue] since instances of
 * this class are used as markers by [ ].
 */
open class RepositoryMapping private constructor(
    entries: MutableMap<String?, RepositoryName?>,
    contextRepo: RepositoryName?
) {
    private val entries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>
    private val contextRepo: RepositoryName?

    init {
        this.entries = com.google.common.collect.ImmutableMap.copyOf<String?, RepositoryName?>(entries)
        this.contextRepo = contextRepo
    }

    /** Returns all the entries in this repo mapping.  */
    fun entries(): com.google.common.collect.ImmutableMap<String?, RepositoryName?> {
        return entries
    }

    /**
     * The context repo of this repository mapping. It is for providing useful debug information when
     * repository mapping fails due to enforcing strict dependency.
     */
    fun contextRepo(): RepositoryName? {
        return contextRepo
    }

    override fun equals(o: Any?): Boolean {
        return this === o
                || (o is RepositoryMapping
                && com.google.common.base.Objects.equal(entries, o.entries)
                && com.google.common.base.Objects.equal(contextRepo, o.contextRepo))
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(entries, contextRepo)
    }

    override fun toString(): String {
        return java.lang.String.format("RepositoryMapping{entries=%s, contextRepo=%s}", entries, contextRepo)
    }

    /**
     * Create a new [RepositoryMapping] instance based on existing repo mappings and given
     * additional mappings. If there are conflicts, existing mappings will take precedence.
     */
    fun withAdditionalMappings(
        additionalMappings: com.google.common.collect.ImmutableMap<String?, RepositoryName?>
    ): RepositoryMapping {
        return com.google.devtools.build.lib.cmdline.RepositoryMapping(
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, RepositoryName?>(
                entries().size() + additionalMappings.size()
            )
                .putAll(additionalMappings)
                .putAll(entries())
                .buildKeepingLast(),
            contextRepo()
        )
    }

    /**
     * Create a new [RepositoryMapping] instance based on existing repo mappings and given
     * additional mappings. If there are conflicts, existing mappings will take precedence. The owner
     * repo of the given additional mappings is ignored.
     */
    fun withAdditionalMappings(additionalMappings: RepositoryMapping?): RepositoryMapping {
        return withAdditionalMappings(
            if (additionalMappings == null) com.google.common.collect.ImmutableMap.of<String?, RepositoryName?>() else additionalMappings.entries()
        )
    }

    /**
     * Returns the canonical repository name associated with the given apparent repo name. The
     * provided apparent repo name is assumed to be valid.
     */
    fun get(preMappingName: String): RepositoryName {
        val canonicalRepoName: RepositoryName? = entries().get(preMappingName)
        if (canonicalRepoName != null) {
            return canonicalRepoName
        }
        return RepositoryName.Companion.createUnvalidated(preMappingName)
            .toNonVisible(
                contextRepo(),
                net.starlark.java.spelling.SpellChecker.didYouMean(preMappingName, entries().keySet())
            )
    }

    /**
     * Returns the first apparent name in this mapping that maps to the given canonical name, if any.
     */
    open fun getInverse(postMappingName: RepositoryName?): java.util.Optional<String?> {
        return entries().entrySet().stream()
            .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, RepositoryName?>? -> e.getValue() == postMappingName })
            .map<String?>(java.util.function.Function { obj: MutableMap.MutableEntry<String?, RepositoryName?>? -> obj.getKey() })
            .findFirst()
    }

    /**
     * Returns a new [RepositoryMapping] instance with identical contents, except that the
     * inverse mapping is cached, causing [.getInverse] to be much more efficient. This is
     * particularly important for the main repo mapping, as it's often used to generate display-form
     * labels ([Label.getDisplayForm]).
     */
    fun withCachedInverseMap(): RepositoryMapping {
        val inverse: HashMap<RepositoryName?, String?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<RepositoryName?, String?>(entries.size())
        for (entry in entries.entrySet()) {
            inverse.putIfAbsent(entry.getValue(), entry.getKey())
        }
        val inverseCopy: com.google.common.collect.ImmutableMap<RepositoryName?, String?> =
            com.google.common.collect.ImmutableMap.copyOf<RepositoryName?, String?>(inverse)
        return object : RepositoryMapping(entries, contextRepo) {
            override fun getInverse(postMappingName: RepositoryName?): java.util.Optional<String?> {
                return java.util.Optional.ofNullable<String?>(inverseCopy.get(postMappingName))
            }
        }
    }

    companion object {
        /* An empty repo mapping with the main repo as the context repo. */
        @kotlin.jvm.JvmField
        val EMPTY: RepositoryMapping = com.google.devtools.build.lib.cmdline.RepositoryMapping.Companion.create(
            com.google.common.collect.ImmutableMap.of<String?, RepositoryName?>(
                "",
                RepositoryName.Companion.MAIN
            ), RepositoryName.Companion.MAIN
        )

        fun create(
            entries: com.google.common.collect.ImmutableMap<String?, RepositoryName?>?, contextRepo: RepositoryName?
        ): RepositoryMapping {
            return com.google.devtools.build.lib.cmdline.RepositoryMapping(
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableMap<String?, RepositoryName?>?>(
                    entries
                ), com.google.common.base.Preconditions.checkNotNull<RepositoryName?>(contextRepo)
            )
        }
    }
}
