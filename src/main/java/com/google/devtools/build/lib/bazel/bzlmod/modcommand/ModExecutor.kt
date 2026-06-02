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

import com.google.auto.value.AutoValue
import com.google.common.annotations.VisibleForTesting
import com.google.common.collect.*
import com.google.devtools.build.lib.bazel.bzlmod.AttributeValues
import com.google.devtools.build.lib.bazel.bzlmod.BazelModuleInspectorValue.AugmentedModule
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionId
import com.google.devtools.build.lib.bazel.bzlmod.ModuleExtensionUsage
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.bazel.bzlmod.Version
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsExpanded
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.ModExecutor.ResultNode.IsIndirect
import com.google.devtools.build.lib.bazel.repository.RepoDefinitionValue
import com.google.devtools.build.lib.util.MaybeCompleteSet
import com.google.errorprone.annotations.CanIgnoreReturnValue
import net.starlark.java.eval.Starlark
import net.starlark.java.eval.StarlarkSemantics
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.lang.String
import java.nio.charset.StandardCharsets
import java.util.*
import java.util.Comparator
import java.util.Map
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Predicate
import java.util.stream.Collectors
import kotlin.Any
import kotlin.Boolean
import kotlin.Comparator
import kotlin.Int
import kotlin.toString

/**
 * Executes inspection queries for [com.google.devtools.build.lib.bazel.commands.ModCommand]
 * and prints the resulted output to the reporter's output stream using the different defined [ ].
 */
