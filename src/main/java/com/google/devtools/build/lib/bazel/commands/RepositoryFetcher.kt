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
package com.google.devtools.build.lib.bazel.commands

import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.bazel.bzlmod.modcommand.InvalidArgumentException
import com.google.devtools.build.lib.cmdline.LabelSyntaxException
import com.google.devtools.build.lib.cmdline.RepositoryName
import com.google.devtools.build.lib.rules.repository.RepositoryDirectoryValue
import com.google.devtools.build.lib.runtime.CommandEnvironment
import com.google.devtools.build.lib.runtime.KeepGoingOption
import com.google.devtools.build.lib.runtime.LoadingPhaseThreadsOption
import com.google.devtools.build.lib.skyframe.RepositoryMappingValue.RepositoryMappingResolutionException
import com.google.devtools.build.skyframe.EvaluationContext
import com.google.devtools.build.skyframe.EvaluationResult
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import net.starlark.java.eval.EvalException
import java.util.function.Function

/** Fetches repositories for commands.  */
internal class RepositoryFetcher private constructor(
    private val env: CommandEnvironment,
    private val threadsOption: LoadingPhaseThreadsOption
) {
    @Throws(InterruptedException::class, RepositoryFetcherException::class, RepositoryMappingResolutionException::class)
    private fun fetchRepos(repos: MutableList<String>): ImmutableMap<RepositoryName?, RepositoryDirectoryValue?> {
        val reposnames = collectRepositoryNames(repos)
        val evaluationResult = evaluateFetch(reposnames)
        return reposnames.stream()
            .collect(
                ImmutableMap.toImmutableMap<RepositoryName?, RepositoryName?, RepositoryDirectoryValue?>(
                    Function { repoName: RepositoryName? -> repoName },
                    Function { repoName: RepositoryName? -> evaluationResult.get(RepositoryDirectoryValue.key(repoName)) as RepositoryDirectoryValue? })
            )
    }

    @Throws(InterruptedException::class, RepositoryFetcherException::class)
    private fun evaluateFetch(reposnames: ImmutableSet<RepositoryName?>): EvaluationResult<SkyValue?> {
        val evaluationContext =
            EvaluationContext.newBuilder()
                .setParallelism(threadsOption.getThreads())
                .setEventHandler(env.getReporter())
                .build()
        val repoDelegatorKeys =
            reposnames.stream().map<RepositoryDirectoryValue.Key?>(Function { repository: RepositoryName? ->
                RepositoryDirectoryValue.key(repository)
            }).collect(
                ImmutableSet.toImmutableSet<SkyKey?>()
            )
        val evaluationResult =
            env.getSkyframeExecutor().prepareAndGet(repoDelegatorKeys, evaluationContext)
        if (evaluationResult.hasError()) {
            val e = evaluationResult.getError().getException()
            throw RepositoryFetcherException(
                if (e != null) e.getMessage() else "Unexpected error during repository fetching."
            )
        }
        return evaluationResult
    }

    @Throws(InterruptedException::class, RepositoryFetcherException::class, RepositoryMappingResolutionException::class)
    private fun collectRepositoryNames(repos: MutableList<String>): ImmutableSet<RepositoryName?> {
        val reposnames = ImmutableSet.builder<RepositoryName?>()
        for (repo in repos) {
            try {
                reposnames.add(getRepositoryName(repo))
            } catch (e: LabelSyntaxException) {
                throw RepositoryFetcherException("Invalid repo name: " + e.getMessage())
            } catch (e: EvalException) {
                throw RepositoryFetcherException("Invalid repo name: " + e.getMessage())
            } catch (e: InvalidArgumentException) {
                throw RepositoryFetcherException("Invalid repo name: " + e.getMessage())
            }
        }
        return reposnames.build()
    }

    @Throws(
        EvalException::class,
        InterruptedException::class,
        LabelSyntaxException::class,
        InvalidArgumentException::class,
        RepositoryMappingResolutionException::class
    )
    private fun getRepositoryName(repoName: String): RepositoryName? {
        if (repoName.startsWith("@@")) { // canonical RepoName
            return RepositoryName.create(repoName.substring(2))
        } else if (repoName.startsWith("@")) { // apparent RepoName
            RepositoryName.validateUserProvidedRepoName(repoName.substring(1))
            val repoMapping =
                env.getSkyframeExecutor()
                    .getMainRepoMapping(
                        env.getOptions().getOptions<KeepGoingOption?>(KeepGoingOption::class.java)!!.getKeepGoing(),
                        threadsOption.getThreads(),
                        env.getReporter()
                    )
            return repoMapping.get(repoName.substring(1))
        } else {
            throw InvalidArgumentException(
                "The repo value has to be either apparent '@repo' or canonical '@@repo' repo name"
            )
        }
    }

    internal class RepositoryFetcherException(message: String?) : Exception(message)
    companion object {
        @Throws(
            RepositoryMappingResolutionException::class,
            InterruptedException::class,
            RepositoryFetcherException::class
        )
        fun fetchRepos(
            repos: MutableList<String>,
            env: CommandEnvironment,
            threadsOption: LoadingPhaseThreadsOption
        ): ImmutableMap<RepositoryName?, RepositoryDirectoryValue?> {
            return RepositoryFetcher(env, threadsOption).fetchRepos(repos)
        }
    }
}
