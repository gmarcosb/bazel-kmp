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
package com.google.devtools.build.lib.query2.testutil

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.common.collect.ImmutableSet
import com.google.devtools.build.lib.bazel.bzlmod.ModuleKey
import com.google.devtools.build.lib.events.EventKind
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.query2.engine.QueryEnvironment
import java.util.*

/** Partial [QueryHelper] implementation for settings storage and event handling.  */
abstract class AbstractQueryHelper<T> : QueryHelper<T?> {
    var reporter: Reporter? = null
        private set
    private var eventCollector: EventCollector? = null

    var isKeepGoing: Boolean = false
    protected var settings: ImmutableSet<QueryEnvironment.Setting?> = ImmutableSet.of<QueryEnvironment.Setting?>()
    protected var orderedResults: Boolean = true
    protected var universeScope: UniverseScope? = UniverseScope.EMPTY

    protected var mainRepoTargetParser: TargetPattern.Parser? = null

    @Throws(Exception::class)
    override fun setUp() {
        eventCollector = EventCollector(EventKind.ERRORS_AND_WARNINGS)
        reporter = Reporter(EventBusEventHandler.createWithNewEventBus(), eventCollector)
        mainRepoTargetParser =
            Parser(
                PathFragment.EMPTY_FRAGMENT, RepositoryName.MAIN, DEFAULT_MAIN_REPO_MAPPING
            )
    }

    override fun setUniverseScope(universeScope: String) {
        this.universeScope =
            UniverseScope.fromUniverseScopeList(
                ImmutableList.< E > copyOf < E ? > (Arrays.< T > asList < T ? > (universeScope.split(",".toRegex())
                    .dropLastWhile { it.isEmpty() }.toTypedArray()))
            )
    }

    override fun clearEvents() {
        eventCollector.clear()
    }

    override fun setOrderedResults(orderedResults: Boolean) {
        this.orderedResults = orderedResults
    }

    override fun setQuerySettings(vararg settings: QueryEnvironment.Setting?) {
        this.settings = ImmutableSet.copyOf<QueryEnvironment.Setting?>(settings)
    }

    override fun assertContainsEvent(expectedMessage: String?) {
        MoreAsserts.assertContainsEvent(eventCollector, expectedMessage)
    }

    override fun assertDoesNotContainEvent(expectedMessage: String?) {
        MoreAsserts.assertDoesNotContainEvent(eventCollector, expectedMessage)
    }

    val firstEvent: String?
        get() = eventCollector.iterator().next().getMessage()

    val events: Iterable<Event?>
        get() = eventCollector

    override fun addModule(key: ModuleKey?, vararg moduleFileLines: String?) {
        throw IllegalStateException("Cannot call this on non-bzlmod-enabled query environments.")
    }

    val moduleRoot: Path?
        get() {
            throw IllegalStateException("Cannot call this on non-bzlmod-enabled query environments.")
        }

    override fun setMainRepoTargetParser(mapping: RepositoryMapping) {
        this.mainRepoTargetParser =
            Parser(
                PathFragment.EMPTY_FRAGMENT,
                RepositoryName.MAIN,
                mapping.withAdditionalMappings(DEFAULT_MAIN_REPO_MAPPING)
            )
    }

    @Throws(AbruptExitException::class, InterruptedException::class)
    override fun maybeHandleDiffs() {
        // Do nothing.
    }

    companion object {
        val DEFAULT_MAIN_REPO_MAPPING: RepositoryMapping? = RepositoryMapping.create(
            ImmutableMap.of<K?, V?>(
                "",
                RepositoryName.MAIN,
                "bazel_tools",
                RepositoryName.BAZEL_TOOLS,
                "platforms",
                RepositoryName.createUnvalidated("platforms")
            ),
            RepositoryName.MAIN
        )
    }
}
