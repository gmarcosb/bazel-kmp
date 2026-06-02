// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.FileStateValue.NONEXISTENT_FILE_STATE_NODE

/** A class that prepares the active directories to run the core SkyframeFocuser algorithm.  */
object SkyfocusExecutor {
    /**
     * Prepares the active directories to run the core SkyframeFocuser algorithm.
     * 
     * 
     * This method will update the active directories if the user has requested new active
     * directories from the command line, or if the user has not requested new active directories,
     * automatically derive it using the source state.
     * 
     * @return an optional of a SkyfocusState. If the value is present, the active directories has
     * been updated.
     */
    fun prepareActiveDirectories(
        topLevelTargetLabels: MutableCollection<Label?>,
        activeDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>,
        evaluator: InMemoryMemoizingEvaluator,
        skyfocusState: SkyfocusState,
        packageManager: PackageManager,
        pkgLocator: PathPackageLocator,
        eventHandler: ExtendedEventHandler
    ): java.util.Optional<SkyfocusState?> {
        com.google.common.base.Preconditions.checkState(
            !topLevelTargetLabels.isEmpty(),
            "Cannot prepare active directories without top level targets to focus on."
        )

        val newSkyfocusStateBuilder: com.google.devtools.build.lib.skyframe.SkyfocusState.Builder =
            skyfocusState.toBuilder()
                .focusedTargetLabels(
                    com.google.common.collect.ImmutableSet.builder<Label?>() // Persist previous focused labels.
                        .addAll(skyfocusState.focusedTargetLabels)
                        .addAll(topLevelTargetLabels)
                        .build()
                )

        val newActiveDirectories: MutableSet<FileStateKey?> =
            com.google.common.collect.Sets.newConcurrentHashSet<FileStateKey?>()

        if (skyfocusState.options.getActiveDirectories().isEmpty()
            && skyfocusState.activeDirectoriesType == ActiveDirectoriesType.DERIVED
        ) {
            // If the user hasn't defined a new active directories from the command line and there
            // isn't an active user-defined active directories in use, automatically derive one using the
            // targets being built.
            Profiler.instance().profile("Skyfocus derive active directories").use { c ->
                eventHandler.handle(Event.info("Skyfocus: automatically deriving active directories."))
                val topLevelTargetPackages: com.google.common.collect.ImmutableSet<PathFragment?> =
                    topLevelTargetLabels.stream().map<Any?>(Label::getPackageFragment)
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

                // For each FSK, add to the active directories if the FSK's parent dir shares the same
                // package as one of the top level targets.
                evaluator
                    .getInMemoryGraph()
                    .parallelForEach(
                        java.util.function.Consumer { node: InMemoryNodeEntry? ->
                            if (node.getKey() is FileStateKey) {
                                com.google.common.base.Preconditions.checkState(
                                    node.isDone(),
                                    "FileState node is not done. This is an internal inconsistency."
                                )
                                if (node.getValue() == NONEXISTENT_FILE_STATE_NODE) {
                                    return@parallelForEach
                                }

                                if (activeDirectoriesMatcher.isPresent()) {
                                    // Check if the file belongs to the given active directories prefixes.
                                    if (activeDirectoriesMatcher
                                            .get()
                                            .includes(fileStateKey.argument().getRootRelativePath())
                                    ) {
                                        newActiveDirectories.add(fileStateKey.argument())
                                    }
                                    return@parallelForEach
                                }

                                // If project directories are not defined, check if the file belongs to the
                                // package of a top level target being built.
                                var currPath: PathFragment? = fileStateKey.argument().getRootRelativePath()
                                while (currPath != null) {
                                    try {
                                        if (packageManager.isPackage(
                                                eventHandler,
                                                PackageIdentifier.create(RepositoryName.MAIN, currPath)
                                            )
                                        ) {
                                            if (topLevelTargetPackages.contains(currPath)) {
                                                newActiveDirectories.add(fileStateKey.argument())
                                            }
                                            break
                                        }
                                    } catch (e: InconsistentFilesystemException) {
                                        throw java.lang.IllegalStateException(e)
                                    } catch (e: java.lang.InterruptedException) {
                                        // Swallow interrupted exceptions at this level, since this is probably from
                                        // the main thread, and so there's not much else to do here.
                                        //
                                        // If this is a stray SIGINT, then we can't do much here either.
                                    }

                                    // traverse up the path until we find a valid package
                                    currPath = currPath.getParentDirectory()
                                }
                            }
                        })

                if (!skyfocusState.forcedRerun && skyfocusState.activeDirectories.containsAll(newActiveDirectories)
                    && skyfocusState.focusedTargetLabels.containsAll(topLevelTargetLabels)
                ) {
                    // Already focused on a superset of the active directories, no need to do anything.
                    return java.util.Optional.empty<SkyfocusState?>()
                }
                newSkyfocusStateBuilder
                    .activeDirectoriesType(ActiveDirectoriesType.DERIVED)
                    .activeDirectories(
                        com.google.common.collect.ImmutableSet.builder<FileStateKey?>()
                            .addAll( // Only persist previously derived active directoriess. If they were
                                // user defined, overwrite them.
                                if (skyfocusState.activeDirectoriesType == ActiveDirectoriesType.DERIVED)
                                    skyfocusState.activeDirectories
                                else
                                    com.google.common.collect.ImmutableSet.of<FileStateKey?>()
                            )
                            .addAll(newActiveDirectories)
                            .build()
                    )
            }
        } else {
            if (skyfocusState.options.getActiveDirectories().isEmpty()
                && !skyfocusState.forcedRerun
            ) {
                // No command line request to update the active directories; return early.
                return java.util.Optional.empty<SkyfocusState?>()
            }

            // User is setting a new explicit active directories from the command line option.
            // This will override any previously defined active directories.
            val activeDirectoriesRootedPaths: com.google.common.collect.ImmutableSet<RootedPath?> =
                java.util.stream.Stream.concat<T?>(
                    java.util.stream.Stream.concat<T?>(
                        skyfocusState.options.getActiveDirectories()
                            .stream(),  // The Bzlmod lockfile can be created after a build without having existed
                        // before and must always be kept in the active directories if it is used.
                        java.util.stream.Stream.of(LabelConstants.MODULE_LOCKFILE_NAME.toString())
                    )
                        .map<RootedPath?> { k: T? -> toFileStateKey(pkgLocator, k) },
                    java.util.stream.Stream.of(
                        RootedPath.toRootedPath(
                            Root.fromPath(pkgLocator.getOutputBase()),
                            LabelConstants.MODULE_LOCKFILE_NAME
                        )
                    )
                )
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
            evaluator
                .getInMemoryGraph()
                .parallelForEach(
                    java.util.function.Consumer { node: InMemoryNodeEntry? ->
                        if (node.getKey() is FileStateKey) {
                            val rootedPath: RootedPath? = fileStateKey.argument()
                            if (activeDirectoriesRootedPaths.contains(rootedPath)) {
                                newActiveDirectories.add(fileStateKey)
                            }
                        }
                    })

            val missingCount: Int = activeDirectoriesRootedPaths.size - newActiveDirectories.size
            if (missingCount > 0) {
                eventHandler.handle(
                    Event.warn(
                        (missingCount
                            .toString() + " files were not found in the transitive closure, and so they are not"
                                + " included in the active directories. They are: "
                                + activeDirectoriesRootedPaths.stream()
                            .filter(java.util.function.Predicate.not<RootedPath?>(java.util.function.Predicate { o: RootedPath? ->
                                newActiveDirectories.contains(
                                    o
                                )
                            }))
                            .map<String?> { r: RootedPath? -> r.getRootRelativePath().toString() }
                            .collect(Collectors.joining(", ")))))
            }

            if ((skyfocusState.options.getActiveDirectories().isEmpty()
                        || skyfocusState.activeDirectories == newActiveDirectories)
                && skyfocusState.focusedTargetLabels.containsAll(topLevelTargetLabels)
            ) {
                if (skyfocusState.forcedRerun) {
                    newActiveDirectories.addAll(skyfocusState.activeDirectories)
                } else {
                    return java.util.Optional.empty<SkyfocusState?>()
                }
            }

            newSkyfocusStateBuilder
                .activeDirectoriesType(ActiveDirectoriesType.USER_DEFINED)
                .activeDirectories(com.google.common.collect.ImmutableSet.copyOf<FileStateKey?>(newActiveDirectories))
        }

        eventHandler.handle(Event.info("Updated active directories successfully."))
        return java.util.Optional.of<SkyfocusState?>(newSkyfocusStateBuilder.build())
    }

