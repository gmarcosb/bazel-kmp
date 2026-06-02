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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.BlazeCommand
import java.util.ArrayDeque
import java.util.HashMap
import java.util.HashSet

internal class CommandNameCacheImpl(commandMap: MutableMap<String?, BlazeCommand?>) :
    com.google.devtools.common.options.CommandNameCache {
    private val commandMap: MutableMap<String?, com.google.devtools.build.lib.runtime.Command?>
    private val cache: MutableMap<String?, com.google.common.collect.ImmutableSet<String?>?> =
        HashMap<String?, com.google.common.collect.ImmutableSet<String?>?>()

    init {
        // Note: it is important that this map is live, since the commandMap may be altered
        // post-creation.
        this.commandMap =
            com.google.common.collect.Maps.transformValues<String?, BlazeCommand?, com.google.devtools.build.lib.runtime.Command?>(
                commandMap,
                com.google.common.base.Function { blazeCommand: BlazeCommand? ->
                    blazeCommand.getClass()
                        .getAnnotation<com.google.devtools.build.lib.runtime.Command?>(com.google.devtools.build.lib.runtime.Command::class.java)
                })
    }

    override fun get(commandName: String?): com.google.common.collect.ImmutableSet<String?> {
        var cachedResult: com.google.common.collect.ImmutableSet<String?>? = cache.get(commandName)
        if (cachedResult != null) {
            return cachedResult
        }
        val builder: com.google.common.collect.ImmutableSet.Builder<String?> =
            com.google.common.collect.ImmutableSet.builder<String?>()

        val command: com.google.devtools.build.lib.runtime.Command =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.runtime.Command>(
                commandMap.get(commandName), commandName
            )
        val visited: MutableSet<com.google.devtools.build.lib.runtime.Command?> =
            HashSet<com.google.devtools.build.lib.runtime.Command?>()
        visited.add(command)
        val queue: java.util.Queue<com.google.devtools.build.lib.runtime.Command> =
            ArrayDeque<com.google.devtools.build.lib.runtime.Command>()
        queue.add(command)
        while (!queue.isEmpty()) {
            val cur: com.google.devtools.build.lib.runtime.Command = queue.remove()
            builder.add(cur.name)
            for (clazz in cur.inheritsOptionsFrom) {
                val parent: com.google.devtools.build.lib.runtime.Command? =
                    clazz.getAnnotation<com.google.devtools.build.lib.runtime.Command?>(com.google.devtools.build.lib.runtime.Command::class.java)
                if (visited.add(parent)) {
                    queue.add(parent)
                }
            }
        }
        cachedResult = builder.build()
        cache.put(commandName, cachedResult)
        return cachedResult
    }
}
