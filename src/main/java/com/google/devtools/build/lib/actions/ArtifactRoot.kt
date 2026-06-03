// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.LabelConstants

/**
 * A root for an artifact. The roots are the directories containing artifacts, and they are mapped
 * together into a single directory tree to form the execution environment. There are two kinds of
 * roots: source roots and derived roots. Source roots correspond to entries of the package path,
 * and they can be anywhere on disk. Derived roots correspond to output directories; there are
 * generally different output directories for different configurations, and different types of
 * output (bin, genfiles, includes, etc.).
 * 
 * 
 * When mapping the roots into a single directory tree, the source roots are merged, such that
 * each package is accessed in its entirety from a single source root. The package cache is
 * responsible for determining that mapping. The derived roots, on the other hand, have to be
 * distinct. (It is currently allowed to have a derived root that is the prefix of another one.)
 * 
 * 
 * Derived roots must have paths that point inside the exec root ([ ][com.google.devtools.build.lib.analysis.BlazeDirectories.getExecRoot]), i.e. below the directory
 * that is the root of the merged directory tree.
 * 
 * 
 * For example, if
 * 
 * 
 *  * your workspace is `/home/my_workspace/`
 *  * your package path is `/home/my_workspace/` (the norm unless you're customizing [       ][com.google.devtools.build.lib.pkgcache.PackageOptions.packagePath]).
 *  * your workspace has a source file at `/home/my_workspace/myapp/source.go`
 *  * you build a binary that outputs `/home/my_workspace/bazel-out/x86-opt/bin/a/mybinary`
 * 
 * 
 * 
 * then
 * 
 * 
 *  * Bazel creates an "output base" directory `$OUTPUT_BASE` which it uses for staging
 * build work: [com.google.devtools.build.lib.analysis.BlazeDirectories.getOutputBase]
 *  * Bazel creates an exec root at `$OUTPUT_BASE/execroot/my_workspace/`. This symlinks
 * all files and directories under `/home/my_workspace/`. This is the working directory
 * where actions run (either directly for local execution or as the base for staging remote
 * execution paths). This is also the base directory for writing outputs.
 *  * `/home/my_workspace/myapp/source.go` is a [SourceArtifact] with source root
 * `/home/my_workspace/`
 *  * `/home/my_workspace/bazel-out/x86-opt/bin/a/mybinary` is a [DerivedArtifact].
 * Because derived artifacts are written under the exec root, `/home/my_workspace/bazel-out` is a symlink to `$EXEC_ROOT/bazel-out`. So `mybinary` is actually at `$EXEC_ROOT/bazel-out/x86-opt/bin/mybinary`. Its derived
 * root is therefore `$EXEC_ROOT/bazel-out/x86-opt/bin/`.
 * 
 * 
 * 
 * The "exec path" ([Artifact.getExecPath], [.getExecPath], etc.) is an entity's
 * path relative to the exec root. So `/home/my_workspace/myapp/source.go`'s exec path is
 * `myapp/source.go` and `/home/my_workspace/bazel-out/x86-opt/bin/a/mybinary`'s exec
 * path is `bazel-out/x86-opt/bin/a/mybinary`
 * 
 * 
 * The "root-relative path" ([Artifact.getRootRelativePath]) is a entity's path relative
 * to its root. So `/home/my_workspace/myapp/source.go`'s root-relative path is `myapp/source.go` and `/home/my_workspace/bazel-out/x86-opt/bin/a/mybinary`'s root-relative
 * path is `a/mybinary`.
 * 
 * 
 * For concrete examples, run `$ bazel info` in your terminal after a build. Also see [Bazel's output directory docs](https://bazel.build/remote/output-directories).
 */
