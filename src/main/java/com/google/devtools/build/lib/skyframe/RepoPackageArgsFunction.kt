// Copyright 2024 The Bazel Authors. All rights reserved.
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

/** A [SkyFunction] that returns the [PackageArgs] for a given repository.  */
class RepoPackageArgsFunction private constructor() : SkyFunction {
    /** [SkyValue] wrapping a PackageArgs.  */
    class RepoPackageArgsValue(packageArgs: PackageArgs) : SkyValue {
        private val packageArgs: PackageArgs

        init {
            this.packageArgs = packageArgs
        }

        fun getPackageArgs(): PackageArgs {
            return packageArgs
        }

        override fun hashCode(): Int {
            return packageArgs.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (other is RepoPackageArgsValue) {
                return other.packageArgs.equals(packageArgs)
            } else {
                return false
            }
        }

        companion object {
            @kotlin.jvm.JvmField
            val EMPTY: RepoPackageArgsValue = RepoPackageArgsValue(PackageArgs.EMPTY)
        }
    }

    /** Thrown when there is something wrong with the arguments of the `repo()` function.  */
    private class BadPackageArgsException(message: String?, cause: java.lang.Exception?) :
        java.lang.Exception(message, cause)

    /** A [SkyFunctionException] for [RepoPackageArgsFunction].  */
    private class RepoPackageArgsFunctionException(e: BadPackageArgsException?) :
        SkyFunctionException(e, Transience.PERSISTENT)

    /** Key type for [RepoPackageArgsValue].  */
    class Key private constructor(repoName: RepositoryName?) : AbstractSkyKey<RepositoryName?>(repoName) {
        override fun functionName(): SkyFunctionName {
            return SkyFunctions.REPO_PACKAGE_ARGS
        }
    }

    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey, env: SkyFunction.Environment): SkyValue? {
        val repositoryName: RepositoryName = skyKey.argument() as RepositoryName

        val repoFileValue: RepoFileValue? = env.getValue(RepoFileValue.Companion.key(repositoryName)) as RepoFileValue?
        val repositoryMappingValue: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.Companion.key(repositoryName)) as RepositoryMappingValue?
        val mainRepoMapping: RepositoryMappingValue? =
            env.getValue(RepositoryMappingValue.Companion.key(RepositoryName.MAIN)) as RepositoryMappingValue?

        if (env.valuesMissing()) {
            return null
        }

        val repoDisplayName: String =
            RepoFileFunction.Companion.getDisplayNameForRepo(repositoryName, mainRepoMapping.repositoryMapping)

        val pkgArgsBuilder: PackageArgs.Builder = PackageArgs.builder()
        val labelConverter: LabelConverter =
            LabelConverter(
                PackageIdentifier.create(repositoryName, PathFragment.EMPTY_FRAGMENT),
                repositoryMappingValue.repositoryMapping
            )
        try {
            for (kwarg in repoFileValue.packageArgsMap.entrySet()) {
                PackageArgs.processParam(
                    kwarg.getKey(),
                    kwarg.getValue(),
                    "repo() argument '" + kwarg.getKey() + "'",
                    labelConverter,
                    pkgArgsBuilder
                )
            }
        } catch (e: net.starlark.java.eval.EvalException) {
            env.getListener().handle(Event.error(e.getMessageWithStack()))
            throw RepoPackageArgsFunctionException(
                BadPackageArgsException(
                    "error evaluating REPO.bazel file for " + repoDisplayName, e
                )
            )
        }

        return RepoPackageArgsValue(pkgArgsBuilder.build())
    }

    companion object {
        @kotlin.jvm.JvmField
        val INSTANCE: RepoPackageArgsFunction = RepoPackageArgsFunction()

        fun key(repoName: RepositoryName?): Key {
            return com.google.devtools.build.lib.skyframe.RepoPackageArgsFunction.Key(repoName)
        }
    }
}
