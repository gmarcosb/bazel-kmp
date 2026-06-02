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

import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableSortedSet
import com.google.common.collect.Sets
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.Version
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.*
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModOptions.ExtensionShow
import java.lang.String
import java.util.*
import java.util.function.Predicate
import kotlin.Boolean
import kotlin.Int
import kotlin.plus

/** Outputs graph-based results of [ModExecutor] in a human-readable text format.  */
class TextOutputFormatter : OutputFormatters.OutputFormatter() {
    private var isLastChildStack: Deque<Boolean?>? = null
    private var drawCharset: DrawCharset? = null
    private var seenExtensions: MutableSet<ModuleExtensionId?>? = null
    private var str: StringBuilder? = null
    private var visited: MutableSet<ModuleKey?>? = null

    public override fun output() {
        if (options.getCharset() == ModOptions.Charset.ASCII) {
            drawCharset = DrawCharset.ASCII
        } else {
            drawCharset = DrawCharset.UTF8
        }
        isLastChildStack = ArrayDeque<Boolean?>()
        seenExtensions = HashSet<ModuleExtensionId?>()
        visited = HashSet<ModuleKey?>()
        str = StringBuilder()
        printModule(ModuleKey.Companion.ROOT, null, IsExpanded.TRUE, IsIndirect.FALSE, IsCycle.FALSE, 0)
        this.printer.println(str)
    }

    // Prints the indents and the tree drawing characters.
    private fun printTreeDrawing(indirect: IsIndirect?, depth: Int) {
        if (depth > 0) {
            val indents: Int = isLastChildStack.size() - 1
            val value = isLastChildStack!!.descendingIterator()
            for (i in 0..<indents) {
                val isLastChild: Boolean = value.next()!!
                if (isLastChild) {
                    str!!.append(drawCharset!!.emptyIndent)
                } else {
                    str!!.append(drawCharset!!.prevChildIndent)
                }
            }
            if (indirect == IsIndirect.TRUE) {
                if (isLastChildStack!!.getFirst()) {
                    str!!.append(drawCharset!!.lastIndirectChildIndent)
                } else {
                    str!!.append(drawCharset!!.indirectChildIndent)
                }
            } else {
                if (isLastChildStack!!.getFirst()) {
                    str!!.append(drawCharset!!.lastChildIndent)
                } else {
                    str!!.append(drawCharset!!.childIndent)
                }
            }
        }
    }

    // Helper to print module extensions similarly to printModule.
    private fun printExtension(
        key: ModuleKey?, extensionId: ModuleExtensionId?, unexpanded: Boolean, depth: Int
    ) {
        printTreeDrawing(IsIndirect.FALSE, depth)
        str!!.append('$')
        str!!.append(extensionId)
        str!!.append(' ')
        if (unexpanded && options.getExtensionInfo() == ExtensionShow.ALL) {
            str!!.append("... ")
        }
        str!!.append("\n")
        if (options.getExtensionInfo() == ExtensionShow.USAGES) {
            return
        }
        val repoImports =
            ImmutableSortedSet.copyOf<String?>(extensionRepoImports.get(extensionId)!!.inverse().get(key))
        var unusedRepos = ImmutableSortedSet.of<String?>()
        if (!unexpanded && options.getExtensionInfo() == ExtensionShow.ALL) {
            unusedRepos =
                ImmutableSortedSet.copyOf<String?>(
                    Sets.difference<String?>(
                        extensionRepos.get(extensionId), extensionRepoImports.get(extensionId)!!.keySet()
                    )
                )
        }
        val totalChildrenNum: Int = repoImports.size() + unusedRepos.size()
        var currChild = 1
        for (usedRepo in repoImports) {
            isLastChildStack!!.push(currChild++ == totalChildrenNum)
            printExtensionRepo(usedRepo, IsIndirect.FALSE, depth + 1)
            isLastChildStack!!.pop()
        }
        if (unexpanded || options.getExtensionInfo() == ExtensionShow.REPOS) {
            return
        }
        for (unusedPackage in unusedRepos) {
            isLastChildStack!!.push(currChild++ == totalChildrenNum)
            printExtensionRepo(unusedPackage, IsIndirect.TRUE, depth + 1)
            isLastChildStack!!.pop()
        }
    }

    // Prints an extension repo line.
    private fun printExtensionRepo(repoName: String?, indirectLink: IsIndirect?, depth: Int) {
        printTreeDrawing(indirectLink, depth)
        str!!.append(repoName).append("\n")
    }

