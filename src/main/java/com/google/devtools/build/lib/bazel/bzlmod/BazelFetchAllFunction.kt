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
//
package com.google.devtools.build.lib.bazel.bzlmod

import com.google.devtools.build.lib.bazel.bzlmod.BazelDepGraphValue
import com.google.devtools.build.lib.bazel.bzlmod.BazelFetchAllValue
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue
import com.google.devtools.build.skyframe.SkyFunction
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.SkyframeLookupResult
import java.util.stream.Collectors

/**
 * Gather and fetch all the repositories from MODULE.bazel resolution and extensions evaluation. If
 * this is fetch configure, only configure repos will be fetched and returned
 */
class BazelFetchAllFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        // Collect all the repos we want to fetch here

        val reposToFetch: MutableList<RepositoryName?>?

        // 1. Run resolution and collect the dependency graph repos except for main
        val depGraphValue: BazelDepGraphValue? = env.getValue(BazelDepGraphValue.Companion.KEY) as BazelDepGraphValue?
        if (depGraphValue == null) {
            return null
        }
        reposToFetch =
            depGraphValue.getCanonicalRepoNameLookup().keySet().stream()
                .filter(java.util.function.Predicate { repo: RepositoryName? -> !repo.isMain() })
                .collect(Collectors.toCollection(java.util.function.Supplier { ArrayList() }))

        // 2. Run every extension found in the modules & collect its generated repos
        val extensionIds: com.google.common.collect.ImmutableSet<ModuleExtensionId?> =
            depGraphValue.getExtensionUsagesTable().rowKeySet()
        val singleExtensionKeys: com.google.common.collect.ImmutableSet<SkyKey> =
            extensionIds.stream()
                .map<com.google.devtools.build.lib.bazel.bzlmod.SingleExtensionValue.Key?>(java.util.function.Function { id: ModuleExtensionId? ->
                    SingleExtensionValue.Companion.key(id)
                }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
        val singleExtensionValues: SkyframeLookupResult = env.getValuesAndExceptions(singleExtensionKeys)
        for (singleExtensionKey in singleExtensionKeys) {
            val singleExtensionValue: SingleExtensionValue? =
                singleExtensionValues.get(singleExtensionKey) as SingleExtensionValue?
            if (singleExtensionValue == null) {
                return null
            }
            reposToFetch!!.addAll(singleExtensionValue.canonicalRepoNameToInternalNames.keySet())
        }

        // 3. If this is fetch configure, get repo rules and only collect repos marked as configure
        val fetchConfigure = skyKey.argument() as Boolean
        if (fetchConfigure) {
            val repoDefinitionKeys: com.google.common.collect.ImmutableSet<SkyKey> =
                reposToFetch.stream()
                    .map<RepoDefinitionValue.Key?>(java.util.function.Function { repositoryName: RepositoryName? ->
                        RepoDefinitionValue.key(repositoryName)
                    }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
            reposToFetch!!.clear() // empty this list to only add configured repos
            val repoDefinitionValues: SkyframeLookupResult = env.getValuesAndExceptions(repoDefinitionKeys)
            for (repoRuleKey in repoDefinitionKeys) {
                val repoDefinitionValue: RepoDefinitionValue? =
                    repoDefinitionValues.get(repoRuleKey) as RepoDefinitionValue?
                if (repoDefinitionValue == null) {
                    return null
                }
                if (repoDefinitionValue is
                            && repoDefinition.repoRule.configure) {
                    reposToFetch.add(repoRuleKey.argument() as RepositoryName?)
                }
            }
        }

        // 4. Fetch all the collected repos
        val shouldVendor: MutableList<RepositoryName?> = java.util.ArrayList<RepositoryName?>()
        val repoDelegatorKeys: com.google.common.collect.ImmutableSet<SkyKey> =
            reposToFetch.stream()
                .map<RepositoryDirectoryValue.Key?>(java.util.function.Function { repository: RepositoryName? ->
                    RepositoryDirectoryValue.key(repository)
                }).collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
        val repoDirValues: SkyframeLookupResult = env.getValuesAndExceptions(repoDelegatorKeys)
        for (repoDelegatorKey in repoDelegatorKeys) {
            val repoDirValue: RepositoryDirectoryValue? =
                repoDirValues.get(repoDelegatorKey) as RepositoryDirectoryValue?
            if (repoDirValue == null) {
                return null
            }
            if (repoDirValue is RepositoryDirectoryValue.Success && !repoDirValue.excludeFromVendoring) {
                shouldVendor.add(repoDelegatorKey.argument() as RepositoryName?)
            }
        }

        return BazelFetchAllValue.Companion.create(
            com.google.common.collect.ImmutableList.copyOf<RepositoryName?>(
                shouldVendor
            )
        )
    }
}
