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
package com.google.devtools.build.lib.starlarkbuildapi.java

import com.google.devtools.build.docgen.annot.DocCategory
import com.google.devtools.build.lib.cmdline.Label
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod

/**
 * Provides access to information about the Java toolchain rule. Accessible as a 'java_toolchain'
 * field on a Target struct.
 * 
 * 
 * This provider is implemented in Starlark. This class remains only for doc-gen purposes.
 */
@StarlarkBuiltin(
    name = "JavaToolchainInfo",
    category = DocCategory.PROVIDER,
    doc = ("Provides access to information about the Java toolchain rule. "
            + "Accessible as a 'java_toolchain' field on a Target struct.")
)
interface JavaToolchainStarlarkApiProviderApi : StructApi {
    @get:StarlarkMethod(
        name = "source_version",
        doc = "The java source version.",
        structField = true
    )
    val sourceVersion: String?

    @get:StarlarkMethod(
        name = "target_version",
        doc = "The java target version.",
        structField = true
    )
    val targetVersion: String?

    @get:StarlarkMethod(name = "label", doc = "The toolchain label.", structField = true)
    val toolchainLabel: Label?

    @get:StarlarkMethod(
        name = "single_jar",
        doc = "The SingleJar executable.",
        structField = true
    )
    val singleJar: FilesToRunProviderApi<out FileApi?>?

    @get:StarlarkMethod(
        name = "bootclasspath",
        doc = "The Java target bootclasspath entries. Corresponds to javac's -bootclasspath flag.",
        structField = true
    )
    val starlarkBootclasspath: Depset?

    @get:StarlarkMethod(
        name = "jvm_opt",
        doc = "The default options for the JVM running the java compiler and associated tools.",
        structField = true
    )
    val starlarkJvmOptions: Depset?

    @get:StarlarkMethod(
        name = "ijar",
        doc = "A FilesToRunProvider representing the ijar executable.",
        structField = true
    )
    val ijar: FilesToRunProviderApi<*>?

    @get:StarlarkMethod(
        name = "jacocorunner",
        doc = "The jacocorunner used by the toolchain.",
        structField = true,
        allowReturnNones = true
    )
    val jacocoRunner: FilesToRunProviderApi<*>?

    @get:StarlarkMethod(name = "tools", doc = "The compilation tools.", structField = true)
    val starlarkTools: Depset?

    @get:StarlarkMethod(
        name = "java_runtime",
        doc = "The java runtime information.",
        structField = true
    )
    val javaRuntime: JavaRuntimeInfoApi?

    @get:StarlarkMethod(
        name = "proguard_allowlister",
        doc = "Return the binary to validate proguard configuration",
        structField = true,
        allowReturnNones = true
    )
    val proguardAllowlister: FilesToRunProviderApi<out FileApi?>?
}