@AutoCodec
@Immutable
class ArtifactRoot private constructor(root: Root?, execPath: PathFragment, rootType: RootType?) :
    Comparable<ArtifactRoot?>, FileRootApi, CommandLineItem {
    /**
     * ArtifactRoot types. Callers of asDerivedRoot methods need to specify which type of derived root
     * artifact they want to create, which is why this enum is public.
     */
    enum class RootType {
        MAIN_SOURCE,
        EXTERNAL_SOURCE,
        OUTPUT,

        // Sibling root types are in effect when --experimental_sibling_repository_layout is activated.
        // These will eventually replace the above Output types when the flag becomes the default option
        // and then removed.
        SIBLING_MAIN_OUTPUT,
        SIBLING_EXTERNAL_OUTPUT,
    }

    private val root: Root
    private val execPath: PathFragment
    private val rootType: RootType?

    init {
        this.root = com.google.common.base.Preconditions.checkNotNull<Root>(root)
        this.execPath = execPath
        this.rootType = rootType
    }

    public override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    fun getRoot(): Root {
        return root
    }

    /**
     * Returns the path fragment from the exec root to the actual root. For source roots, this returns
     * the empty fragment.
     */
    fun getExecPath(): PathFragment {
        return execPath
    }

    public override fun getExecPathString(): String {
        return execPath.getPathString()
    }

    fun getRootType(): RootType? {
        return rootType
    }

    fun isSourceRoot(): Boolean {
        return rootType == RootType.MAIN_SOURCE || rootType == RootType.EXTERNAL_SOURCE
    }

    fun isExternal(): Boolean {
        return rootType == RootType.EXTERNAL_SOURCE || rootType == RootType.SIBLING_EXTERNAL_OUTPUT
    }

    /**
     * Returns true if the ArtifactRoot is a legacy derived root type, i.e. a derived root type
     * created without the --experimental_sibling_repository_layout flag set.
     */
    fun isLegacy(): Boolean {
        return rootType == RootType.OUTPUT
    }

    override fun compareTo(o: ArtifactRoot): Int {
        return root.compareTo(o.root)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(root, execPath, rootType)
    }

    /**
     * The Root of a derived ArtifactRoot contains the exec path. In order to avoid duplicating that
     * path, and enable the Root to be serialized as a constant, we return the "exec root" Root here,
     * by stripping the exec path. That Root is likely to be serialized as a constant by [ ], saving a lot of serialized bytes on the wire.
     */
    @Suppress("unused")
    fun getRootForSerialization(): Root {
        if (!isOutputRootType(rootType)) {
            return root
        }
        // Find fragment of root that does not include execPath and return just that root. It is likely
        // to be serialized as a constant by RootCodec. For instance, if the original exec root was
        // /execroot, and this root was /execroot/bazel-out/bin, with execPath bazel-out/bin, then we
        // just serialize /execroot and bazel-out/bin separately.
        // We just want to strip execPath from root, but I don't know a trivial way to do that.
        val rootFragment: PathFragment = root.asPath().asFragment()
        return Root.fromPath(
            root.asPath()
                .getFileSystem()
                .getPath(
                    rootFragment.subFragment(
                        0, rootFragment.segmentCount() - execPath.segmentCount()
                    )
                )
        )
    }

    override fun equals(o: Any?): Boolean {
        if (o === this) {
            return true
        }
        if (o !is ArtifactRoot) {
            return false
        }
        return root.equals(o.root) && execPath.equals(o.execPath) && rootType == o.rootType
    }

    override fun toString(): String {
        return root.toString() + (if (isSourceRoot()) "[source]" else "[derived]")
    }

    public override fun repr(printer: Printer, semantics: StarlarkSemantics?) {
        printer.append(if (isSourceRoot()) "<source root>" else "<derived root>")
    }

    override fun expandToCommandLine(): String {
        return getExecPathString()
    }

    companion object {
        private val INTERNER: com.google.common.collect.Interner<ArtifactRoot> =
            com.google.common.collect.Interners.newWeakInterner<ArtifactRoot?>()

        /**
         * Do not use except in tests and in [ ].
         * 
         * 
         * Returns the given path as a source root. The path may not be `null`.
         */
        fun asSourceRoot(root: Root?): ArtifactRoot {
            return INTERNER.intern(
                ArtifactRoot(root, PathFragment.EMPTY_FRAGMENT, RootType.MAIN_SOURCE)
            )
        }

        /**
         * Do not use except in tests and in [ ].
         * 
         * 
         * Returns the given path as the external source root. The path should end with [ ] since the external repository root is always
         * $OUTPUT_BASE/external regardless of the layout of the exec root.
         */
        fun asExternalSourceRoot(root: Root): ArtifactRoot {
            com.google.common.base.Preconditions.checkArgument(
                root.asPath()
                    .asFragment()
                    .getParentDirectory()
                    .endsWith(LabelConstants.EXTERNAL_REPOSITORY_LOCATION)
            )
            return INTERNER.intern(
                ArtifactRoot(root, PathFragment.EMPTY_FRAGMENT, RootType.EXTERNAL_SOURCE)
            )
        }

        /**
         * Constructs an ArtifactRoot given the output prefixes. (eg, "bin"), and (eg, "testlogs")
         * relative to the execRoot.
         * 
         * 
         * Be careful with this method - all derived roots must be within the derived artifacts tree,
         * defined in ArtifactFactory (see [ArtifactFactory.isDerivedArtifact]).
         * 
         * 
         * Call [.asDerivedRoot] if you already have a [ ] instance for the exec path.
         */
        fun asDerivedRoot(execRoot: Path, rootType: RootType?, vararg prefixes: String): ArtifactRoot {
            var execPath: PathFragment = PathFragment.EMPTY_FRAGMENT
            for (prefix in prefixes) {
                // Tests can have empty segments here, be gentle to them.
                if (!prefix.isEmpty()) {
                    execPath = execPath.getChild(prefix)
                }
            }
            return Companion.asDerivedRoot(execRoot, rootType, execPath)
        }

        /**
         * Constructs an [ArtifactRoot] given the execPath, relative to the execRoot.
         * 
         * 
         * Be careful with this method - all derived roots must be within the derived artifacts tree,
         * defined in ArtifactFactory (see [ArtifactFactory.isDerivedArtifact]).
         */
        fun asDerivedRoot(
            execRoot: Path, rootType: RootType?, execPath: PathFragment
        ): ArtifactRoot {
            // Make sure that we are not creating a derived artifact under the execRoot.
            com.google.common.base.Preconditions.checkArgument(!execPath.isEmpty(), "empty execPath")
            com.google.common.base.Preconditions.checkArgument(
                !execPath.isAbsolute(),
                "execPath must be relative: %s",
                execPath
            )
            com.google.common.base.Preconditions.checkArgument(
                !execPath.containsUplevelReferences(),
                "execPath: %s contains parent directory reference (..)",
                execPath
            )
            com.google.common.base.Preconditions.checkArgument(
                isOutputRootType(rootType), "%s is not a derived root type", rootType
            )
            val root: Path? = execRoot.getRelative(execPath)
            return INTERNER.intern(ArtifactRoot(Root.fromPath(root), execPath, rootType))
        }

        @VisibleForSerialization
        @AutoCodec.Instantiator
        fun createForSerialization(
            rootForSerialization: Root, execPath: PathFragment, rootType: RootType?
        ): ArtifactRoot? {
            if (!isOutputRootType(rootType)) {
                return INTERNER.intern(ArtifactRoot(rootForSerialization, execPath, rootType))
            }
            return asDerivedRoot(rootForSerialization.asPath(), rootType, execPath)
        }

        private fun isOutputRootType(rootType: RootType?): Boolean {
            return rootType == RootType.SIBLING_MAIN_OUTPUT || rootType == RootType.SIBLING_EXTERNAL_OUTPUT || rootType == RootType.OUTPUT
        }
    }
}
