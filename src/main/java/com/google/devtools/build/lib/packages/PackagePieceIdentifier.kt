// Copyright 2025 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** A unique identifier for a [PackagePiece].  */
interface PackagePieceIdentifier : SkyKey {
    /**
     * The canonical form of the package name if this is an identifier for a [ ], or the canonical form of the macro instance name if this is an
     * identifier for a [PackagePiece.ForMacro].
     * 
     * 
     * In tha case of a [PackagePiece.ForMacro], the string is not unique, since multiple
     * macro instances can have the same name. Intended to be used in combination with [ ][PackagePiece.getCanonicalFormDefinedBy].
     */
    fun getCanonicalFormName(): String?

    /** Returns the package identifier of the package to which this package piece belong .  */
    fun getPackageIdentifier(): PackageIdentifier?

    /**
     * A unique identifier for a [PackagePiece.ForBuildFile].
     * 
     * 
     * This class does not add any new fields to [PackagePieceIdentifier]; it exists as a
     * sibling class of [PackagePieceIdentifier.ForMacro] only to reduce the potential for
     * confusion when used as sky keys.
     */
    class ForBuildFile(packageIdentifier: PackageIdentifier?) : PackagePieceIdentifier {
        private val packageIdentifier: PackageIdentifier

        override fun getPackageIdentifier(): PackageIdentifier {
            return packageIdentifier
        }

        override fun getCanonicalFormName(): String {
            return packageIdentifier.getCanonicalForm()
        }

        override fun toString(): String {
            return java.lang.String.format("<PackagePieceIdentifier.ForBuildFile pkg=%s>", getCanonicalFormName())
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PACKAGE
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            return other is ForBuildFile
                    && this.packageIdentifier == other.packageIdentifier
        }

        override fun hashCode(): Int {
            return HashCodes.hashObject(packageIdentifier)
        }

        init {
            this.packageIdentifier =
                com.google.common.base.Preconditions.checkNotNull<PackageIdentifier>(packageIdentifier)
        }
    }

    /** A unique identifier for a [PackagePiece.ForMacro].  */
    class ForMacro(
        packageIdentifier: PackageIdentifier?,
        parentIdentifier: PackagePieceIdentifier,
        instanceName: String?
    ) : PackagePieceIdentifier {
        private val packageIdentifier: PackageIdentifier
        @kotlin.jvm.JvmField
        private val parentIdentifier: PackagePieceIdentifier
        @kotlin.jvm.JvmField
        private val instanceName: String

        /** Returns the name attribute of the macro instance.  */
        fun getInstanceName(): String {
            return instanceName
        }

        override fun getPackageIdentifier(): PackageIdentifier {
            return packageIdentifier
        }

        /** Returns the identifier of the package piece in which this macro instance was defined.  */
        fun getParentIdentifier(): PackagePieceIdentifier {
            return parentIdentifier
        }

        override fun getCanonicalFormName(): String? {
            return java.lang.String.format("%s:%s", packageIdentifier.getCanonicalForm(), getInstanceName())
        }

        override fun toString(): String {
            return java.lang.String.format(
                "<PackagePieceIdentifier.ForMacro name=%s declared_in=%s>",
                getCanonicalFormName(), parentIdentifier
            )
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.EVAL_MACRO
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) {
                return true
            }
            return other is ForMacro
                    && this.packageIdentifier == other.packageIdentifier
                    && this.instanceName == other.instanceName
                    && this.parentIdentifier == other.parentIdentifier
        }

        override fun hashCode(): Int {
            return HashCodes.hashObjects(packageIdentifier, instanceName, parentIdentifier)
        }

        init {
            this.packageIdentifier =
                com.google.common.base.Preconditions.checkNotNull<PackageIdentifier>(packageIdentifier)
            com.google.common.base.Preconditions.checkArgument(
                com.google.common.base.Preconditions.checkNotNull<PackagePieceIdentifier?>(parentIdentifier)
                    .getPackageIdentifier().equals(packageIdentifier)
            )
            this.parentIdentifier = parentIdentifier
            this.instanceName = com.google.common.base.Preconditions.checkNotNull<String>(instanceName)
        }
    }
}
