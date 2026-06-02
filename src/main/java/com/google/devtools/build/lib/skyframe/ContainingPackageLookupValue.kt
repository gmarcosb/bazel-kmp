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

/**
 * A value that represents the result of looking for the existence of a package that owns a
 * specific directory path. Compare with [PackageLookupValue], which deals with existence of
 * a specific package.
 */
abstract class ContainingPackageLookupValue : SkyValue {
    /** Returns whether there is a containing package.  */
    abstract fun hasContainingPackage(): Boolean

    /** If there is a containing package, returns its name.  */
    @kotlin.jvm.JvmField
    abstract val containingPackageName: PackageIdentifier

    /** If there is a containing package, returns its package root  */
    @kotlin.jvm.JvmField
    abstract val containingPackageRoot: Root?

    open val reasonForNoContainingPackage: String?
        /**
         * If there is not a containing package, returns a reason why (this is usually the reason the
         * outer-most directory isn't a package).
         */
        get() {
            throw java.lang.IllegalStateException()
        }

    /** [com.google.devtools.build.skyframe.SkyKey] for `ContainingPackageLookupValue`.  */
    @AutoCodec
    class Key private constructor(arg: PackageIdentifier?) : AbstractSkyKey<PackageIdentifier?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.CONTAINING_PACKAGE_LOOKUP
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: PackageIdentifier?): Key {
                return com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    /** Value indicating there is no containing package.  */
    class NoContainingPackage : ContainingPackageLookupValue {
        private val reason: String?

        private constructor() {
            this.reason = null
        }

        private constructor(@javax.annotation.Nonnull reason: String) {
            this.reason = reason
        }

        override fun hasContainingPackage(): Boolean {
            return false
        }

        override fun getContainingPackageName(): PackageIdentifier? {
            throw java.lang.IllegalStateException()
        }

        override fun getContainingPackageRoot(): Root? {
            throw java.lang.IllegalStateException()
        }

        override fun toString(): String {
            return getClass().getName()
        }

        override fun getReasonForNoContainingPackage(): String? {
            return reason
        }
    }

    /** A successful lookup value.  */
    @com.google.common.annotations.VisibleForTesting
    class ContainingPackage private constructor(containingPackage: PackageIdentifier, containingPackageRoot: Root) :
        ContainingPackageLookupValue() {
        private val containingPackage: PackageIdentifier
        private val containingPackageRoot: Root

        init {
            this.containingPackage = containingPackage
            this.containingPackageRoot = containingPackageRoot
        }

        override fun hasContainingPackage(): Boolean {
            return true
        }

        override fun getContainingPackageName(): PackageIdentifier {
            return containingPackage
        }

        override fun getContainingPackageRoot(): Root {
            return containingPackageRoot
        }

        override fun equals(obj: Any?): Boolean {
            if (this === obj) {
                return true
            }
            if (obj !is ContainingPackage) {
                return false
            }
            return containingPackage.equals(obj.containingPackage)
                    && containingPackageRoot == obj.containingPackageRoot
        }

        override fun hashCode(): Int {
            return containingPackage.hashCode()
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("containingPackage", containingPackage)
                .add("containingPackageRoot", containingPackageRoot)
                .toString()
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val NONE: NoContainingPackage = NoContainingPackage()

        fun key(id: PackageIdentifier): Key {
            com.google.common.base.Preconditions.checkArgument(!id.getPackageFragment().isAbsolute(), id)
            return com.google.devtools.build.lib.skyframe.ContainingPackageLookupValue.Key.Companion.create(id)
        }

        fun getErrorMessageForLabelCrossingPackageBoundary(
            pkgRoot: Root,
            label: Label,
            containingPkgLookupValue: ContainingPackageLookupValue
        ): String {
            val containingPkg: PackageIdentifier = containingPkgLookupValue.containingPackageName
            val crossesPackageBoundaryBelow: Boolean =
                containingPkg.getSourceRoot().startsWith(label.getPackageIdentifier().getSourceRoot())
            val labelNameFragment: PathFragment = PathFragment.create(label.name)
            var message: String
            if (crossesPackageBoundaryBelow) {
                message =
                    java.lang.String.format("Label '%s' is invalid because '%s' is a subpackage", label, containingPkg)
            } else {
                message =
                    java.lang.String.format(
                        "Label '%s' is invalid because '%s' is not a package", label, label.getPackageName()
                    )
            }

            val containingRoot: Root? = containingPkgLookupValue.containingPackageRoot
            if (pkgRoot == containingRoot) {
                val containingPkgFragment: PathFragment = containingPkg.getPackageFragment()
                val labelNameInContainingPackage: PathFragment? =
                    if (crossesPackageBoundaryBelow)
                        labelNameFragment.subFragment(
                            containingPkgFragment.segmentCount()
                                    - label.getPackageFragment().segmentCount(),
                            labelNameFragment.segmentCount()
                        )
                    else
                        label.toPathFragment().relativeTo(containingPkgFragment)
                message += "; perhaps you meant to put the colon here: '"
                if (containingPkg.getRepository().isMain()) {
                    message += "//"
                }
                message += containingPkg.toString() + ":" + labelNameInContainingPackage + "'?"
            } else {
                message +=
                    ("; have you deleted "
                            + containingPkg
                            + "/BUILD? "
                            + "If so, use the --deleted_packages="
                            + containingPkg
                            + " option")
            }
            return message
        }

        fun withContainingPackage(pkgId: PackageIdentifier, root: Root): ContainingPackage {
            return ContainingPackage(pkgId, root)
        }

        fun noContainingPackage(reason: String): ContainingPackageLookupValue {
            return NoContainingPackage(reason)
        }
    }
}