    // Depth-first traversal to print the actual output
    private fun printModule(
        key: ModuleKey,
        parent: ModuleKey?,
        expanded: IsExpanded?,
        indirect: IsIndirect?,
        cycle: IsCycle?,
        depth: Int
    ) {
        printTreeDrawing(indirect, depth)

        val added = visited!!.add(key)
        try {
            val node = Objects.requireNonNull<ResultNode>(result.get(key))
            if (key == ModuleKey.Companion.ROOT) {
                val rootModule = depGraph.get(ModuleKey.Companion.ROOT)
                Preconditions.checkNotNull<AugmentedModule?>(rootModule)
                str!!.append(
                    String.format(
                        "<root> (%s@%s)",
                        rootModule!!.name,
                        if (rootModule.version == Version.Companion.EMPTY) "_" else rootModule.version
                    )
                )
            } else {
                str!!.append(key).append(" ")
            }

            var totalChildrenNum = node.getChildren().size()

            val extensionsUsed: ImmutableSortedSet<ModuleExtensionId?> =
                extensionRepoImports.keySet().stream()
                    .filter(Predicate { e: ModuleExtensionId? ->
                        extensionRepoImports.get(e)!!.inverse().containsKey(key)
                    })
                    .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleExtensionId?>(ModuleExtensionId.Companion.LEXICOGRAPHIC_COMPARATOR))
            if (options.getExtensionInfo() != ExtensionShow.HIDDEN) {
                totalChildrenNum += extensionsUsed.size()
            }

            // If we've already seen this node in the current traversal path, treat it as a cycle
            // even if the graph structure says otherwise (which can happen due to merged paths).
            val isCycle = cycle == IsCycle.TRUE || !added

            if (isCycle) {
                str!!.append("(cycle) ")
            } else if (expanded == IsExpanded.FALSE) {
                str!!.append("(*) ")
            } else {
                if (node.isTarget()) {
                    str!!.append("# ")
                }
            }
            val module = Objects.requireNonNull<AugmentedModule>(depGraph.get(key))
            if (!options.getVerbose() && !module.isUsed()) {
                str!!.append("(unused) ")
            }
            // If the edge is indirect, the parent is not only unknown, but the node could have come
            // from multiple paths merged in the process, so we skip the resolution explanation.
            if (indirect == IsIndirect.FALSE && options.getVerbose() && parent != null) {
                val explanation = getExtraResolutionExplanation(key, parent)
                if (explanation != null) {
                    str!!.append(explanation.toExplanationString(!module.isUsed()))
                }
            }

            str!!.append("\n")

            if (expanded == IsExpanded.FALSE || isCycle) {
                return
            }

            var currChild = 1
            if (options.getExtensionInfo() != ExtensionShow.HIDDEN) {
                for (extensionId in extensionsUsed) {
                    val unexpandedExtension = !seenExtensions!!.add(extensionId)
                    isLastChildStack!!.push(currChild++ == totalChildrenNum)
                    printExtension(key, extensionId, unexpandedExtension, depth + 1)
                    isLastChildStack!!.pop()
                }
            }
            for (e in node.getChildrenSortedByEdgeType()) {
                val childKey: ModuleKey = e.getKey()
                val childExpanded: IsExpanded? = e.getValue().isExpanded
                val childIndirect: IsIndirect? = e.getValue().isIndirect
                val childCycles: IsCycle? = e.getValue().isCycle
                isLastChildStack!!.push(currChild++ == totalChildrenNum)
                printModule(childKey, key, childExpanded, childIndirect, childCycles, depth + 1)
                isLastChildStack!!.pop()
            }
        } finally {
            if (added) {
                visited!!.remove(key)
            }
        }
    }

    internal enum class DrawCharset(
        emptyIndent: kotlin.String,
        prevChildIndent: kotlin.String,
        childIndent: kotlin.String,
        indirectChildIndent: kotlin.String,
        lastChildIndent: kotlin.String,
        lastIndirectChildIndent: kotlin.String
    ) {
        ASCII("    ", "|   ", "|___", "|...", "|___", "|..."),
        UTF8("    ", "│   ", "├───", "├╌╌╌", "└───", "└╌╌╌");

        val emptyIndent: kotlin.String?
        val prevChildIndent: kotlin.String?
        val childIndent: kotlin.String?
        val indirectChildIndent: kotlin.String?
        val lastChildIndent: kotlin.String?
        val lastIndirectChildIndent: kotlin.String?

        init {
            this.emptyIndent = emptyIndent
            this.prevChildIndent = prevChildIndent
            this.childIndent = childIndent
            this.indirectChildIndent = indirectChildIndent
            this.lastChildIndent = lastChildIndent
            this.lastIndirectChildIndent = lastIndirectChildIndent
        }
    }
}
