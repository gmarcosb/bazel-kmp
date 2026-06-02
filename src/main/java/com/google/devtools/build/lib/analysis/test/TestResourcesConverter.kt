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
package com.google.devtools.build.lib.analysis.test

import BuildEventStreamProtos.TestSize
import com.google.devtools.build.lib.packages.TestSize
import com.google.devtools.build.lib.util.ResourceConverter

class TestResourcesConverter

    :
    com.google.devtools.common.options.Converter.Contextless<com.google.devtools.build.lib.util.Pair<String?, MutableMap<TestSize?, Double?>?>?>() {
    val typeDescription: String
        get() = "a resource name followed by equal and 1 float or 4 float, e.g memory=10,30,60,100"

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    override fun convert(input: String): com.google.devtools.build.lib.util.Pair<String?, MutableMap<TestSize?, Double?>?> {
        val assignment: MutableMap.MutableEntry<String?, String?> = assignmentConverter.convert(input)
        val values: java.util.ArrayList<Double?> = java.util.ArrayList<Double?>(TestSize.entries.size)
        for (s in com.google.common.base.Splitter.on(",").splitToList(assignment.value)) {
            values.add(resourceConverter.convert(s))
        }

        if (values.size != 1 && values.size != TestSize.entries.size) {
            throw com.google.devtools.common.options.OptionsParsingException("Invalid number of comma-separated entries in " + input)
        }

        val amounts: java.util.EnumMap<TestSize?, Double?> = java.util.EnumMap<TestSize?, Double?>(TestSize::class.java)
        for (size in TestSize.entries) {
            amounts.put(size, values.get(min(values.size - 1, size.ordinal)))
        }
        return com.google.devtools.build.lib.util.Pair.of<String?, MutableMap<TestSize?, Double?>?>(
            assignment.key,
            amounts
        )
    }

    companion object {
        private val assignmentConverter: com.google.devtools.common.options.Converters.AssignmentConverter =
            com.google.devtools.common.options.Converters.AssignmentConverter()
        private val resourceConverter: ResourceConverter.DoubleConverter =
            ResourceConverter.DoubleConverter( /* keywords= */
                com.google.common.collect.ImmutableMap.of<String?, java.util.function.Supplier<Double?>?>(
                    ResourceConverter.HOST_CPUS_KEYWORD,
                    java.util.function.Supplier { ResourceConverter.HOST_CPUS_SUPPLIER.get().toDouble() },
                    ResourceConverter.HOST_RAM_KEYWORD,
                    java.util.function.Supplier {
                        ResourceConverter.HOST_RAM_SUPPLIER.get().toDouble()
                    }),  /* minValue= */
                0.0,  /* maxValue= */
                Double.Companion.MAX_VALUE
            )
    }
}
