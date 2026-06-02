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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * Exception indicating a failed attempt to access a package that could not be read or had syntax
 * errors.
 */
class BuildFileContainsErrorsException : NoSuchPackageException {
    constructor(packageIdentifier: PackageIdentifier) : super(
        packageIdentifier,
        java.lang.String.format(
            "Package '%s' contains errors",
            packageIdentifier.getPackageFragment().getPathString()
        )
    )

    constructor(packageIdentifier: PackageIdentifier?, message: String?) : super(packageIdentifier, message)

    constructor(packageIdentifier: PackageIdentifier?, message: String?, cause: IOException?) : super(
        packageIdentifier,
        message,
        cause
    )

    constructor(packageIdentifier: PackageIdentifier?, message: String?, detailedExitCode: DetailedExitCode?) : super(
        packageIdentifier,
        message,
        detailedExitCode
    )

    constructor(
        packageIdentifier: PackageIdentifier?,
        message: String?,
        cause: IOException?,
        detailedExitCode: DetailedExitCode?
    ) : super(packageIdentifier, message, cause, detailedExitCode)

    override fun getMessage(): String? {
        return java.lang.String.format("error loading package '%s': %s", getPackageId(), getRawMessage())
    }
}
