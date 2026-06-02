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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * A Skyframe value representing a package.
 * 
 * 
 * The corresponding [com.google.devtools.build.skyframe.SkyKey] is [ ].
 */
@AutoCodec(explicitlyAllowClass = [Package::class])
@Immutable
@ThreadSafe
class PackageValue(pkg: Package?) : PackageoidValue {
    private val pkg: Package

    init {
        this.pkg = com.google.common.base.Preconditions.checkNotNull<Package>(pkg)
    }

    val `package`: Package
        /**
         * Returns the package. This package may contain errors, in which case the caller should throw a
         * [com.google.devtools.build.lib.packages.BuildFileContainsErrorsException] if an
         * error-free package is needed. See also [PackageErrorFunction] for the case where
         * encountering a package with errors should shut down the build but the caller can handle
         * packages with errors.
         */
        get() = pkg

    val packageoid: Packageoid
        get() = this.`package`

    override fun toString(): String {
        return "<PackageValue name=" + pkg.getName() + ">"
    }
}
