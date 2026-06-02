// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.bazel.bzlmod.modcommand

import com.google.common.collect.ImmutableSortedSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.*
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.collections.HashSet
import kotlin.collections.MutableSet

/** Outputs graph-based results of [ModExecutor] in JSON format.  */
class JsonOutputFormatter : OutputFormatters.OutputFormatter() {
    private var seenExtensions: MutableSet<ModuleExtensionId?>? = null

    public override fun output() {
        seenExtensions = HashSet<ModuleExtensionId?>()
        val root = printModule(ModuleKey.Companion.ROOT, null, IsExpanded.TRUE, IsIndirect.FALSE)
        root.addProperty("root", true)
        printer.println(
            GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
        )
    }

    fun printKey(key: ModuleKey): String {
        if (key == ModuleKey.Companion.ROOT) {
            return "<root>"
        }
        return key.toString()
    }

    // Helper to print module extensions similarly to printModule
    private fun printExtension(
        key: ModuleKey?, extensionId: ModuleExtensionId, unexpanded: Boolean
    ): JsonObject {
        val json = JsonObject()
        json.addProperty("key", extensionId.toString())
        json.addProperty("unexpanded", unexpanded)
        if (options.getExtensionInfo() == ExtensionShow.USAGES) {
            return json
        }
        val repoImports =
            ImmutableSortedSet.copyOf<String?>(extensionRepoImports.get(extensionId)!!.inverse().get(key))
        val usedRepos = JsonArray()
        for (usedRepo in repoImports) {
            usedRepos.add(usedRepo)
        }
        json.add("used_repos", usedRepos)

        if (unexpanded || options.getExtensionInfo() == ExtensionShow.REPOS) {
            return json
        }
        val unusedRepos =
            ImmutableSortedSet.copyOf<String?>(
                Sets.difference<String?>(
                    extensionRepos.get(extensionId), extensionRepoImports.get(extensionId)!!.keySet()
                )
            )
        val unusedReposJson = JsonArray()
        for (unusedRepo in unusedRepos) {
            unusedReposJson.add(unusedRepo)
        }
        json.add("unused_repos", unusedReposJson)
        return json
    }

    // Depth-first traversal to display modules (while explicitly detecting cycles)
    fun printModule(
        key: ModuleKey, parent: ModuleKey?, expanded: IsExpanded?, indirect: IsIndirect?
    ): JsonObject {
        val node = result.get(key)
        val module = depGraph.get(key)
        val json = JsonObject()
        json.addProperty("key", printKey(key))
        json.addProperty("name", module!!.name)
        json.addProperty("version", module.version.toString())
        val apparentName: String?
        if (parent != null) {
            // The apparent repository name under which parent refers to key.
            apparentName = depGraph.get(parent)!!.deps.inverse().get(key)
        } else {
            // The apparent repository name under which key refers to itself.
            apparentName = module.repoName
        }
        json.addProperty("apparentName", apparentName)

        if (indirect == IsIndirect.FALSE && options.getVerbose() && parent != null) {
            val explanation = getExtraResolutionExplanation(key, parent)
            if (explanation != null) {
                if (!module.isUsed()) {
                    json.addProperty("unused", true)
                    json.addProperty("resolvedVersion", explanation.changedVersion.toString())
                } else {
                    json.addProperty("originalVersion", explanation.changedVersion.toString())
                }
                json.addProperty("resolutionReason", explanation.changedVersion.toString())
                if (explanation.requestedByModules != null) {
                    val requestedBy = JsonArray()
                    explanation.requestedByModules!!.forEach(Consumer { k: ModuleKey? -> requestedBy.add(printKey(k!!)) })
                    json.add("resolvedRequestedBy", requestedBy)
                }
            }
        }

        if (expanded == IsExpanded.FALSE) {
            json.addProperty("unexpanded", true)
            return json
        }

        val deps = JsonArray()
        val indirectDeps = JsonArray()
        val cycles = JsonArray()
        for (e in node!!.getChildrenSortedByEdgeType()) {
            val childKey: ModuleKey = e.getKey()
            val childExpanded: IsExpanded? = e.getValue().isExpanded
            val childIndirect: IsIndirect? = e.getValue().isIndirect
            val childCycles: IsCycle? = e.getValue().isCycle
            if (childCycles == IsCycle.TRUE) {
                cycles.add(printModule(childKey, key, IsExpanded.FALSE, IsIndirect.FALSE))
            } else if (childIndirect == IsIndirect.TRUE) {
                indirectDeps.add(printModule(childKey, key, childExpanded, IsIndirect.TRUE))
            } else {
                deps.add(printModule(childKey, key, childExpanded, IsIndirect.FALSE))
            }
        }
        json.add("dependencies", deps)
        json.add("indirectDependencies", indirectDeps)
        json.add("cycles", cycles)

        if (options.getExtensionInfo() == ExtensionShow.HIDDEN) {
            return json
        }
        val extensionsUsed: ImmutableSortedSet<ModuleExtensionId> =
            extensionRepoImports.keySet().stream()
                .filter(Predicate { e: ModuleExtensionId? -> extensionRepoImports.get(e)!!.inverse().containsKey(key) })
                .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleExtensionId?>(ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR))
        val extensionUsages = JsonArray()
        for (extensionId in extensionsUsed) {
            val unexpandedExtension = !seenExtensions!!.add(extensionId)
            extensionUsages.add(printExtension(key, extensionId, unexpandedExtension))
        }
        json.add("extensionUsages", extensionUsages)

        return json
    }
}
