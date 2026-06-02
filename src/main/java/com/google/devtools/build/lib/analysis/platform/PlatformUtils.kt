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
package com.google.devtools.build.lib.analysis.platform

import build.bazel.remote.execution.v2.Platform

/** Utilities for accessing platform properties.  */
object PlatformUtils {
    private fun sortPlatformProperties(builder: Platform.Builder) {
        val properties: MutableList<Platform.Property?> =
            com.google.common.collect.Ordering.from<Any?>(java.util.Comparator.comparing<Any?, Any?>(Platform.Property::getName))
                .sortedCopy<E?>(builder.getPropertiesList())
        builder.clearProperties()
        builder.addAllProperties(properties)
    }

    fun buildPlatformProto(executionProperties: MutableMap<String?, String?>): Platform? {
        if (executionProperties.isEmpty()) {
            return null
        }
        val builder: Platform.Builder = Platform.newBuilder()
        for (keyValue in executionProperties.entries) {
            val property: Property? =
                Property.newBuilder()
                    .setName(StringEncoding.internalToUnicode(keyValue.key))
                    .setValue(StringEncoding.internalToUnicode(keyValue.value))
                    .build()
            builder.addProperties(property)
        }

        com.google.devtools.build.lib.analysis.platform.PlatformUtils.sortPlatformProperties(builder)
        return builder.build()
    }

    @Throws(UserExecException::class)
    fun getPlatformProto(spawn: Spawn, remoteOptions: RemoteOptions?): Platform? {
        return com.google.devtools.build.lib.analysis.platform.PlatformUtils.getPlatformProto(
            spawn,
            remoteOptions,
            com.google.common.collect.ImmutableMap.of<String?, String?>()
        )
    }

    private fun shouldProducePlatformProto(
        spawn: Spawn,
        defaultExecProperties: SortedMap<String?, String?>,
        additionalProperties: MutableMap<String?, String?>
    ): Boolean {
        val executionPlatform: com.google.devtools.build.lib.analysis.platform.PlatformInfo? =
            spawn.getExecutionPlatform()
        if (executionPlatform != null) {
            if (!executionPlatform.execProperties().isEmpty()) {
                return true
            }
        }
        if (!spawn.getCombinedExecProperties().isEmpty()) {
            return true
        }
        if (!defaultExecProperties.isEmpty()) {
            return true
        }
        if (!additionalProperties.isEmpty()) {
            return true
        }
        return false
    }

    @Throws(UserExecException::class)
    fun getPlatformProto(
        spawn: Spawn, remoteOptions: RemoteOptions?, additionalProperties: MutableMap<String?, String?>
    ): Platform? {
        val defaultExecProperties: SortedMap<String?, String?> =
            if (remoteOptions != null)
                remoteOptions.getRemoteDefaultExecProperties()
            else
                com.google.common.collect.ImmutableSortedMap.of<String?, String?>()

        if (!com.google.devtools.build.lib.analysis.platform.PlatformUtils.shouldProducePlatformProto(
                spawn,
                defaultExecProperties,
                additionalProperties
            )
        ) {
            // Execution platform is null or functionally empty
            return null
        }

        var properties: MutableMap<String?, String?> = HashMap<String?, String?>()
        if (!spawn.getCombinedExecProperties().isEmpty()) {
            // Apply default exec properties if the execution platform does not already set
            // exec_properties
            if (spawn.getExecutionPlatform() == null
                || spawn.getExecutionPlatform().execProperties().isEmpty()
            ) {
                properties.putAll(defaultExecProperties)
                properties.putAll(spawn.getCombinedExecProperties())
            } else {
                properties = spawn.getCombinedExecProperties()
            }
        } else if (spawn.getExecutionPlatform() != null) {
            properties.putAll(spawn.getExecutionPlatform().execProperties())
        }

        if (properties.isEmpty()) {
            properties = defaultExecProperties
        }

        if (!additionalProperties.isEmpty()) {
            if (properties.isEmpty()) {
                properties = additionalProperties
            } else {
                // Merge the two maps.
                properties = HashMap<String?, String?>(properties)
                properties.putAll(additionalProperties)
            }
        }

        val platformBuilder: Platform.Builder = Platform.newBuilder()
        for (entry in properties.entries) {
            platformBuilder
                .addPropertiesBuilder()
                .setName(StringEncoding.internalToUnicode(entry.key))
                .setValue(StringEncoding.internalToUnicode(entry.value))
        }
        com.google.devtools.build.lib.analysis.platform.PlatformUtils.sortPlatformProperties(platformBuilder)
        return platformBuilder.build()
    }
}
