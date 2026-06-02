// Copyright 2015 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.LabelConstants
import com.google.devtools.build.lib.cmdline.LabelParser
import com.google.devtools.build.lib.cmdline.LabelParser.Parts
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.skyframe.SkyFunctions
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.util.HashCodes
import com.google.devtools.build.lib.vfs.PathFragment
import com.google.devtools.build.skyframe.SkyFunctionName
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyKey.SkyKeyInterner
import com.google.devtools.build.zip.ZipFileEntry.getName
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Uniquely identifies a package. Contains the (canonical) name of the repository this package lives
 * in, and the package's path fragment.
 * 
 * 
 * Used as a [SkyKey] to request a [ ].
 */
@javax.annotation.concurrent.Immutable
class PackageIdentifier private constructor(repository: RepositoryName?, pkgName: PathFragment?) : SkyKey,
    Comparable<PackageIdentifier?> {
    /**
     * The identifier for this repository. This is either "" or prefixed with an "@", e.g., "@myrepo".
     */
    private val repository: RepositoryName

    /** The name of the package.  */
    private val pkgName: PathFragment

    /**
     * Precomputed hash code. Hash/equality is based on repository and pkgName. Note that due to weak
     * interning, x.equals(y) usually implies x==y.
     */
    private val hashCode: Int

    init {
        this.repository = com.google.common.base.Preconditions.checkNotNull<RepositoryName>(repository)
        this.pkgName = com.google.common.base.Preconditions.checkNotNull<PathFragment>(pkgName)
        this.hashCode = HashCodes.hashObjects(repository, pkgName)
    }

    fun getRepository(): RepositoryName {
        return repository
    }

    val packageFragment: PathFragment
        get() = pkgName

    val sourceRoot: PathFragment
        /**
         * Returns a path to the source code for this package relative to the corresponding source root.
         * Returns pkgName for all repositories.
         */
        get() = pkgName

    val topLevelDir: String?
        /**
         * Get the top level dir after the root.
         * 
         * 
         * Used for some symlink planting strategies.
         */
        get() = if (this.sourceRoot.isEmpty()) "" else this.sourceRoot.getSegment(0)

    /**
     * Returns the package path fragment to derived artifacts for this package. Returns pkgName if
     * this is in the main repository or siblingRepositoryLayout is true. Otherwise, returns
     * external/[repository name]/[pkgName].
     */
    // TODO(bazel-team): Rename getDerivedArtifactPath or similar.
    fun getPackagePath(siblingRepositoryLayout: Boolean): PathFragment? {
        return if (repository.isMain() || siblingRepositoryLayout)
            pkgName
        else
            LabelConstants.EXTERNAL_PATH_PREFIX
                .getRelative(repository.getName())
                .getRelative(pkgName)
    }

    fun getExecPath(siblingRepositoryLayout: Boolean): PathFragment? {
        return repository.getExecPath(siblingRepositoryLayout).getRelative(pkgName)
    }

    val runfilesPath: PathFragment?
        /**
         * Returns the runfiles/execRoot path for this repository (relative to the x.runfiles/main-repo/
         * directory).
         */
        get() = repository.getRunfilesPath().getRelative(pkgName)

    val canonicalForm: String
        /**
         * Returns the package in label syntax format.
         * 
         * 
         * Packages in the main repo are formatted without a repo qualifier.
         */
        get() = repository.getCanonicalForm() + "//" + pkgName

    val unambiguousCanonicalForm: String
        /**
         * Returns an absolutely unambiguous canonical form for this package in label form. Parsing this
         * string in any environment, even when subject to repository mapping, should identify the same
         * package.
         */
        get() = repository.getNameWithAt() + "//" + pkgName

    /**
     * Returns a label representation for this package that is suitable for display. The returned
     * string is as simple as possible while referencing the current package when parsed in the
     * context of the main repository whose repository mapping is provided.
     * 
     * @param mainRepositoryMapping the [RepositoryMapping] of the main repository
     * @return
     * <dl>
     * <dt>`//some/pkg`
    </dt> * <dd>if this package lives in the main repository
    </dd> * <dt>`@protobuf//some/pkg`
    </dt> * <dd>if this package lives in a repository with "protobuf" as apparent name of a
     * dependency of the main module
    </dd> * <dt>`@@protobuf+//some/pkg`
    </dt> * <dd>if the current package belongs to a repository that is not visible from the main
     * module
    </dd></dl> */
    fun getDisplayForm(mainRepositoryMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?): String {
        return repository.getDisplayForm(mainRepositoryMapping) + "//" + pkgName
    }

    override fun functionName(): SkyFunctionName {
        return SkyFunctions.PACKAGE
    }

    val skyKeyInterner: SkyKeyInterner<*>
        get() = interner

    /**
     * Returns the package path, possibly qualified with a repository name.
     * 
     * 
     * Packages that live in the main repo are stringified without a "@" qualifier or "//"
     * separator (e.g. "foo/bar"). All other packages include these (e.g. "@repo//foo/bar").
     */
    // TODO(bazel-team): The absence of "//" for the main repo seems strange. Can we eliminate
    // that disparity?
    override fun toString(): String {
        if (repository.isMain()) {
            return pkgName.getPathString()
        }
        return this.canonicalForm
    }

    override fun equals(`object`: Any?): Boolean {
        if (this === `object`) {
            return true
        }
        if (`object` !is PackageIdentifier) {
            return false
        }
        val that = `object`
        return this.hashCode == that.hashCode && pkgName == that.pkgName
                && repository == that.repository
    }

    override fun hashCode(): Int {
        return this.hashCode
    }

    // Performance optimization.
    override fun compareTo(that: PackageIdentifier): Int {
        // Fast-paths for the common case of the same package or a package in the same repository.
        if (this === that) {
            return 0
        }
        if (repository === that.repository) {
            return pkgName.compareTo(that.pkgName)
        }
        return com.google.common.collect.ComparisonChain.start()
            .compare(repository.getName(), that.repository.getName())
            .compare(pkgName, that.pkgName)
            .result()
    }

    private class PackageIdentifierDeferredCodec

        : DeferredObjectCodec<PackageIdentifier?>() {
        override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<PackageIdentifier?>
            get() = PackageIdentifier::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: PackageIdentifier?, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf<PackageIdentifier?>(obj, packageIdentifierCodec(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<PackageIdentifier?> {
            val value: PackageIdentifier? =
                context.deserializeLeaf<PackageIdentifier?>(codedIn, packageIdentifierCodec())
            return DeferredValue { value }
        }

        companion object {
            private val INSTANCE = PackageIdentifierDeferredCodec()
        }
    }

    private class PackageIdentifierLeafCodec : LeafObjectCodec<PackageIdentifier?>() {
        val encodedClass: java.lang.Class<PackageIdentifier?>
            get() = PackageIdentifier::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: LeafSerializationContext, obj: PackageIdentifier, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf<RepositoryName?>(
                obj.getRepository(),
                RepositoryName.Companion.repositoryNameCodec(),
                codedOut
            )
            context.serializeLeaf<PathFragment?>(obj.packageFragment, PathFragment.pathFragmentCodec(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(
            context: LeafDeserializationContext, codedIn: CodedInputStream?
        ): PackageIdentifier? {
            val repository: RepositoryName? =
                context.deserializeLeaf<RepositoryName?>(codedIn, RepositoryName.Companion.repositoryNameCodec())
            val pkgName: PathFragment? =
                context.deserializeLeaf<PathFragment?>(codedIn, PathFragment.pathFragmentCodec())
            return PackageIdentifier.Companion.create(repository, pkgName)
        }

        companion object {
            private val INSTANCE = PackageIdentifierLeafCodec()
        }
    }

    companion object {
        private val interner: SkyKeyInterner<PackageIdentifier?> = SkyKey.newInterner<PackageIdentifier?>()

        @Throws(LabelSyntaxException::class)
        fun create(repository: String, pkgName: PathFragment?): PackageIdentifier? {
            return Companion.create(RepositoryName.Companion.create(repository), pkgName)
        }

        fun create(repository: RepositoryName?, pkgName: PathFragment?): PackageIdentifier? {
            return interner.intern(PackageIdentifier(repository, pkgName))
        }

        /** Creates `PackageIdentifier` from a known-valid string.  */
        @kotlin.jvm.JvmStatic
        fun createUnchecked(repository: String, pkgName: String?): PackageIdentifier? {
            return Companion.create(
                RepositoryName.Companion.createUnvalidated(repository),
                PathFragment.create(pkgName)
            )
        }

        @kotlin.jvm.JvmField
        val EMPTY_PACKAGE_ID: PackageIdentifier? = Companion.createInMainRepo(PathFragment.EMPTY_FRAGMENT)

        @kotlin.jvm.JvmStatic
        fun createInMainRepo(name: String?): PackageIdentifier? {
            return Companion.createInMainRepo(PathFragment.create(name))
        }

        fun createInMainRepo(name: PathFragment?): PackageIdentifier? {
            return Companion.create(RepositoryName.Companion.MAIN, name)
        }

        fun createRootPackage(repository: RepositoryName?): PackageIdentifier? {
            return Companion.create(repository, PathFragment.EMPTY_FRAGMENT)
        }

        /**
         * Tries to infer the package identifier from the given exec path. This method does not perform
         * any I/O, but looks solely at the structure of the exec path. The resulting identifier may
         * actually be a subdirectory of a package rather than a package, e.g.:
         * 
         * <pre>`
         * + WORKSPACE
         * + foo/BUILD
         * + foo/bar/bar.java
        `</pre> * 
         * 
         * In this case, this method returns a package identifier for foo/bar, even though that is not a
         * package. Callers need to look up the actual package if needed.
         * 
         * 
         * Returns [Optional.empty] if the path corresponds to an invalid label (e.g. with a
         * malformed repo name).
         */
        fun discoverFromExecPath(
            execPath: PathFragment, forFiles: Boolean, siblingRepositoryLayout: Boolean
        ): java.util.Optional<PackageIdentifier?> {
            com.google.common.base.Preconditions.checkArgument(!execPath.isAbsolute(), execPath)
            val tofind: PathFragment =
                if (forFiles)
                    com.google.common.base.Preconditions.checkNotNull<PathFragment>(
                        execPath.getParentDirectory(), "Must pass in files, not root directory"
                    )
                else
                    execPath
            val prefix: PathFragment? =
                if (siblingRepositoryLayout)
                    LabelConstants.EXPERIMENTAL_EXTERNAL_PATH_PREFIX
                else
                    LabelConstants.EXTERNAL_PATH_PREFIX
            if (tofind.startsWith(prefix)) {
                // Using the path prefix can be either "external" or "..", depending on whether the sibling
                // repository layout is used.
                try {
                    val repository: RepositoryName? = RepositoryName.Companion.create(tofind.getSegment(1))
                    return java.util.Optional.of<PackageIdentifier?>(
                        Companion.create(
                            repository,
                            tofind.subFragment(2)
                        )
                    )
                } catch (e: LabelSyntaxException) {
                    // The path corresponds to an invalid label.
                    return java.util.Optional.empty<PackageIdentifier?>()
                }
            } else {
                return java.util.Optional.of<PackageIdentifier?>(Companion.createInMainRepo(tofind))
            }
        }

        @kotlin.jvm.JvmStatic
        @Throws(LabelSyntaxException::class)
        fun parse(input: String): PackageIdentifier? {
            if (input.contains(":")) {
                throw LabelParser.syntaxErrorf("invalid package identifier '%s': contains ':'", input)
            }
            val parts: Parts = Parts.Companion.parse(input + ":dummy_target")
            val repoName: RepositoryName? =
                if (parts.repo() == null) RepositoryName.Companion.MAIN else RepositoryName.Companion.createUnvalidated(
                    parts.repo()
                )
            return Companion.create(repoName, PathFragment.create(parts.pkg()))
        }

        fun packageIdentifierCodec(): PackageIdentifierLeafCodec {
            return PackageIdentifierLeafCodec.Companion.INSTANCE
        }

        @kotlin.jvm.JvmStatic
        fun deferredCodec(): DeferredObjectCodec<PackageIdentifier?> {
            return PackageIdentifierDeferredCodec.Companion.INSTANCE
        }
    }
}
