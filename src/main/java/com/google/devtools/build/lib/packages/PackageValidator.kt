// Copyright 2020 The Bazel Authors. All rights reserved.
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

/** Provides loaded-package validation functionality.  */
interface PackageValidator {
    /** Thrown when a package is deemed invalid.  */
    class InvalidPackageException : NoSuchPackageException {
        constructor(pkgId: PackageIdentifier?, message: String?) : super(pkgId, message)

        constructor(pkgId: PackageIdentifier?, message: String?, detailedExitCode: DetailedExitCode?) : super(
            pkgId,
            message,
            detailedExitCode
        )
    }

    /** Thrown when a package piece is deemed invalid.  */
    class InvalidPackagePieceException : NoSuchPackagePieceException {
        constructor(packagePieceId: PackagePieceIdentifier?, message: String?) : super(packagePieceId, message)

        constructor(
            packagePieceId: PackagePieceIdentifier?,
            message: String?,
            detailedExitCode: DetailedExitCode?
        ) : super(packagePieceId, message, detailedExitCode)
    }

    fun getPackageLimits(): PackageLimits {
        return PackageLimits.Companion.DEFAULTS
    }

    /**
     * Validates a loaded package. Throws [InvalidPackageException] if the package is deemed
     * invalid.
     */
    @Throws(InvalidPackageException::class)
    fun validate(
        pkg: com.google.devtools.build.lib.packages.Package?,
        metrics: com.google.devtools.build.lib.packages.PackageLoadingListener.Metrics?,
        eventHandler: ExtendedEventHandler?
    )

    companion object {
        /** No-op implementation of [PackageValidator].  */
        @kotlin.jvm.JvmField
        val NOOP_VALIDATOR: PackageValidator =
            PackageValidator { pkg: com.google.devtools.build.lib.packages.Package?, metrics: com.google.devtools.build.lib.packages.PackageLoadingListener.Metrics?, eventHandler: ExtendedEventHandler? -> }
    }
}
