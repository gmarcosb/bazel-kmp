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
package com.google.devtools.build.lib.bazel

import com.google.devtools.build.lib.bazel.repository.cache.DownloadCacheHitEvent
import com.google.devtools.build.lib.repository.RepositoryFailedEvent
import com.google.devtools.build.lib.repository.RepositoryFetchProgress
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import java.util.concurrent.ConcurrentHashMap

/** Module reporting about cache hits in external repositories in case of failures  */
class CacheHitReportingModule : BlazeModule() {
    private var reporter: com.google.devtools.build.lib.events.Reporter? = null
    private var cacheHitsByContext: ConcurrentHashMap<String?, MutableSet<com.google.devtools.build.lib.util.Pair<String?, java.net.URI?>>?>? =
        null

    override fun beforeCommand(env: CommandEnvironment) {
        env.getEventBus().register(this)
        this.reporter = env.getReporter()
        this.cacheHitsByContext =
            ConcurrentHashMap<String?, MutableSet<com.google.devtools.build.lib.util.Pair<String?, java.net.URI?>>?>()
    }

    override fun afterCommand() {
        this.reporter = null
        this.cacheHitsByContext = null
    }

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    fun cacheHit(event: DownloadCacheHitEvent) {
        cacheHitsByContext
            .computeIfAbsent(
                event.context,
                java.util.function.Function { k: String? -> ConcurrentHashMap.newKeySet<com.google.devtools.build.lib.util.Pair<String?, java.net.URI?>?>() })
            .add(com.google.devtools.build.lib.util.Pair.of<String?, java.net.URI?>(event.fileHash, event.uri))
    }

    @com.google.common.eventbus.Subscribe
    fun failed(event: RepositoryFailedEvent) {
        // TODO(wyv): add an event for the failure of a module extension too
        val context: String = RepositoryFetchProgress.repositoryFetchContextString(event.getRepo())
        val cacheHits: MutableSet<com.google.devtools.build.lib.util.Pair<String?, java.net.URI?>>? =
            cacheHitsByContext.get(context)
        if (cacheHits != null && !cacheHits.isEmpty()) {
            val info: java.lang.StringBuilder = java.lang.StringBuilder()

            info.append(context)
                .append(
                    "' used the following cache hits instead of downloading the corresponding file.\n"
                )
            for (hit in cacheHits) {
                info.append(" * Hash '")
                    .append(hit.getFirst())
                    .append("' for ")
                    .append(hit.getSecond().toString())
                    .append("\n")
            }
            info.append("If the definition of '")
                .append(context)
                .append("' was updated, verify that the hashes were also updated.")
            reporter.handle(com.google.devtools.build.lib.events.Event.info(info.toString()))
        }
    }
}
