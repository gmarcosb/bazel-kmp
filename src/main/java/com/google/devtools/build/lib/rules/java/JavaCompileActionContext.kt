// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.java

import com.google.common.base.Throwables
import com.google.common.collect.ImmutableList
import com.google.devtools.build.lib.actions.ActionContext
import java.util.concurrent.Callable
import java.util.concurrent.Future
import kotlin.collections.ArrayList
import kotlin.collections.MutableList
import kotlin.collections.MutableSet

/** Context for compiling Java files.  */
class JavaCompileActionContext : ActionContext {
    // TODO(djasper): Investigate caching across builds.
    private val cache: ConcurrentHashMap<Artifact?, Deps.Dependencies?> =
        ConcurrentHashMap<Artifact?, Deps.Dependencies?>()

    private val executor: ExecutorService =
        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("jdeps-reader-", 0).factory())

    @Throws(IOException::class, InterruptedException::class)
    fun addDependencies(
        jdepsFiles: ImmutableList<Artifact?>,
        actionExecutionContext: ActionExecutionContext,
        deps: MutableSet<String?>
    ) {
        val uncached: MutableList<Future<Deps.Dependencies?>> = ArrayList<Future<Deps.Dependencies?>>()
        for (jdepsFile in jdepsFiles) {
            // Reading a jdeps file is potentially expensive, e.g. when we have to download an input with
            // actionFS, so we use an ExecutorService instead of computeIfAbsent here. The downside is
            // that potentially we parse the same jdeps file twice, but at least we are not blocking all
            // other threads on the lock for the cache.
            val cachedDeps: Deps.Dependencies? = cache.get(jdepsFile)
            if (cachedDeps == null) {
                uncached.add(
                    executor.submit<Deps.Dependencies?>(Callable {
                        readAndCacheJdepsFile(
                            jdepsFile,
                            actionExecutionContext
                        )
                    })
                )
            } else {
                for (dep in cachedDeps.getDependencyList()) {
                    deps.add(dep.getPath())
                }
            }
        }

        for (future in uncached) {
            val result: Deps.Dependencies
            try {
                result = future.get()
            } catch (e: ExecutionException) {
                Throwables.throwIfInstanceOf<IOException?>(e.getCause(), IOException::class.java)
                throw IllegalStateException(e)
            }

            for (dep in result.getDependencyList()) {
                deps.add(dep.getPath())
            }
        }
    }

    @Throws(IOException::class)
    private fun readAndCacheJdepsFile(
        jdepsFile: Artifact?, actionExecutionContext: ActionExecutionContext
    ): Deps.Dependencies? {
        val deps: Deps.Dependencies?
        actionExecutionContext.getInputPath(jdepsFile).getInputStream().use { input ->
            deps = Deps.Dependencies.parseFrom(input)
        }
        cache.putIfAbsent(jdepsFile, deps)
        return deps
    }

    fun insertDependencies(jdepsFile: Artifact?, dependencies: Deps.Dependencies?) {
        cache.put(jdepsFile, dependencies)
    }
}
