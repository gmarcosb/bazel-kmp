// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.RepositoryName

/**
 * A value that represents a local repository lookup result.
 * 
 * 
 * Local repository lookups will always produce a value. The `#getRepository` method
 * returns the name of the repository that the directory resides in.
 */
abstract class LocalRepositoryLookupValue : SkyValue {
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    @AutoCodec
    internal class Key private constructor(arg: RootedPath?) : AbstractSkyKey<RootedPath?>(arg) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.LOCAL_REPOSITORY_LOOKUP
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.LocalRepositoryLookupValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            private fun create(arg: RootedPath?): Key {
                return com.google.devtools.build.lib.skyframe.LocalRepositoryLookupValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.LocalRepositoryLookupValue.Key(arg)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(key: Key?): Key {
                return com.google.devtools.build.lib.skyframe.LocalRepositoryLookupValue.Key.Companion.interner.intern(
                    key
                )
            }
        }
    }

    /**
     * Returns `true` if the local repository lookup succeeded and the [.getRepository]
     * method will return a useful result.
     */
    abstract fun exists(): Boolean

    /**
     * Returns the [RepositoryName] of the local repository contained in the directory which was
     * looked up, [RepositoryName.MAIN] if the directory is part of the main repository, or
     * throws a [IllegalStateException] if there was no repository found.
     */
    @kotlin.jvm.JvmField
    abstract val repository: RepositoryName?

    /**
     * Returns the path to the local repository, or throws a [IllegalStateException] if there
     * was no repository found.
     */
    @kotlin.jvm.JvmField
    abstract val path: PathFragment?

    /** Represents a successful lookup of the main repository.  */
    class MainRepositoryLookupValue  // This should be a singleton value.
    private constructor() : LocalRepositoryLookupValue() {
        override fun exists(): Boolean {
            return true
        }

        override fun getRepository(): RepositoryName {
            return RepositoryName.MAIN
        }

        override fun getPath(): PathFragment {
            return PathFragment.EMPTY_FRAGMENT
        }

        override fun toString(): String {
            return "MainRepositoryLookupValue"
        }

        override fun equals(obj: Any?): Boolean {
            // All MainRepositoryLookupValue instances are equivalent.
            return obj is MainRepositoryLookupValue
        }

        override fun hashCode(): Int {
            return MainRepositoryLookupValue::class.java.getSimpleName().hashCode()
        }
    }

    /** Represents a successful lookup of a local repository.  */
    class SuccessfulLocalRepositoryLookupValue
        (repositoryName: RepositoryName, path: PathFragment?) : LocalRepositoryLookupValue() {
        private val repositoryName: RepositoryName
        private val path: PathFragment?

        init {
            this.repositoryName = repositoryName
            this.path = path
        }

        override fun exists(): Boolean {
            return true
        }

        override fun getRepository(): RepositoryName {
            return repositoryName
        }

        override fun getPath(): PathFragment? {
            return path
        }

        override fun toString(): String {
            return "SuccessfulLocalRepositoryLookupValue(" + repositoryName + ")"
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is SuccessfulLocalRepositoryLookupValue) {
                return false
            }
            return repositoryName.equals(obj.repositoryName)
        }

        override fun hashCode(): Int {
            return repositoryName.hashCode()
        }
    }

    /** Represents the state where no repository was found, either local or the main repository.  */
    class NotFoundLocalRepositoryLookupValue  // This should be a singleton value.
    private constructor() : LocalRepositoryLookupValue() {
        override fun exists(): Boolean {
            return false
        }

        override fun getRepository(): RepositoryName? {
            throw java.lang.IllegalStateException("Repository was not found")
        }

        override fun getPath(): PathFragment? {
            throw java.lang.IllegalStateException("Repository was not found")
        }

        override fun toString(): String {
            return "NotFoundLocalRepositoryLookupValue"
        }

        override fun equals(obj: Any?): Boolean {
            // All NotFoundLocalRepositoryLookupValue instances are equivalent.
            return obj is NotFoundLocalRepositoryLookupValue
        }

        override fun hashCode(): Int {
            return NotFoundLocalRepositoryLookupValue::class.java.getSimpleName().hashCode()
        }
    }

    companion object {
        fun key(directory: RootedPath?): Key {
            return com.google.devtools.build.lib.skyframe.LocalRepositoryLookupValue.Key.Companion.create(directory)
        }

        private val MAIN_REPO_VALUE: LocalRepositoryLookupValue = MainRepositoryLookupValue()
        private val NOT_FOUND_VALUE: LocalRepositoryLookupValue = NotFoundLocalRepositoryLookupValue()

        @kotlin.jvm.JvmStatic
        fun mainRepository(): LocalRepositoryLookupValue {
            return MAIN_REPO_VALUE
        }

        fun success(
            repositoryName: RepositoryName, path: PathFragment?
        ): LocalRepositoryLookupValue {
            return SuccessfulLocalRepositoryLookupValue(repositoryName, path)
        }

        fun notFound(): LocalRepositoryLookupValue {
            return NOT_FOUND_VALUE
        }
    }
}
