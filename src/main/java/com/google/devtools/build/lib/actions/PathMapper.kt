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
package com.google.devtools.build.lib.actions

import com.google.devtools.build.lib.starlarkbuildapi.FileRootApi

/**
 * Support for mapping config parts of exec paths in an action's command line as well as when
 * staging its inputs and outputs for execution, with the aim of making the resulting [Spawn]
 * more cacheable.
 * 
 * 
 * Actions that want to support path mapping should use [ ].
 * 
 * 
 * An example of an implementing class is [ ], which removes the config
 * part (e.g. "k8-fastbuild") from exec paths to allow for cross-configuration cache hits.
 */
interface PathMapper {
    /**
     * Creates a new [StarlarkSemantics] instance which causes all Starlark threads using it to
     * automatically apply this [PathMapper] to all struct fields of [ ].
     * 
     * 
     * This is meant to be used when evaluating user-defined callbacks to Starlark variants of
     * custom command lines that are evaluated during the execution phase.
     * 
     * 
     * Since any unmapped path appearing in a command line will prevent cross-configuration cache
     * hits, this mapping is applied automatically instead of requiring users to explicitly map all
     * paths themselves. As an added benefit, this allows actions to opt into path mapping without
     * actual changes to their command line code.
     */
    @com.google.errorprone.annotations.CheckReturnValue
    fun storeIn(semantics: StarlarkSemantics): StarlarkSemantics? {
        // This in particular covers the case where the semantics do not have a path mapper yet and this
        // is NOOP.
        if (semantics.get(PathMapperConstants.SEMANTICS_KEY) === this) {
            return semantics
        }
        return object : StarlarkSemantics(
            semantics.toBuilder().set(PathMapperConstants.SEMANTICS_KEY, this).build()
        ) {
            // The path mapper doesn't affect which fields or methods are available on any given Starlark
            // object; it just affects the behavior of certain methods on Artifact. We thus preserve the
            // original semantics as a cache key. Otherwise, even if PathMapper#equals returned true for
            // each two non-NOOP instances, cache lookups in CallUtils would result in frequent
            // comparisons of equal but not reference equal semantics maps, which regresses CPU (~7% on
            // a benchmark with ~10 semantics options).
            public override fun getBuiltinManagerCacheKey(): StarlarkSemantics {
                return semantics
            }
        }
    }

    /**
     * Returns the exec path with the path mapping applied.
     * 
     * 
     * Path mappers may return paths with different roots for two paths that have the same root
     * (e.g., they may map an artifact at `bazel-out/k8-fastbuild/bin/pkg/foo` to `bazel-out/<hash of the file>/bin/pkg/foo`). Paths of artifacts that should share the same
     * parent directory, such as runfiles or tree artifact files, should thus be derived from the
     * mapped path of their parent.
     */
    fun map(execPath: PathFragment?): PathFragment

    /** Returns the exec path of the input with the path mapping applied.  */
    fun getMappedExecPathString(artifact: ActionInput): String {
        return map(artifact.getExecPath()).getPathString()
    }

    /**
     * Returns the difference `artifact.getExecPathString().length() - getMappedExecPathString(artifact).length()`, i.e., the unmapped path length minus the mapped
     * path length.
     * 
     * 
     * Implementations should provide a more efficient implementation that avoids allocations.
     */
    fun computeExecPathLengthDiff(artifact: DerivedArtifact): Int {
        return artifact.getExecPathString().length() - getMappedExecPathString(artifact).length()
    }

    /**
     * We don't yet have a Starlark API for mapping paths in command lines. Simple Starlark calls like
     * `args.add(arg_name, file_path` are automatically handled. But calls that involve custom
     * Starlark code require deeper API support that remains a TODO.
     * 
     * 
     * This method allows implementations to hard-code support for specific command line entries
     * for specific Starlark actions.
     */
    fun mapCustomStarlarkArgs(chunk: ArgChunk?): ArgChunk? {
        return chunk
    }

    /**
     * Returns the [MapFn] to apply to a vector argument with the given previous String argument
     * in a [com.google.devtools.build.lib.analysis.actions.CustomCommandLine].
     * 
     * 
     * For example, if the previous argument is `"--foo"`, this method should return a [ ] that maps the next arguments to the correct path, potentially mapping them if "--foo"
     * requires it.
     * 
     * 
     * This is used to map paths obtained via location expansion in native rules, which returns a
     * list of strings rather than a structured command line.
     * 
     * 
     * By default, this method returns [MapFn.DEFAULT].
     */
    fun getMapFn(previousFlag: String?): ExceptionlessMapFn<Any?>? {
        return MapFn.Companion.DEFAULT
    }

    /** Heuristically maps all path-like strings in the given argument.  */
    fun mapHeuristically(arg: String?): String? {
        return arg
    }

