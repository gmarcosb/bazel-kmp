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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.PackageIdentifier

/**
 * SkyFunction that throws a [BuildFileContainsErrorsException] for [Package] that
 * loaded, but was in error. Must only be requested when a SkyFunction wishes to ignore the Skyframe
 * error from a [PackageValue] in keep_going mode, but to shut down the build in nokeep_going
 * mode. Thus, this SkyFunction should only be requested when the corresponding [ ] has already been successfully called and the resulting Package contains an
 * error.
 * 
 * 
 * This SkyFunction always throws a [BuildFileContainsErrorsException]. It also should
 * never request a skyframe restart, since all of its dependencies should already be present.
 */
class PackageErrorFunction : SkyFunction {
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: PackageIdentifier?) : AbstractSkyKey<PackageIdentifier?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PACKAGE_ERROR
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.PackageErrorFunction.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: PackageIdentifier?): Key {
                return com.google.devtools.build.lib.skyframe.PackageErrorFunction.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.PackageErrorFunction.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.PackageErrorFunction.Key.Companion.interner.intern(key)
            }
        }
    }

    @Throws(PackageErrorFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val packageIdentifier: PackageIdentifier? = skyKey.argument() as PackageIdentifier?
        try {
            // Callers must have tried to load the package already and gotten the package successfully.
            val pkg: Package =
                (env.getValueOrThrow<E?>(packageIdentifier, NoSuchPackageException::class.java) as PackageValue)
                    .getPackage()
            com.google.common.base.Preconditions.checkState(pkg.containsErrors(), skyKey)
            throw PackageErrorFunctionException(
                BuildFileContainsErrorsException(packageIdentifier), Transience.PERSISTENT
            )
        } catch (e: NoSuchPackageException) {
            throw java.lang.IllegalStateException(
                "Function should not have been called on package with exception", e
            )
        }
    }

    private class PackageErrorFunctionException(cause: BuildFileContainsErrorsException?, transience: Transience?) :
        SkyFunctionException(cause, transience)

    companion object {
        fun key(packageIdentifier: PackageIdentifier?): Key {
            return com.google.devtools.build.lib.skyframe.PackageErrorFunction.Key.Companion.create(packageIdentifier)
        }
    }
}