class ModExecutor(
    private val depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
    private val extensionUsages: ImmutableTable<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>,
    private val extensionRepos: ImmutableSetMultimap<ModuleExtensionId?, String?>,
    private val extensionFilter: Optional<MaybeCompleteSet<ModuleExtensionId?>?>,
    private val options: ModOptions,
    private val outputStream: OutputStream
) {
    private val printer: PrintWriter
    private var extensionRepoImports: ImmutableMap<ModuleExtensionId?, ImmutableSetMultimap<String?, ModuleKey?>?>


    constructor(
        depGraph: ImmutableMap<ModuleKey?, AugmentedModule?>,
        options: ModOptions,
        outputStream: OutputStream
    ) : this(
        depGraph,
        ImmutableTable.of<ModuleExtensionId?, ModuleKey?, ModuleExtensionUsage?>(),
        ImmutableSetMultimap.of<ModuleExtensionId?, String?>(),
        Optional.of<MaybeCompleteSet<ModuleExtensionId?>?>(MaybeCompleteSet.completeSet<ModuleExtensionId?>()),
        options,
        outputStream
    )

    init {
        this.printer =
            PrintWriter(
                OutputStreamWriter(
                    outputStream,
                    if (options.getCharset() == ModOptions.Charset.UTF8) StandardCharsets.UTF_8 else StandardCharsets.US_ASCII
                )
            )
        // Easier lookup table for repo imports by module.
        // It is updated after pruneByDepthAndLink to filter out pruned modules.
        this.extensionRepoImports = computeRepoImportsTable(depGraph.keySet())
    }

    fun graph(from: ImmutableSet<ModuleKey>) {
        val result: ImmutableMap<ModuleKey?, ResultNode?>?
        val targets = computeExtensionFilterTargets()
        if (targets.isEmpty()) {
            result = expandAndPrune(from)
        } else {
            result = expandPathsToTargets(from, targets, false)
        }
        OutputFormatters.getFormatter(options.getOutputFormat())
            .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options)
    }

    fun path(from: ImmutableSet<ModuleKey>, to: ImmutableSet<ModuleKey?>) {
        val targets =
            ImmutableSet.builder<ModuleKey?>()
                .addAll(computeExtensionFilterTargets())
                .addAll(to)
                .build()

        if (targets.isEmpty()) {
            printer.println("No target modules specified.")
            printer.flush()
            return
        }

        val result = expandPathsToTargets(from, targets, true)
        if (result.isEmpty()) {
            printer.println("No path found to the specified target modules.")
            printer.flush()
            return
        }
        OutputFormatters.getFormatter(options.getOutputFormat())
            .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options)
    }

    fun allPaths(from: ImmutableSet<ModuleKey>, to: ImmutableSet<ModuleKey?>) {
        val targets =
            ImmutableSet.builder<ModuleKey?>()
                .addAll(computeExtensionFilterTargets())
                .addAll(to)
                .build()

        if (targets.isEmpty()) {
            printer.println("No target modules specified.")
            printer.flush()
            return
        }

        val result = expandPathsToTargets(from, targets, false)
        if (result.isEmpty()) {
            printer.println("No path found to the specified target modules.")
            printer.flush()
            return
        }
        OutputFormatters.getFormatter(options.getOutputFormat())
            .output(result, depGraph, extensionRepos, extensionRepoImports, printer, options)
    }

    fun showRepo(targetRepoDefinitions: ImmutableMap<String?, RepoDefinitionValue?>) {
        val formatter = RepoOutputFormatter(printer, outputStream, options.getOutputFormat())
        for (e in targetRepoDefinitions.entrySet()) {
            formatter.print(e.getKey(), e.getValue())
        }

        try {
            outputStream.flush()
        } catch (ex: IOException) {
            // Ignore IOException like PrintWriter.
        }
        printer.flush()
    }

    @Throws(InvalidArgumentException::class)
    fun showExtension(
        extensions: ImmutableSet<ModuleExtensionId>, fromUsages: ImmutableSet<ModuleKey?>
    ) {
        for (extension in extensions) {
            displayExtension(extension, fromUsages)
        }
        printer.flush()
    }

    /**
     * Reconstructs a path backwards from a child to the root and adds it to the result graph.
     * 
     * 
     * This is a helper function for [.expandPathsToTargets]. Once a path to a target is
     * found, this function is called to walk up the dependency chain (using the `bfsParentMap`)
     * and add the necessary nodes and edges to the `resultGraph`.
     */
    private fun addPathToResultGraph(
        resultGraph: MutableMap<ModuleKey?, ResultNode>,
        bfsParentMap: MutableMap<ModuleKey?, ModuleKey?>,
        pathParent: ModuleKey?,
        pathChild: ModuleKey?
    ) {
        // Mark the child node as a target in the result graph.
        val childNodeBuilder = ResultNode.Companion.builder()
        if (resultGraph.containsKey(pathChild)) {
            childNodeBuilder.addChildren(resultGraph.get(pathChild)!!.children)
        }
        resultGraph.put(pathChild, childNodeBuilder.setTarget(true)!!.build()!!)

        // Traverse up from the found path to the root, adding the path to the result graph.
        val rootDirectChildren: ImmutableSortedSet<ModuleKey?> =
            depGraph.get(ModuleKey.Companion.ROOT)!!.getAllDeps(options.getIncludeUnused()).keySet()

        var currentChild = pathChild
        var currentParent = pathParent

        while (currentParent != null) {
            val parentNodeBuilder = ResultNode.Companion.builder()

            // Preserve existing children if the parent node is already in the graph.
            if (resultGraph.containsKey(currentParent)) {
                val existingNode: ResultNode = resultGraph.get(currentParent)!!
                parentNodeBuilder
                    .addChildren(existingNode.children)
                    .setTarget(existingNode.isTarget)
            }

            // Add the edge from parent to child.
            val isIndirect =
                currentParent == ModuleKey.Companion.ROOT && !rootDirectChildren.contains(currentChild)
            parentNodeBuilder.addChild(
                currentChild!!, IsExpanded.TRUE, if (isIndirect) IsIndirect.TRUE else IsIndirect.FALSE
            )

            resultGraph.put(currentParent, parentNodeBuilder.build()!!)

            // Move up the path.
            currentChild = currentParent
            currentParent = bfsParentMap.get(currentChild)
        }
    }

    /**
     * Finds paths from a set of modules to a set of target modules and returns a dependency graph
     * containing these paths.
     * 
     * 
     * This function performs a breadth-first search (BFS) starting from the `from` modules
     * to find paths to the `targets`. When a path is found, it's added to the result graph. The
     * search can be configured to stop after finding a single path to each target or to find all
     * possible paths. The final graph is then pruned to the depth specified in the options by [ ].
     * 
     * @param from The set of modules to start the search from.
     * @param targets The set of target modules to find paths to.
     * @param findSinglePath If true, the search for paths to a specific target will stop once the
     * first path is found.
     * @return An immutable map representing the pruned dependency graph containing the paths.
     */
    fun expandPathsToTargets(
        from: ImmutableSet<ModuleKey>, targets: ImmutableSet<ModuleKey?>, findSinglePath: Boolean
    ): ImmutableMap<ModuleKey?, ResultNode?> {
        // 1. Perform a BFS to find paths from the "from" modules to the "targets".
        // This map tracks the parent of each visited module to reconstruct paths later.
        val bfsParentMap: MutableMap<ModuleKey?, ModuleKey?> = HashMap<ModuleKey?, ModuleKey?>()
        from.stream()
            .filter(Predicate { key: ModuleKey? -> this.filterBuiltin(key!!) })
            .sorted(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR)
            .forEach(Consumer { moduleKey: ModuleKey? -> bfsParentMap.put(moduleKey, ModuleKey.Companion.ROOT) })
        bfsParentMap.put(ModuleKey.Companion.ROOT, null) // The root has no parent.

        val resultGraph: MutableMap<ModuleKey?, ResultNode> = HashMap<ModuleKey?, ResultNode>()
        val queue: Deque<ModuleKey?> = ArrayDeque<ModuleKey?>(from)
        val foundTargets: MutableSet<ModuleKey?> = HashSet<ModuleKey?>()

        while (!queue.isEmpty()) {
            // If we only need one path to each target, and we've found them all, we can stop.
            if (findSinglePath && foundTargets.containsAll(targets)) {
                break
            }

            val currentModuleKey = queue.pop()

            if (targets.contains(currentModuleKey)
                && !(findSinglePath && foundTargets.contains(currentModuleKey))
            ) {
                addPathToResultGraph(
                    resultGraph, bfsParentMap, bfsParentMap.get(currentModuleKey), currentModuleKey
                )
                foundTargets.add(currentModuleKey)
                if (findSinglePath && foundTargets.containsAll(targets)) {
                    break
                }
            }

            val module = depGraph.get(currentModuleKey)
            val dependencies: ImmutableSortedSet<ModuleKey?> =
                module!!.getAllDeps(options.getIncludeUnused()).keySet().stream()
                    .filter(Predicate { key: ModuleKey? -> this.filterBuiltin(key!!) })
                    .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleKey?>(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR))

            for (depKey in dependencies) {
                // A path to a target is found.
                if (targets.contains(depKey) && !(findSinglePath && foundTargets.contains(depKey))) {
                    addPathToResultGraph(resultGraph, bfsParentMap, currentModuleKey, depKey)
                    foundTargets.add(depKey)
                }
                // If this dependency hasn't been visited, add it to the queue for traversal.
                if (!bfsParentMap.containsKey(depKey)) {
                    bfsParentMap.put(depKey, currentModuleKey)
                    queue.add(depKey)
                }
            }
        }

        // 2. Prune the resulting graph containing the found paths to the specified depth.
        return ResultGraphPruner(
            MaybeCompleteSet.copyOf<ModuleKey?>(targets),
            ImmutableMap.copyOf<ModuleKey?, ResultNode?>(resultGraph)
        )
            .pruneByDepth()
    }

    /**
     * Expands the full dependency graph starting from a given set of modules and then prunes it to
     * the depth specified in the options.
     * 
     * 
     * This function first performs a breadth-first traversal to build a complete graph of all
     * dependencies reachable from the `from` modules. The `from` modules themselves are
     * "pinned" as direct children of the root node in the resulting graph. Finally, it uses [ ] to trim the graph to the requested depth.
     */
    @VisibleForTesting
    fun expandAndPrune(from: ImmutableSet<ModuleKey>): ImmutableMap<ModuleKey?, ResultNode?> {
        // This map will store the fully expanded dependency graph as ResultNode objects.
        val fullGraphBuilder = ImmutableMap.Builder<ModuleKey?, ResultNode?>()

        // 1. Initialize the graph with the ROOT module and its immediate "pinned" children.
        // "Pinned" children are the modules that are explicitly requested to start the graph from.
        val rootBuilder = ResultNode.Companion.builder()
        val rootDirectChildren: ImmutableSet<ModuleKey?> =
            depGraph.get(ModuleKey.Companion.ROOT)!!.getAllDeps(options.getIncludeUnused()).keySet()
        val pinnedChildren =
            getPinnedChildrenOfRootInTheResultGraph(rootDirectChildren, from).stream()
                .filter(Predicate { key: ModuleKey? -> this.filterBuiltin(key!!) })
                .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleKey?>(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR))

        for (pinnedChild in pinnedChildren) {
            val isDirect = rootDirectChildren.contains(pinnedChild)
            rootBuilder.addChild(
                pinnedChild, IsExpanded.TRUE, if (isDirect) IsIndirect.FALSE else IsIndirect.TRUE
            )
        }
        fullGraphBuilder.put(ModuleKey.Companion.ROOT, rootBuilder.build())

        // 2. Traverse the dependency graph starting from the pinned children (BFS).
        val visited: MutableSet<ModuleKey?> = HashSet<ModuleKey?>(pinnedChildren)
        val queue: Deque<ModuleKey?> = ArrayDeque<ModuleKey?>(pinnedChildren)
        visited.add(ModuleKey.Companion.ROOT)

        while (!queue.isEmpty()) {
            val currentModuleKey = queue.pop()
            val module = depGraph.get(currentModuleKey)
            val nodeBuilder = ResultNode.Companion.builder()

            val dependencies: ImmutableSortedSet<ModuleKey> =
                module!!.getAllDeps(options.getIncludeUnused()).keySet().stream()
                    .filter(Predicate { key: ModuleKey? -> this.filterBuiltin(key!!) })
                    .collect(ImmutableSortedSet.toImmutableSortedSet<ModuleKey?>(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR))

            for (depKey in dependencies) {
                if (visited.contains(depKey)) {
                    // This dependency has been seen before, but we add a non-expanded edge to it.
                    nodeBuilder.addChild(depKey, IsExpanded.FALSE, IsIndirect.FALSE)
                } else {
                    // New dependency found, add it to the queue to visit and mark as expanded.
                    nodeBuilder.addChild(depKey, IsExpanded.TRUE, IsIndirect.FALSE)
                    visited.add(depKey)
                    queue.add(depKey)
                }
            }
            fullGraphBuilder.put(currentModuleKey, nodeBuilder.build())
        }

        // 3. Prune the fully expanded graph based on the specified depth.
        return ResultGraphPruner(MaybeCompleteSet.completeSet<ModuleKey?>(), fullGraphBuilder.buildOrThrow())
            .pruneByDepth()
    }

    private inner class ResultGraphPruner(
        targets: MaybeCompleteSet<ModuleKey?>,
        private val oldResult: MutableMap<ModuleKey?, ResultNode>
    ) {
        private val resultBuilder: MutableMap<ModuleKey?, ResultNode.Builder>
        private val parentStack: MutableSet<ModuleKey?>
        private val targets: MaybeCompleteSet<ModuleKey?>

        /**
         * Constructs a ResultGraphPruner to prune the result graph after the specified depth.
         * 
         * @param targets If not complete, it means that the result graph contains paths to some
         * specific targets. This will cause some branches to contain, after the specified depths,
         * some targets or target parents. As any other nodes omitted, transitive edges (embedding
         * multiple edges) will be stored as *indirect*.
         * @param oldResult The unpruned result graph.
         */
        init {
            this.resultBuilder = HashMap<ModuleKey?, ResultNode.Builder>()
            this.parentStack = HashSet<ModuleKey?>()
            this.targets = targets
        }

        /**
         * Prunes the result tree after the specified depth using DFS (because some nodes may still
         * appear after the max depth).
         */
        fun pruneByDepth(): ImmutableMap<ModuleKey?, ResultNode?> {
            if (oldResult.isEmpty()) {
                return ImmutableMap.of<ModuleKey?, ResultNode?>()
            }

            val rootBuilder = ResultNode.Companion.builder()
            resultBuilder.put(ModuleKey.Companion.ROOT, rootBuilder)

            parentStack.add(ModuleKey.Companion.ROOT)

            for (e in oldResult.get(ModuleKey.Companion.ROOT)!!.childrenSortedByKey) {
                rootBuilder.addChild(e.getKey(), IsExpanded.TRUE, e.getValue().isIndirect)
                visitVisible(e.getKey(), 1, ModuleKey.Companion.ROOT, IsExpanded.TRUE)
            }

            // Build everything at the end to allow children to add themselves to their parent's
            // adjacency list.
            val result: ImmutableMap<ModuleKey?, ResultNode?> =
                resultBuilder.entrySet().stream()
                    .collect()
            TODO(
                """
                |Cannot convert element
                |With text:
                |ModuleKey, ResultNode>toImmutableSortedMap(
                |                      ModuleKey.LEXICOGRAPHIC_COMPARATOR,
                |                      Entry::getKey,
                |                      e -> e.getValue().build())
                """.trimMargin()
            )

            // Filter imports for nodes that were pruned during this process.
            extensionRepoImports = computeRepoImportsTable(result.keySet())
            return result
        }

        // Handles graph traversal within the specified depth.
        fun visitVisible(
            moduleKey: ModuleKey, depth: Int, parentKey: ModuleKey?, expanded: IsExpanded?
        ) {
            parentStack.add(moduleKey)
            val oldNode: ResultNode = oldResult.get(moduleKey)!!
            val nodeBuilder =
                resultBuilder.computeIfAbsent(moduleKey, Function { k: ModuleKey? -> ResultNode.Companion.builder() })

            nodeBuilder.setTarget(oldNode.isTarget)
            if (depth > 1) {
                resultBuilder.get(parentKey)!!.addChild(moduleKey, expanded, IsIndirect.FALSE)
            }

            if (expanded == IsExpanded.FALSE) {
                parentStack.remove(moduleKey)
                return
            }
            for (e in oldNode.childrenSortedByKey) {
                val childKey: ModuleKey = e.getKey()
                val childExpanded: IsExpanded? = e.getValue().isExpanded
                if (notCycle(childKey)) {
                    if (depth < options.getDepth()) {
                        visitVisible(childKey, depth + 1, moduleKey, childExpanded)
                    } else if (!targets.isComplete()) {
                        visitDetached(childKey, moduleKey, moduleKey, childExpanded)
                    }
                } else if (options.getCycles()) {
                    nodeBuilder.addCycle(childKey)
                }
            }
            parentStack.remove(moduleKey)
        }

        // Detached mode is only present in withTargets and handles adding targets and target parents
        // living below the specified depth to the graph.
        fun visitDetached(
            moduleKey: ModuleKey,
            parentKey: ModuleKey?,
            lastVisibleParentKey: ModuleKey,
            expanded: IsExpanded?
        ) {
            var lastVisibleParentKey = lastVisibleParentKey
            parentStack.add(moduleKey)
            val oldNode: ResultNode = oldResult.get(moduleKey)!!
            val nodeBuilder = ResultNode.Companion.builder()
            nodeBuilder.setTarget(oldNode.isTarget)

            if (oldNode.isTarget || isTargetParent(oldNode)) {
                val parentBuilder: ResultNode.Builder = resultBuilder.get(lastVisibleParentKey)!!
                val childIndirect =
                    if (lastVisibleParentKey == parentKey) IsIndirect.FALSE else IsIndirect.TRUE
                parentBuilder.addChild(moduleKey, expanded, childIndirect)
                resultBuilder.put(moduleKey, nodeBuilder)
                lastVisibleParentKey = moduleKey
            }

            if (expanded == IsExpanded.FALSE) {
                parentStack.remove(moduleKey)
                return
            }
            for (e in oldNode.childrenSortedByKey) {
                val childKey: ModuleKey = e.getKey()
                val childExpanded: IsExpanded? = e.getValue().isExpanded
                if (notCycle(childKey)) {
                    visitDetached(childKey, moduleKey, lastVisibleParentKey, childExpanded)
                } else if (options.getCycles()) {
                    nodeBuilder.addCycle(childKey)
                }
            }
            parentStack.remove(moduleKey)
        }

        fun notCycle(key: ModuleKey?): Boolean {
            return !parentStack.contains(key)
        }

        fun isTargetParent(node: ResultNode): Boolean {
            return node.children.keys().stream()
                .filter(Predicate.not<ModuleKey?>(Predicate { o: ModuleKey? -> parentStack.contains(o) }))
                .anyMatch(Predicate { value: ModuleKey? -> targets.contains(value) })
        }
    }

    /**
     * Return a sorted list of modules that will be the direct children of the root in the result
     * graph (original root's direct dependencies along with the specified targets).
     */
    private fun getPinnedChildrenOfRootInTheResultGraph(
        rootDirectDeps: ImmutableSet<ModuleKey?>?, fromTargets: ImmutableSet<ModuleKey>
    ): ImmutableSortedSet<ModuleKey?> {
        val targetKeys: MutableSet<ModuleKey?> = HashSet<ModuleKey?>(fromTargets)
        if (fromTargets.contains(ModuleKey.Companion.ROOT)) {
            targetKeys.remove(ModuleKey.Companion.ROOT)
            targetKeys.addAll(rootDirectDeps!!)
        }
        return ImmutableSortedSet.copyOf<ModuleKey?>(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR, targetKeys)
    }

    /**
     * If the extensionFilter option is set, computes the set of target modules that use the specified
     * extension(s)
     */
    private fun computeExtensionFilterTargets(): ImmutableSet<ModuleKey?> {
        if (extensionFilter.isEmpty()) {
            return ImmutableSet.of<ModuleKey?>()
        }
        return depGraph.keySet().stream()
            .filter(Predicate { key: ModuleKey? -> this.filterUnused(key) })
            .filter(Predicate { key: ModuleKey? -> this.filterBuiltin(key!!) })
            .filter(Predicate { k: ModuleKey? -> intersect(extensionFilter.get(), extensionUsages.column(k).keySet()) })
            .collect(ImmutableSet.toImmutableSet<ModuleKey?>())
    }

    /** Compute the multimap of repo imports to modules for each extension.  */
    private fun computeRepoImportsTable(presentModules: ImmutableSet<ModuleKey?>): ImmutableMap<ModuleExtensionId?, ImmutableSetMultimap<String?, ModuleKey?>?> {
        val resultBuilder =
            ImmutableMap.Builder<ModuleExtensionId?, ImmutableSetMultimap<String?, ModuleKey?>?>()
        for (extension in extensionUsages.rowKeySet()) {
            if (extensionFilter.isPresent() && !extensionFilter.get().contains(extension)) {
                continue
            }
            val modulesToImportsBuilder =
                ImmutableSetMultimap.Builder<ModuleKey?, String?>()
            for (usage in extensionUsages.rowMap().get(extension).entrySet()) {
                if (!presentModules.contains(usage.getKey())) {
                    continue
                }
                for (proxy in usage.getValue().getProxies()) {
                    modulesToImportsBuilder.putAll(usage.getKey(), proxy.getImports().values())
                }
            }
            resultBuilder.put(extension, modulesToImportsBuilder.build().inverse())
        }
        return resultBuilder.buildOrThrow()
    }

    private fun filterUnused(key: ModuleKey?): Boolean {
        val module = depGraph.get(key)
        return options.getIncludeUnused() || module!!.isUsed()
    }

    private fun filterBuiltin(key: ModuleKey): Boolean {
        return options.getIncludeBuiltin() || !isBuiltin(key)
    }

    private fun tagToFunctionArgs(attributes: AttributeValues): String? {
        return attributes.attributes().entrySet().stream() // show 'name' first for readability, similar to buildifier
            .sorted(Map.Entry.comparingByKey<String?, Any?>(Comparator.comparing<String?, String?>(Function { s: String? -> if (s == "name") "" else s })))
            .map<String?>(
                Function { e: MutableMap.MutableEntry<String?, Any?>? ->
                    String.format(
                        "%s=%s", e.getKey(), Starlark.repr(e.getValue(), StarlarkSemantics.DEFAULT)
                    )
                })
            .collect(Collectors.joining(", "))
    }

    /** Helper to display show_extension info.  */
    @Throws(InvalidArgumentException::class)
    private fun displayExtension(extension: ModuleExtensionId, fromUsages: ImmutableSet<ModuleKey?>) {
        var fromUsages = fromUsages
        printer.printf("## %s:\n", extension.toString())
        printer.println()
        printer.println("Fetched repositories:")
        if (!extensionRepoImports.containsKey(extension)) {
            throw InvalidArgumentException(
                String.format("No extension %s exists in the dependency graph", extension)
            )
        }
        val usedRepos =
            ImmutableSortedSet.copyOf<kotlin.String?>(extensionRepoImports.get(extension)!!.keySet())
        val unusedRepos =
            ImmutableSortedSet.copyOf<kotlin.String?>(
                Sets.difference<kotlin.String?>(
                    extensionRepos.get(extension),
                    usedRepos
                )
            )
        for (repo in usedRepos) {
            printer.printf(
                "  - %s (imported by %s)\n",
                repo,
                extensionRepoImports.get(extension)!!.get(repo).stream()
                    .sorted(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR)
                    .map<kotlin.String?>(Function { obj: ModuleKey? -> obj.toString() })
                    .collect(Collectors.joining(", "))
            )
        }
        for (repo in unusedRepos) {
            printer.printf("  - %s\n", repo)
        }
        printer.println()
        if (fromUsages.isEmpty()) {
            fromUsages = ImmutableSet.copyOf<ModuleKey?>(extensionUsages.rowMap().get(extension).keySet())
        }
        for (module in fromUsages) {
            if (!extensionUsages.contains(extension, module)) {
                continue
            }
            val usage = extensionUsages.get(extension, module)
            // TODO: maybe consider printing each proxy separately? Might be relevant for included
            //  segments.
            printer.printf(
                "## Usage in %s from %s:%s\n",
                module,
                usage!!.getProxies().getFirst().getLocation().file(),
                usage.getProxies().getFirst().getLocation().line()
            )

            if (extension.isInnate()) {
                // This is for the special case of "innate" extensions: fake module extensions created by
                // use_repo_rule(). The name of the extension is of the form "<bzl_file_label> <rule_name>".
                // Rule names cannot contain spaces, so we can split on the last space.
                val lastSpace: Int = extension.extensionName.lastIndexOf(' '.code)
                val rawLabel: kotlin.String = extension.extensionName.substring(0, lastSpace)
                val ruleName: kotlin.String = extension.extensionName.substring(lastSpace + 1)

                printer.printf("%s = use_repo_rule(\"%s\", \"%s\")\n", ruleName, rawLabel, ruleName)

                for (tag in usage.getTags()) {
                    // use_repo_rule creates a fake repo extension with a single tag 'repo'.
                    // However, code defensively and print the tag name if it's not 'repo'.
                    var callee: kotlin.String? = ruleName
                    if (tag.getTagName() != "repo") {
                        callee = String.format("%s.%s", ruleName, tag.getTagName())
                    }
                    printer.printf("%s(%s)\n", callee, tagToFunctionArgs(tag.getAttributeValues()))
                }

                // Skip the use_repo part since every call to the repo rule creates a repo that is imported.
                printer.println()
            } else {
                for (tag in usage.getTags()) {
                    printer.printf(
                        "%s.%s(%s)\n",
                        extension.extensionName,
                        tag.getTagName(),
                        tagToFunctionArgs(tag.getAttributeValues())
                    )
                }
                printer.printf("use_repo(\n")
                printer.printf("  %s,\n", extension.extensionName)
                for (proxy in usage.getProxies()) {
                    for (repo in proxy.getImports().entrySet()) {
                        printer.printf(
                            "  %s,\n",
                            if (repo.getKey() == repo.getValue())
                                String.format("\"%s\"", repo.getKey())
                            else
                                String.format("%s=\"%s\"", repo.getKey(), repo.getValue())
                        )
                    }
                }
                printer.printf(")\n\n")
            }
        }
    }

    private fun isBuiltin(key: ModuleKey): Boolean {
        return key == ModuleKey("bazel_tools", Version.Companion.EMPTY)
    }

    /** A node representing a module that forms the result graph.  */
    @AutoValue
    abstract class ResultNode {
        /** Whether the module is one of the targets in a paths query.  */
        abstract val isTarget: Boolean

        internal enum class IsExpanded {
            FALSE,
            TRUE
        }

        internal enum class IsIndirect {
            FALSE,
            TRUE
        }

        internal enum class IsCycle {
            FALSE,
            TRUE
        }

        /**
         * Detailed edge type for the [ResultNode] graph.
         * 
         * @param isExpanded Whether the node should be expanded from this edge (the same node can
         * appear in multiple places in a flattened graph).
         * @param isIndirect Whether the edge is a direct edge or an indirect (transitive) one.
         * @param isCycle Whether the edge is cycling back inside the flattened graph.
         */
        @kotlin.jvm.JvmRecord
        data class NodeMetadata(val isExpanded: IsExpanded?, val isIndirect: IsIndirect?, val isCycle: IsCycle?) {
            init {
                Objects.requireNonNull<IsExpanded?>(isExpanded, "isExpanded")
                Objects.requireNonNull<IsIndirect?>(isIndirect, "isIndirect")
                Objects.requireNonNull<IsCycle?>(isCycle, "isCycle")
            }

            companion object {
                private fun create(
                    isExpanded: IsExpanded?, isIndirect: IsIndirect?, isCycle: IsCycle?
                ): NodeMetadata {
                    return NodeMetadata(isExpanded, isIndirect, isCycle)
                }
            }
        }

        /** List of children mapped to detailed edge types.  */
        abstract val children: ImmutableSetMultimap<ModuleKey?, NodeMetadata?>?

        val childrenSortedByKey: ImmutableSortedSet<MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>>
            get() = ImmutableSortedSet.copyOf<MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>?>(
                Map.Entry.comparingByKey<ModuleKey?, NodeMetadata?>(ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR),
                this.children.entries()
            )

        val childrenSortedByEdgeType: ImmutableSortedSet<MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>?>
            get() {
                return ImmutableSortedSet.< Entry < ModuleKey, NodeMetadata>>copyOf<kotlin.collections.MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>?>(
                Comparator.comparing<MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>?, IsCycle?>(
                    Function { e: MutableMap.MutableEntry<ModuleKey?, NodeMetadata?>? -> e.getValue().isCycle },
                    TODO("Cannot convert element")
                )<IsCycle> java . util . Comparator . reverseOrder < T ? > ())
                .<IsExpanded>thenComparing<IsExpanded?>({ e -> e.getValue().isExpanded() })
                .<IsIndirect>thenComparing<IsIndirect?>({ e -> e.getValue().isIndirect() })
                .<ModuleKey>thenComparing<ModuleKey?>({ obj: MutableMap.MutableEntry<*, *>? -> obj.getKey() }, ModuleKey.Companion.LEXICOGRAPHIC_COMPARATOR)
                this.children.entries()
            }

        @AutoValue.Builder
        internal abstract class Builder {
            abstract fun setTarget(value: Boolean): Builder?

            abstract fun childrenBuilder(): ImmutableSetMultimap.Builder<ModuleKey?, NodeMetadata?>?

            @CanIgnoreReturnValue
            fun addChild(value: ModuleKey, expanded: IsExpanded?, indirect: IsIndirect?): Builder {
                childrenBuilder()!!.put(value, NodeMetadata.Companion.create(expanded, indirect, IsCycle.FALSE))
                return this
            }

            @CanIgnoreReturnValue
            fun addChildren(children: ImmutableSetMultimap<ModuleKey?, NodeMetadata?>): Builder {
                childrenBuilder()!!.putAll(children)
                return this
            }

            @CanIgnoreReturnValue
            fun addCycle(value: ModuleKey): Builder {
                childrenBuilder()!!
                    .put(value, NodeMetadata.Companion.create(IsExpanded.FALSE, IsIndirect.FALSE, IsCycle.TRUE))
                return this
            }

            abstract fun build(): ResultNode?
        }

        companion object {
            @kotlin.jvm.JvmStatic
            fun builder(): Builder {
                return Builder().setTarget(false)!!
            }
        }
    }

    companion object {
        private fun intersect(
            a: MaybeCompleteSet<ModuleExtensionId?>, b: MutableSet<ModuleExtensionId?>
        ): Boolean {
            if (a.isComplete()) {
                return !b.isEmpty()
            }
            return !Collections.disjoint(a.getElementsIfNotComplete(), b)
        }
    }
}
