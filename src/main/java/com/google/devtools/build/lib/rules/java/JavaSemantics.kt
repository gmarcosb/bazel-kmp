// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.common.collect.ImmutableList
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.analysis.OutputGroupInfo
import com.google.devtools.build.lib.util.FileType
import java.util.*

/** Pluggable Java compilation semantics.  */
interface JavaSemantics {
    val javaToolchainType: String?

    val javaRuntimeToolchainType: Label?

    /**
     * Takes the path of a Java resource and tries to determine the Java root relative path of the
     * resource.
     * 
     * 
     * This is only used if the Java rule doesn't have a `resource_strip_prefix` attribute.
     * 
     * @param path the root relative path of the resource.
     * @return the Java root relative path of the resource of the root relative path of the resource
     * if no Java root relative path can be determined.
     */
    fun getDefaultJavaResourcePath(path: PathFragment?): PathFragment?

    /** Environment variable that sets the UTF-8 charset for the given execution platform.  */
    fun utf8Environment(executionPlatform: PlatformInfo?): ImmutableMap<String?, String?>?

    /** Whether to enable parallelism in Turbine.  */
    fun turbineParallelism(): Boolean

    /** Returns the name of the tool to use for fixing dependencies in Java rules.  */
    fun getFixDepsTool(rule: Rule?, javaConfiguration: JavaConfiguration?): Optional<String?>?

    companion object {
        // transformed by Copybara on export
        const val RULES_JAVA_PROVIDER_LABELS_PREFIX: String = "@@rules_java+//"

        @kotlin.jvm.JvmField
        val JAVA_SOURCE: FileType = FileType.of(".java")
        val JAR: FileType = FileType.of(".jar")
        val PROPERTIES: FileType = FileType.of(".properties")

        /** Name of the output group used for transitive source jars.  */
        @kotlin.jvm.JvmField
        val SOURCE_JARS_OUTPUT_GROUP: String = OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX + "source_jars"

        /** Name of the output group used for direct source jars.  */
        @kotlin.jvm.JvmField
        val DIRECT_SOURCE_JARS_OUTPUT_GROUP: String = OutputGroupInfo.HIDDEN_OUTPUT_GROUP_PREFIX + "direct_source_jars"

        @SerializationConstant
        val JAVA_PLUGINS: LabelListLateBoundDefault<JavaConfiguration?>? =
            LabelListLateBoundDefault.fromTargetConfiguration(
                JavaConfiguration::class.java,
                { rule, attributes, javaConfig -> ImmutableList.copyOf(javaConfig.getPlugins()) })
    }
}

