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
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.Version
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow
import java.lang.String
import java.util.*
import java.util.function.Predicate

/**
 * Outputs graph-based results of [ModExecutor] in the Graphviz *dot* format which can be
 * further pipelined to create an image graph visualization.
 */
class GraphvizOutputFormatter : OutputFormatters.OutputFormatter() {
    private var str: StringBuilder? = null

    public override fun output() {
        str = StringBuilder()
        str!!.append("digraph mygraph {\n")
            .append("  ")
            .append("node [ shape=box ]\n")
            .append("  ")
            .append("edge [ fontsize=8 ]\n")
        val seen: MutableSet<ModuleKey?> = HashSet<ModuleKey?>()
        val seenExtensions: MutableSet<ModuleExtensionId?> = HashSet<ModuleExtensionId?>()
        val toVisit: Deque<ModuleKey> = ArrayDeque<ModuleKey>()
        seen.add(ModuleKey.Companion.ROOT)
        toVisit.add(ModuleKey.Companion.ROOT)

        while (!toVisit.isEmpty()) {
            val key = toVisit.pop()
            val module = Objects.requireNonNull<AugmentedModule>(depGraph.get(key))
            val node = Objects.requireNonNull<ResultNode>(result.get(key))
            val sourceId = toId(key)

            if (key == ModuleKey.Companion.ROOT) {
                val rootLabel = String.format("<root> (%s@%s)", module.name, module.version)
                str!!.append(String.format("  \"<root>\" [ label=\"%s\" ]\n", rootLabel))
            } else if (node.isTarget() || !module.isUsed()) {
                val shapeString = if (node.isTarget()) "diamond" else "box"
                val styleString = if (module.isUsed()) "solid" else "dotted"
                str!!.append(
                    String.format("  %s [ shape=%s style=%s ]\n", toId(key), shapeString, styleString)
                )
            }

            if (options.getExtensionInfo() != ExtensionShow.HIDDEN) {
                val extensionsUsed: ImmutableSortedSet<ModuleExtensionId> =
                    extensionRepoImports.keySet().stream()
                        .filter(Predicate { e: ModuleExtensionId? ->
                            extensionRepoImports.get(e)!!.inverse().containsKey(key)
                        })
                        .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleExtensionId?>(ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR))
                for (extensionId in extensionsUsed) {
                    if (options.getExtensionInfo() == ExtensionShow.USAGES) {
                        str!!.append(String.format("  %s -> \"%s\"\n", toId(key), toId(extensionId)))
                        continue
                    }
                    if (seenExtensions.add(extensionId)) {
                        printExtension(extensionId)
                    }
                    val repoImports =
                        ImmutableSortedSet.copyOf<kotlin.String?>(
                            extensionRepoImports.get(extensionId)!!.inverse().get(key)
                        )
                    for (repo in repoImports) {
                        str!!.append(String.format("  %s -> %s\n", toId(key), toId(extensionId, repo)))
                    }
                }
            }
            for (e in node.getChildrenSortedByKey()) {
                val childKey: ModuleKey = e.getKey()
                val childIndirect: IsIndirect? = e.getValue().isIndirect
                val childId = toId(childKey)
                if (childIndirect == IsIndirect.FALSE) {
                    val reasonLabel = getReasonLabel(childKey, key)
                    str!!.append(String.format("  %s -> %s [ %s ]\n", sourceId, childId, reasonLabel))
                } else {
                    str!!.append(String.format("  %s -> %s [ style=dashed ]\n", sourceId, childId))
                }
                if (seen.add(childKey)) {
                    toVisit.add(childKey)
                }
            }
        }
        str!!.append("}")
        printer.println(str)
        printer.flush()
    }

    private fun toId(key: ModuleKey): kotlin.String? {
        if (key == ModuleKey.Companion.ROOT) {
            return "\"<root>\""
        }
        return String.format(
            "\"%s@%s\"", key.name, if (key.version == Version.Companion.EMPTY) "_" else key.version
        )
    }

    private fun toId(id: ModuleExtensionId): kotlin.String? {
        return id.toString()
    }

    private fun toId(id: ModuleExtensionId, repo: kotlin.String?): kotlin.String? {
        return String.format("\"%s%%%s\"", toId(id), repo)
    }

    private fun printExtension(id: ModuleExtensionId) {
        str!!.append(String.format("  subgraph \"cluster_%s\" {\n", toId(id)))
        str!!.append(String.format("    label=\"%s\"\n", toId(id)))
        if (options.getExtensionInfo() == ExtensionShow.USAGES) {
            return
        }
        val usedRepos =
            ImmutableSortedSet.copyOf<kotlin.String?>(extensionRepoImports.get(id)!!.keySet())
        for (repo in usedRepos) {
            str!!.append(String.format("    %s [ label=\"%s\" ]\n", toId(id, repo), repo))
        }
        if (options.getExtensionInfo() == ExtensionShow.REPOS) {
            return
        }
        val unusedRepos =
            ImmutableSortedSet.copyOf<kotlin.String?>(
                Sets.difference<kotlin.String?>(
                    extensionRepos.get(id),
                    usedRepos
                )
            )
        for (repo in unusedRepos) {
            str!!.append(String.format("    %s [ label=\"%s\" style=dotted ]\n", toId(id, repo), repo))
        }
        str!!.append("  }\n")
    }

    private fun getReasonLabel(key: ModuleKey, parent: ModuleKey?): kotlin.String? {
        if (!options.getVerbose()) {
            return ""
        }
        val explanation = getExtraResolutionExplanation(key, parent)
        if (explanation == null) {
            return ""
        }
        val label = explanation.resolutionReason.getLabel()
        if (!label.isEmpty()) {
            return String.format("label=%s", label)
        }
        return ""
    }
}
