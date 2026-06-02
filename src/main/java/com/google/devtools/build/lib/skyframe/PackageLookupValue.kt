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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label
import com.google.devtools.build.lib.skyframe.PackageLookupValue.Companion.key

/**
 * A value that represents a package lookup result.
 * 
 * 
 * Package lookups will always produce a value. On success, the `#getRoot` returns the
 * package path root under which the package resides and the package's BUILD file is guaranteed to
 * exist (unless this is looking up a WORKSPACE file, in which case the underlying file may or may
 * not exist. On failure, `#getErrorReason` and `#getErrorMsg` describe why the package
 * doesn't exist.
 * 
 * 
 * Implementation detail: we use inheritance here to optimize for memory usage.
 */
abstract class PackageLookupValue protected constructor() : SkyValue {
    internal enum class ErrorReason {
        /** There is no BUILD file.  */
        NO_BUILD_FILE,

        /** The package name is invalid.  */
        INVALID_PACKAGE_NAME,

        /** The package is considered deleted because of --deleted_packages.  */
        DELETED_PACKAGE,

        /** The repository was not found.  */
        REPOSITORY_NOT_FOUND,
    }

    /**
     * For a successful package lookup, returns the root (package path entry) that the package resides
     * in.
     */
    @kotlin.jvm.JvmField
    abstract val root: Root?

    /** For a successful package lookup, returns the build file name that the package uses.  */
    @kotlin.jvm.JvmField
    abstract val buildFileName: BuildFileName?

    /** Returns whether the package lookup was successful.  */
    abstract fun packageExists(): Boolean

    /**
     * For a successful package lookup, returns the [RootedPath] for the build file that defines
     * the package.
     */
    fun getRootedPath(packageIdentifier: PackageIdentifier?): RootedPath? {
        return RootedPath.toRootedPath(
            this.root, this.buildFileName.getBuildFileFragment(packageIdentifier)
        )
    }

    /**
     * For an unsuccessful package lookup, gets the reason why [.packageExists] returns `false`.
     */
    @kotlin.jvm.JvmField
    abstract val errorReason: ErrorReason?

    /**
     * For an unsuccessful package lookup, gets a detailed error message for [.getErrorReason]
     * that is suitable for reporting to a user.
     */
    @kotlin.jvm.JvmField
    abstract val errorMsg: String?

    /** [SkyKey] for [PackageLookupValue] computation.  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: PackageIdentifier?) : AbstractSkyKey<PackageIdentifier?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PACKAGE_LOOKUP
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.PackageLookupValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: PackageIdentifier?): Key {
                return com.google.devtools.build.lib.skyframe.PackageLookupValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.PackageLookupValue.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.PackageLookupValue.Key.Companion.interner.intern(key)
            }
        }
    }

    /** Successful lookup value for a package in the main repo.  */
    @AutoCodec
    class SuccessfulPackageLookupValue internal constructor(root: Root, buildFileName: BuildFileName) :
        PackageLookupValue() {
        private val root: Root
        private val buildFileName: BuildFileName

        init {
            this.root = root
            this.buildFileName = buildFileName
        }

        override fun packageExists(): Boolean {
            return true
        }

        override fun getRoot(): Root {
            return root
        }

        override fun getBuildFileName(): BuildFileName {
            return buildFileName
        }

        override fun getErrorReason(): ErrorReason? {
            throw java.lang.IllegalStateException()
        }

        override fun getErrorMsg(): String? {
            throw java.lang.IllegalStateException()
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is SuccessfulPackageLookupValue) {
                return false
            }
            return root == obj.root && buildFileName === obj.buildFileName
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(root.hashCode(), buildFileName.hashCode())
        }

