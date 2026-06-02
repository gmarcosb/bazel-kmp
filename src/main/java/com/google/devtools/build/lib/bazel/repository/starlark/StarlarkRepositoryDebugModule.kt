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
package com.google.devtools.build.lib.bazel.repository.starlark

import com.google.common.eventbus.AllowConcurrentEvents
import com.google.common.eventbus.Subscribe
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.events.Reporter
import com.google.devtools.build.lib.repository.RequestRepositoryInformationEvent
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import java.util.concurrent.ConcurrentHashMap

/**
 * Module reporting back the place an external repository was defined, if requested by some error
 * involving that repository. This also covers cases where the definition of the repository is not
 * directly available, e.g., during detection of a dependency cycle.
 */
class StarlarkRepositoryDebugModule : BlazeModule() {
    var repositoryDefinitions: ConcurrentHashMap<String?, String?>? = null
    var reporter: Reporter? = null
    var reported: MutableSet<String?>? = null

    override fun beforeCommand(env: CommandEnvironment) {
        repositoryDefinitions = ConcurrentHashMap<String?, String?>()
        reported = ConcurrentHashMap.newKeySet<String?>()
        reporter = env.getReporter()
        env.getEventBus().register(this)
    }

    override fun afterCommand() {
        repositoryDefinitions = null
        reporter = null
        reported = null
    }

    @Subscribe
    @AllowConcurrentEvents
    fun definitionLocation(event: StarlarkRepositoryDefinitionLocationEvent) {
        repositoryDefinitions!!.put(event.name, event.definitionInformation)
    }

    @Subscribe
    @AllowConcurrentEvents
    fun requestDefinition(event: RequestRepositoryInformationEvent) {
        val toReport =
            if (reported!!.add(event.getName())) repositoryDefinitions!!.get(event.getName()) else null
        if (toReport != null) {
            reporter!!.handle(Event.info(toReport))
        }
    }
}