    /**
     * Returns a [FileRootApi] representing the new root of the given artifact after mapping.
     * 
     * 
     * All objects returned by this method must be [Comparable] among each other.
     */
    fun mapRoot(artifact: Artifact): FileRootApi? {
        val root: ArtifactRoot = artifact.getRoot()
        if (root.isSourceRoot()) {
            // Source roots' paths are never mapped, but we still need to wrap them in a
            // MappedArtifactRoot to ensure correct Starlark comparison behavior.
            return PathMapperConstants.mappedSourceRoots.get(root)
        }
        // It would *not* be correct to just apply #map to the exec path of the root: The root part of
        // the mapped exec path of this artifact may depend on its complete exec path as well as on e.g.
        // the digest of the artifact.
        val execPath: PathFragment = artifact.getExecPath()
        val mappedExecPath: PathFragment = map(execPath)
        // map never changes the root-relative part of the exec path, so we can remove that suffix to
        // get the mapped root part.
        val rootRelativeSegmentCount: Int = execPath.segmentCount() - root.getExecPath().segmentCount()
        val mappedRootExecPath: PathFragment =
            mappedExecPath.subFragment(0, mappedExecPath.segmentCount() - rootRelativeSegmentCount)
        return MappedArtifactRoot(mappedRootExecPath)
    }

    /**
     * Returns `true` if the mapper is known to map all paths identically.
     * 
     * 
     * Can be used by actions to skip additional work that isn't needed if path mapping is not
     * enabled.
     */
    fun isNoop(): Boolean {
        return this === NOOP
    }

    /**
     * Returns an opaque object whose equality class should encode all information that goes into the
     * behavior of the [.map] function of this path mapper. This is used as a key
     * for in-memory caches.
     * 
     * 
     * The default implementation returns the [Class] of the path mapper.
     */
    fun cacheKey(): Any {
        return this.getClass()
    }

    /** A [FileRootApi] returned by [PathMapper.mapRoot].  */
    @StarlarkBuiltin(
        name = "mapped_root",
        category = com.google.devtools.build.docgen.annot.DocCategory.BUILTIN,
        doc = "A root for files that have been subject to path mapping"
    )
    class MappedArtifactRoot(mappedRootExecPath: PathFragment) : FileRootApi, Comparable<MappedArtifactRoot?> {
        private val mappedRootExecPath: PathFragment

        init {
            this.mappedRootExecPath = mappedRootExecPath
        }

        public override fun getExecPathString(): String {
            return mappedRootExecPath.getPathString()
        }

        override fun compareTo(otherRoot: MappedArtifactRoot): Int {
            return mappedRootExecPath.compareTo(otherRoot.mappedRootExecPath)
        }

        override fun equals(obj: Any?): Boolean {
            // Per the contract of PathMapper#map, mapped roots never have exec paths that are equal to
            // exec paths of non-mapped roots, that is, of instances of ArtifactRoot. Thus, it is correct
            // for both equals implementations to return false if the other object is not an instance of
            // the respective class.
            if (obj !is MappedArtifactRoot) {
                return false
            }
            return mappedRootExecPath.equals(obj.mappedRootExecPath)
        }

        override fun hashCode(): Int {
            return mappedRootExecPath.hashCode()
        }

        override fun toString(): String {
            return mappedRootExecPath.toString() + " [mapped]"
        }

        public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
            printer.append("<mapped root>")
        }

        public override fun isImmutable(): Boolean {
            return true
        }
    }

    companion object {
        /**
         * Retrieve the [PathMapper] instance stored in the given [StarlarkSemantics] via
         * [.storeIn].
         */
        fun loadFrom(semantics: StarlarkSemantics): PathMapper {
            return semantics.get(PathMapperConstants.SEMANTICS_KEY)
        }

        /** Returns the instance to use during action key computation.  */
        fun forActionKey(effectiveOutputPathsMode: OutputPathsMode?): PathMapper {
            return if (effectiveOutputPathsMode == CoreOptions.OutputPathsMode.OFF)
                NOOP
            else
                PathMapperConstants.FOR_FINGERPRINTING
        }

        /** A [PathMapper] that doesn't change paths.  */
        @kotlin.jvm.JvmField
        val NOOP: PathMapper = object : PathMapper {
            override fun map(execPath: PathFragment?): PathFragment? {
                return execPath
            }

            override fun getMappedExecPathString(artifact: ActionInput): String? {
                return artifact.getExecPathString()
            }

            override fun computeExecPathLengthDiff(artifact: DerivedArtifact?): Int {
                return 0
            }

            override fun mapRoot(artifact: Artifact): FileRootApi {
                return artifact.getRoot()
            }
        }
    }
}