        companion object {
            private val INTERNER: com.google.common.collect.Interner<SuccessfulPackageLookupValue> =
                BlazeInterners.newWeakInterner()

            @AutoCodec.Instantiator
            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            fun create(root: Root, buildFileName: BuildFileName): SuccessfulPackageLookupValue {
                // In practice there will be very few unique values. Most successful package lookups succeed
                // against the first root (maybe there's only a single root!), there are only a few possible
                // build file names (for Blaze there's just one!).
                return INTERNER.intern(SuccessfulPackageLookupValue(root, buildFileName))
            }
        }
    }

    private abstract class UnsuccessfulPackageLookupValue : PackageLookupValue() {
        override fun packageExists(): Boolean {
            return false
        }

        override fun getRoot(): Root? {
            throw java.lang.IllegalStateException()
        }

        override fun getBuildFileName(): BuildFileName? {
            throw java.lang.IllegalStateException()
        }
    }

    /** Marker value for no build file found.  */
    class NoBuildFilePackageLookupValue private constructor() : UnsuccessfulPackageLookupValue() {
        override fun getErrorReason(): ErrorReason {
            return ErrorReason.NO_BUILD_FILE
        }

        override fun getErrorMsg(): String {
            return "BUILD file not found on package path"
        }
    }

    /** Value indicating the package name was in error.  */
    class InvalidNamePackageLookupValue internal constructor(private val errorMsg: String) :
        UnsuccessfulPackageLookupValue() {
        override fun getErrorReason(): ErrorReason {
            return ErrorReason.INVALID_PACKAGE_NAME
        }

        override fun getErrorMsg(): String {
            return errorMsg
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is InvalidNamePackageLookupValue) {
                return false
            }
            return errorMsg == obj.errorMsg
        }

        override fun hashCode(): Int {
            return errorMsg.hashCode()
        }

        override fun toString(): String {
            return java.lang.String.format("%s: %s", this.getClass().getSimpleName(), this.errorMsg)
        }
    }

    /** Value indicating the package name was in error.  */
    class IncorrectRepositoryReferencePackageLookupValue
    internal constructor(invalidPackageIdentifier: PackageIdentifier?, correctedPackageIdentifier: PackageIdentifier) :
        UnsuccessfulPackageLookupValue() {
        private val invalidPackageIdentifier: PackageIdentifier?
        private val correctedPackageIdentifier: PackageIdentifier

        init {
            this.invalidPackageIdentifier = invalidPackageIdentifier
            this.correctedPackageIdentifier = correctedPackageIdentifier
        }

        fun getInvalidPackageIdentifier(): PackageIdentifier? {
            return invalidPackageIdentifier
        }

        fun getCorrectedPackageIdentifier(): PackageIdentifier {
            return correctedPackageIdentifier
        }

        override fun getErrorReason(): ErrorReason {
            return ErrorReason.INVALID_PACKAGE_NAME
        }

        override fun getErrorMsg(): String? {
            return java.lang.String.format(
                "Invalid package reference %s crosses into repository %s:"
                        + " did you mean to use %s instead?",
                invalidPackageIdentifier,
                correctedPackageIdentifier.getRepository(),
                correctedPackageIdentifier
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is IncorrectRepositoryReferencePackageLookupValue) {
                return false
            }
            return com.google.common.base.Objects.equal(invalidPackageIdentifier, obj.invalidPackageIdentifier)
                    && com.google.common.base.Objects.equal(correctedPackageIdentifier, obj.correctedPackageIdentifier)
        }

        override fun hashCode(): Int {
            return com.google.common.base.Objects.hashCode(invalidPackageIdentifier, correctedPackageIdentifier)
        }

        override fun toString(): String {
            return java.lang.String.format(
                "%s: invalidPackageIdentifier: %s, corrected: %s",
                this.getClass().getSimpleName(),
                this.invalidPackageIdentifier,
                this.correctedPackageIdentifier
            )
        }
    }

    /** Marker value for a deleted package.  */
    class DeletedPackageLookupValue private constructor() : UnsuccessfulPackageLookupValue() {
        override fun getErrorReason(): ErrorReason {
            return ErrorReason.DELETED_PACKAGE
        }

        override fun getErrorMsg(): String {
            return "Package is considered deleted due to --deleted_packages"
        }
    }

    /**
     * Value for repository we could not find. This can happen when looking for a label that specifies
     * a non-existent repository.
     */
    class NoRepositoryPackageLookupValue internal constructor(repositoryName: RepositoryName?, reason: String?) :
        UnsuccessfulPackageLookupValue() {
        private val repositoryName: RepositoryName?
        private val reason: String?

        init {
            this.repositoryName = repositoryName
            this.reason = reason
        }

        override fun getErrorReason(): ErrorReason {
            return ErrorReason.REPOSITORY_NOT_FOUND
        }

        override fun getErrorMsg(): String? {
            return java.lang.String.format("The repository '%s' could not be resolved: %s", repositoryName, reason)
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val NO_BUILD_FILE_VALUE: NoBuildFilePackageLookupValue = NoBuildFilePackageLookupValue()

        @kotlin.jvm.JvmField
        @SerializationConstant
        val DELETED_PACKAGE_VALUE: DeletedPackageLookupValue = DeletedPackageLookupValue()
        fun success(root: Root, buildFileName: BuildFileName): PackageLookupValue {
            return SuccessfulPackageLookupValue.Companion.create(root, buildFileName)
        }

        @kotlin.jvm.JvmStatic
        fun invalidPackageName(errorMsg: String): PackageLookupValue {
            return InvalidNamePackageLookupValue(errorMsg)
        }

        fun incorrectRepositoryReference(
            invalidPackage: PackageIdentifier?, correctPackage: PackageIdentifier
        ): PackageLookupValue {
            return IncorrectRepositoryReferencePackageLookupValue(invalidPackage, correctPackage)
        }

        fun key(directory: PathFragment): SkyKey? {
            com.google.common.base.Preconditions.checkArgument(!directory.isAbsolute(), directory)
            return key(PackageIdentifier.createInMainRepo(directory))
        }

        fun key(pkgIdentifier: PackageIdentifier?): Key {
            return com.google.devtools.build.lib.skyframe.PackageLookupValue.Key.Companion.create(pkgIdentifier)
        }

        fun appliesToKey(key: SkyKey, identifierPredicate: java.util.function.Predicate<PackageIdentifier?>): Boolean {
            return SkyFunctions.PACKAGE_LOOKUP == key.functionName()
                    && identifierPredicate.test(key.argument() as PackageIdentifier?)
        }

        /**
         * Creates the error message for the input [label][Label] has a subpackage crossing
         * boundary.
         * 
         * 
         * Returns `null` if no subpackage is discovered or the subpackage is marked as DELETED.
         */
        fun getErrorMessageForLabelCrossingPackageBoundary(
            pkgRoot: Root,
            label: Label,
            subpackageIdentifier: PackageIdentifier,
            packageLookupValue: PackageLookupValue
        ): String? {
            var message: String? = null
            if (packageLookupValue.packageExists()) {
                message =
                    java.lang.String.format(
                        "Label '%s' is invalid because '%s' is a subpackage", label, subpackageIdentifier
                    )
                val subPackageRoot: Root? = packageLookupValue.root

                if (pkgRoot == subPackageRoot) {
                    val labelRootPathFragment: PathFragment = label.getPackageIdentifier().getSourceRoot()
                    val subpackagePathFragment: PathFragment = subpackageIdentifier.getSourceRoot()
                    if (subpackagePathFragment.startsWith(labelRootPathFragment)) {
                        val labelNameInSubpackage: PathFragment? =
                            PathFragment.create(label.name)
                                .subFragment(
                                    subpackagePathFragment.segmentCount() - labelRootPathFragment.segmentCount()
                                )
                        message += "; perhaps you meant to put the" + " colon here: '"
                        if (subpackageIdentifier.getRepository().isMain()) {
                            message += "//"
                        }
                        message += subpackageIdentifier.toString() + ":" + labelNameInSubpackage + "'?"
                    } else {
                        // TODO: Is this a valid case? How do we handle this case?
                    }
                } else {
                    message +=
                        ("; have you deleted "
                                + subpackageIdentifier
                                + "/BUILD? "
                                + "If so, use the --deleted_packages="
                                + subpackageIdentifier
                                + " option")
                }
            } else if (packageLookupValue is IncorrectRepositoryReferencePackageLookupValue) {
                message =
                    java.lang.String.format(
                        "Label '%s' is invalid because '%s' is a subpackage",
                        label,
                        packageLookupValue
                            .correctedPackageIdentifier
                    )
            }
            return message
        }
    }
}
