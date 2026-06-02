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

/**
 * Encapsulates the errors, if any, encountered when loading a specific package.
 * 
 * 
 * This is a change-pruning-friendly convenience [SkyValue] for use cases where [ ] is sufficient.
 */
abstract class PackageErrorMessageValue : SkyValue {
    /** Tri-state result of loading the package.  */
    enum class Result {
        /**
         * There was no error loading the package and [ ][com.google.devtools.build.lib.packages.Package.containsErrors] returned `false`.
         */
        NO_ERROR,

        /**
         * There was no error loading the package and [ ][com.google.devtools.build.lib.packages.Package.containsErrors] returned `true`.
         */
        ERROR,

        /**
         * There was a [com.google.devtools.build.lib.packages.NoSuchPackageException] loading the
         * package.
         */
        NO_SUCH_PACKAGE_EXCEPTION,
    }

    /** Returns the [Result] from loading the package.  */
    @kotlin.jvm.JvmField
    abstract val result: Result?

    /**
     * If `getResult().equals(NO_SUCH_PACKAGE_EXCEPTION)`, returns the error message from the
     * [com.google.devtools.build.lib.packages.NoSuchPackageException] encountered.
     */
    @kotlin.jvm.JvmField
    abstract val noSuchPackageExceptionMessage: String?

    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: PackageIdentifier?) : AbstractSkyKey<PackageIdentifier?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PACKAGE_ERROR_MESSAGE
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: PackageIdentifier?): Key {
                return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Key(
                        arg
                    )
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Key.Companion.interner.intern(key)
            }
        }
    }

    private class NoSuchPackageExceptionValue(private val errorMessage: String) : PackageErrorMessageValue() {
        override fun getResult(): Result {
            return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.NO_SUCH_PACKAGE_EXCEPTION
        }

        override fun getNoSuchPackageExceptionMessage(): String {
            return errorMessage
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is NoSuchPackageExceptionValue) {
                return false
            }
            return errorMessage == obj.errorMessage
        }

        override fun hashCode(): Int {
            return errorMessage.hashCode()
        }
    }

    companion object {
        fun ofPackageWithNoErrors(): PackageErrorMessageValue {
            return NO_ERROR_VALUE
        }

        fun ofPackageWithErrors(): PackageErrorMessageValue {
            return ERROR_VALUE
        }

        fun ofNoSuchPackageException(errorMessage: String): PackageErrorMessageValue {
            return NoSuchPackageExceptionValue(errorMessage)
        }

        fun key(pkgId: PackageIdentifier?): SkyKey {
            return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Key.Companion.create(pkgId)
        }

        @SerializationConstant
        val NO_ERROR_VALUE: PackageErrorMessageValue = object : PackageErrorMessageValue() {
            override fun getResult(): Result {
                return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.NO_ERROR
            }

            override fun getNoSuchPackageExceptionMessage(): String? {
                throw java.lang.IllegalStateException()
            }
        }

        @SerializationConstant
        val ERROR_VALUE: PackageErrorMessageValue = object : PackageErrorMessageValue() {
            override fun getResult(): Result {
                return com.google.devtools.build.lib.skyframe.PackageErrorMessageValue.Result.ERROR
            }

            override fun getNoSuchPackageExceptionMessage(): String? {
                throw java.lang.IllegalStateException()
            }
        }
    }
}
