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
 * Exception indicating an attempt to access a package which is not found, does not exist, or can't
 * be parsed into a package.
 * 
 * 
 * Prefer using more-specific subclasses, when appropriate.
 */
open class NoSuchPackageException : NoSuchThingException {
    private val packageId: PackageIdentifier?

    constructor(packageId: PackageIdentifier?, message: String?) : super(message) {
        this.packageId = packageId
    }

    constructor(packageId: PackageIdentifier?, message: String?, cause: java.lang.Exception?) : super(message, cause) {
        this.packageId = packageId
    }

    constructor(packageId: PackageIdentifier?, message: String?, detailedExitCode: DetailedExitCode?) : super(
        message,
        detailedExitCode
    ) {
        this.packageId = packageId
    }

    constructor(
        packageId: PackageIdentifier?,
        message: String?,
        cause: java.lang.Exception?,
        detailedExitCode: DetailedExitCode?
    ) : super(message, cause, detailedExitCode) {
        this.packageId = packageId
    }

    fun getPackageId(): PackageIdentifier? {
        return packageId
    }

    fun getRawMessage(): String? {
        return super.getMessage()
    }

    override fun getMessage(): String? {
        return java.lang.String.format("no such package '%s': %s", packageId, getRawMessage())
    }

    fun hasExplicitDetailedExitCode(): Boolean {
        return getUncheckedDetailedExitCode() != null
    }

    override fun getDetailedExitCode(): DetailedExitCode {
        val uncheckedDetailedExitCode: DetailedExitCode? = getUncheckedDetailedExitCode()
        return if (uncheckedDetailedExitCode != null)
            uncheckedDetailedExitCode
        else
            defaultDetailedExitCode()
    }

    private fun defaultDetailedExitCode(): DetailedExitCode {
        return DetailedExitCode.of(
            FailureDetail.newBuilder()
                .setMessage(getMessage())
                .setPackageLoading(
                    PackageLoading.newBuilder().setCode(PackageLoading.Code.PACKAGE_MISSING).build()
                )
                .build()
        )
    }
}
