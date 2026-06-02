// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/** Computes path queries given a [TargetProvider].  */
internal class PathLabelVisitor(
    targetProvider: TargetProvider,
    edgeFilter: DependencyFilter?,
    errorObserver: TargetEdgeErrorObserver
) {
    private val targetProvider: TargetProvider
    private val edgeFilter: DependencyFilter?
    private val errorObserver: TargetEdgeErrorObserver

    /**
     * Construct a PathLabelVisitor.
     * 
     * @param targetProvider how to resolve labels to targets
     * @param edgeFilter which edges may be traversed
     */
    init {
        this.targetProvider = targetProvider
        this.edgeFilter = edgeFilter
        this.errorObserver = errorObserver
    }

    @Throws(java.lang.InterruptedException::class)
    fun somePath(
        eventHandler: ExtendedEventHandler?, from: Iterable<Target>, to: Iterable<Target?>
    ): Iterable<Target?> {
        val visitor: Visitor =
            com.google.devtools.build.lib.query2.query.PathLabelVisitor.Visitor(eventHandler, VisitorMode.SOMEPATH)
        // TODO(ulfjack): It might be faster to stop the visitation once we see any 'to' Target.
        visitor.visitTargets(from)
        for (t in to) {
            if (visitor.hasVisited(t)) {
                val result: ArrayDeque<Target?> = ArrayDeque<Target?>()
                var at = t
                while (true) {
                    result.addFirst(at)
                    val pred = visitor.getParents(at)
                    if (pred == null || pred.isEmpty()) {
                        break
                    }
                    at = pred.get(0)
                }
                return result
            }
        }
        return com.google.common.collect.ImmutableList.of<Target?>()
    }

    @Throws(java.lang.InterruptedException::class)
    fun allPaths(
        eventHandler: ExtendedEventHandler?, from: Iterable<Target>, to: Iterable<Target?>
    ): Iterable<Target?> {
        val visitor: Visitor =
            com.google.devtools.build.lib.query2.query.PathLabelVisitor.Visitor(eventHandler, VisitorMode.ALLPATHS)
        visitor.visitTargets(from)
        val result: MutableSet<Target?> = HashSet<Target?>()
        val workQueue: java.util.Queue<Target?> = ArrayDeque<Target?>()
        // Add all 'to' targets to the work queue that are in the transitive closure of 'from' targets.
        for (t in to) {
            if (visitor.hasVisited(t)) {
                workQueue.add(t)
            }
        }
        while (!workQueue.isEmpty()) {
            val at: Target? = workQueue.remove()
            if (result.add(at)) {
                val pred = visitor.getParents(at)
                if (pred != null) {
                    workQueue.addAll(pred)
                }
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    fun samePkgDirectRdeps(
        eventHandler: ExtendedEventHandler?, from: Iterable<Target>
    ): Iterable<Target?> {
        val visitor: Visitor = com.google.devtools.build.lib.query2.query.PathLabelVisitor.Visitor(
            eventHandler,
            VisitorMode.SAME_PKG_DIRECT_RDEPS
        )
        for (t in from) {
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): support lazy macro expansion
            visitor.visitTargets(t.getPackage().getTargets().values())
        }
        val result: MutableSet<Target?> = HashSet<Target?>()
        for (t in from) {
            val pred = visitor.getParents(t)
            if (pred != null) {
                result.addAll(pred)
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    fun rdeps(
        eventHandler: ExtendedEventHandler?,
        from: Iterable<Target?>,
        universe: Iterable<Target>,
        depth: OptionalInt
    ): Iterable<Target?> {
        val visitor: Visitor =
            com.google.devtools.build.lib.query2.query.PathLabelVisitor.Visitor(eventHandler, VisitorMode.ALLPATHS)
        visitor.visitTargets(universe)

        val result: MutableSet<Target?> = HashSet<Target?>()
        var at: MutableSet<Target?> = HashSet<Target?>()
        // Add all 'from' targets to the work set that are in the transitive closure of 'universe'.
        for (t in from) {
            if (visitor.hasVisited(t)) {
                at.add(t)
            }
        }
        var next: MutableSet<Target?> = HashSet<Target?>()
        // In round i, we add all targets at depth i to result, so we need depth + 1 rounds. Note that
        // depth can be Integer.MAX_VALUE, so do not use "< depth + 1" here..
        var i = 0
        while (QueryEnvironment.Companion.shouldVisit(depth, i++) && !at.isEmpty()) {
            for (t in at) {
                if (result.add(t)) {
                    val pred = visitor.getParents(t)
                    if (pred != null) {
                        next.addAll(pred)
                    }
                }
            }
            at.clear()
            val temp = at
            at = next
            next = temp
        }
        return result
    }

    private enum class VisitorMode {
        DEPS,
        ALLPATHS,
        SOMEPATH,
        SAME_PKG_DIRECT_RDEPS
    }

    private class Visit(from: Target?, attribute: Attribute?, target: Target) {
        private val from: Target?
        private val attribute: Attribute?
        private val target: Target

        init {
            if (target == null) {
                throw java.lang.NullPointerException(
                    java.lang.String.format(
                        "'%s' attribute '%s'",
                        if (from == null) "(null)" else from.getLabel().toString(),
                        if (attribute == null) "(null)" else attribute.name
                    )
                )
            }
            this.from = from
            this.attribute = attribute
            this.target = target
        }
    }

    private inner class Visitor(eventHandler: ExtendedEventHandler?, mode: VisitorMode?) {
        private val eventHandler: ExtendedEventHandler?
        private val mode: VisitorMode
        private val visited: MutableSet<Target?> = HashSet<Target?>()
        private val parentMap: MutableMap<Target?, MutableList<Target?>?> = HashMap<Target?, MutableList<Target?>?>()
        private val workQueue: java.util.Queue<Visit> = ArrayDeque<Visit>()

        init {
            this.eventHandler = eventHandler
            this.mode = com.google.common.base.Preconditions.checkNotNull<VisitorMode>(mode)
        }

        fun hasVisited(target: Target?): Boolean {
            return visited.contains(target)
        }

        fun getParents(target: Target?): MutableList<Target?>? {
            return parentMap.get(target)
        }

        /**
         * Visit the specified labels and follow the transitive closure of their outbound dependencies.
         * 
         * @param targets the targets to visit
         */
        @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
        @Throws(java.lang.InterruptedException::class)
        fun visitTargets(targets: Iterable<Target>) {
            for (t in targets) {
                enqueue(null, null, t)
            }
            while (!workQueue.isEmpty()) {
                val visit: Visit = workQueue.remove()
                try {
                    visit(visit.from, visit.attribute, visit.target)
                } catch (e: NoSuchThingException) {
                    errorObserver.missingEdge(visit.from, visit.target.getLabel(), e)
                }
            }
        }

        @Throws(java.lang.InterruptedException::class, NoSuchThingException::class)
        fun enqueue(from: Target, attribute: Attribute?, label: Label) {
            if (mode == VisitorMode.SAME_PKG_DIRECT_RDEPS) {
                // Only track same-package dependencies to avoid loading unneeded packages.
                if (!label.getPackageIdentifier().equals(from.getLabel().getPackageIdentifier())) {
                    return
                }
            }
            val target: Target = targetProvider.getTarget(eventHandler, label)
            enqueue(from, attribute, target)
        }

        fun enqueue(from: Target?, attribute: Attribute?, target: Target) {
            workQueue.add(Visit(from, attribute, target))
        }

        @Throws(java.lang.InterruptedException::class, NoSuchThingException::class)
        fun visit(from: Target?, attribute: Attribute, target: Target) {
            if (from != null) {
                when (mode) {
                    VisitorMode.DEPS -> {
                        // Don't update parentMap; only use visited.
                    }

                    VisitorMode.SAME_PKG_DIRECT_RDEPS -> {
                        // Only track same-package dependencies.
                        if (target
                                .getLabel()
                                .getPackageIdentifier()
                                .equals(from.getLabel().getPackageIdentifier())
                        ) {
                            if (!parentMap.containsKey(target)) {
                                parentMap.put(target, java.util.ArrayList<Target?>())
                            }
                            parentMap.get(target)!!.add(from)
                        }
                        // We only need to perform a single level of visitation. We have a non-null 'from'
                        // target, and we're now at 'target' target, so we have one level, and can return here.
                        return
                    }

                    VisitorMode.ALLPATHS -> {
                        if (!parentMap.containsKey(target)) {
                            parentMap.put(target, java.util.ArrayList<Target?>())
                        }
                        parentMap.get(target)!!.add(from)
                    }

                    VisitorMode.SOMEPATH -> parentMap.putIfAbsent(
                        target,
                        com.google.common.collect.ImmutableList.of<Target?>(from)
                    )
                }

                visitAspectsIfRequired(from, attribute, target)
            } else if (mode == VisitorMode.SOMEPATH) {
                // Here we make sure that if this is a top-level visitation node (where 'from' is null),
                // a parent edge cannot be made for this node. This prevents parent-edge cycles from being
                // formed and hence infinite loops impossible when traversing parent-edges.
                parentMap.putIfAbsent(target, com.google.common.collect.ImmutableList.of<Target?>())
            }

            if (visited.add(target)) {
                visitEdgesOfTarget(target)
            }
        }

        @Throws(java.lang.InterruptedException::class, NoSuchThingException::class)
        fun visitEdgesOfTarget(target: Target?) {
            try {
                LabelVisitationUtils.visitTarget(
                    target,
                    edgeFilter,
                    { from, attribute, label ->
                        try {
                            enqueue(from, attribute, label)
                        } catch (e: java.lang.InterruptedException) {
                            // Tunnel the exception, since we can't throw checked exceptions from here.
                            throw CompletionException(e)
                        } catch (e: NoSuchThingException) {
                            throw CompletionException(e)
                        }
                    })
            } catch (e: CompletionException) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e.getCause(), NoSuchThingException::class.java)
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
                throw e
            }
        }

        @Throws(java.lang.InterruptedException::class, NoSuchThingException::class)
        fun visitAspectsIfRequired(from: Target, attribute: Attribute, to: Target?) {
            // TODO(bazel-team): The getAspects call below is duplicate work for each direct dep entailed
            // by an attribute's value. Additionally, we might end up enqueueing the same exact visitation
            // multiple times: consider the case where the same direct dependency is entailed by aspects
            // of *different* attributes. These visitations get culled later, but we still have to pay the
            // overhead for all that.

            if (from !is Rule || to !is Rule) {
                return
            }
            for (aspect in attribute.getAspects(from)) {
                if (AspectDefinition.satisfies(
                        aspect, to.getRuleClassObject().getAdvertisedProviders()
                    )
                ) {
                    val allLabels: com.google.common.collect.Multimap<Attribute?, Label?> =
                        com.google.common.collect.HashMultimap.create<Attribute?, Label?>()
                    AspectDefinition.addAllAttributesOfAspect(allLabels, aspect, edgeFilter)
                    for (e in allLabels.entries()) {
                        enqueue(from, e.getKey(), e.getValue())
                    }
                }
            }
        }
    }
}