    @Throws(java.lang.InterruptedException::class)
    fun execute(
        activeDirectories: com.google.common.collect.ImmutableSet<FileStateKey?>,
        evaluator: InMemoryMemoizingEvaluator,
        eventHandler: ExtendedEventHandler,
        actionCache: ActionCache?
    ): FocusResult {
        val roots: MutableSet<SkyKey?> = evaluator.getLatestTopLevelEvaluations()
        com.google.common.base.Preconditions.checkState(
            roots != null && !roots.isEmpty(), "Skyfocus needs roots, so it can't be null or empty."
        )

        val leafs: com.google.common.collect.ImmutableSet<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>() // TODO: b/312819241 - BUILD_ID is necessary for build correctness of volatile actions,
                // like stamping, but retains a lot of memory (100MB of retained heap for a 9+GB build).
                // Figure out a way to not include it.
                .add(PrecomputedValue.BUILD_ID.getKey())
                .addAll(activeDirectories)
                .build()

        eventHandler.handle(
            Event.info(
                String.format(
                    "Focusing on %d roots, %d leafs... (use --experimental_skyfocus_dump_keys to show"
                            + " them)",
                    roots.size, leafs.size
                )
            )
        )

        val focusResult: FocusResult

        Profiler.instance().profile("SkyframeFocuser").use { c ->
            focusResult = SkyframeFocuser.Companion.focus(evaluator.getInMemoryGraph(), actionCache, roots, leafs)
        }
        return focusResult
    }

    /** Turns a root relative path string into a RootedPath object.  */
    fun toFileStateKey(pkgLocator: PathPackageLocator, rootRelativePathFragment: String?): RootedPath? {
        // For simplicity's sake, use the first --package_path as the root. This
        // may be an issue with packages from a different package_path root.
        // TODO: b/312819241  - handle multiple package_path roots.
        val packageRoot: Root? = pkgLocator.getPathEntries().get(0)
        return RootedPath.toRootedPath(packageRoot, PathFragment.create(rootRelativePathFragment))
    }
}
