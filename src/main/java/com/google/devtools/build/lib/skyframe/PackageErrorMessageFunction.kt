// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/** [SkyFunction] for [PackageErrorMessageValue].  */
class PackageErrorMessageFunction : SkyFunction {
    @Throws(java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val pkgId: PackageIdentifier? = skyKey.argument() as PackageIdentifier?
        val pkgValue: PackageValue?
        try {
            pkgValue = env.getValueOrThrow<E?>(pkgId, NoSuchPackageException::class.java) as PackageValue?
        } catch (e: NoSuchPackageException) {
            // Note that in a no-keep-going build, this returned value is ignored by Skyframe, and the
            // NoSuchPackageException will be propagated up to the caller of PackageErrorMessageFunction.
            return PackageErrorMessageValue.Companion.ofNoSuchPackageException(e.getMessage())
        }
        if (pkgValue == null) {
            return null
        }
        val pkg: Package = pkgValue.getPackage()
        return if (pkg.containsErrors())
            PackageErrorMessageValue.Companion.ofPackageWithErrors()
        else
            PackageErrorMessageValue.Companion.ofPackageWithNoErrors()
    }
}
