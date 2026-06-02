// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.starlarkbuildapi.android

import com.google.devtools.build.docgen.annot.DocCategory
import net.starlark.java.annot.StarlarkBuiltin
import net.starlark.java.annot.StarlarkMethod
import net.starlark.java.eval.StarlarkValue

/** Configuration fragment for Android rules.  */
@StarlarkBuiltin(
    name = "android",
    doc = ("Do not use this module. It is intended for migration purposes only. If you depend on it, "
            + "you will be broken when it is removed. "
            + "A configuration fragment for Android."),
    documented = false,
    category = DocCategory.CONFIGURATION_FRAGMENT
)
interface AndroidConfigurationApi : StarlarkValue {
    @StarlarkMethod(name = "incremental_dexing_shards_after_proguard", structField = true, doc = "", documented = false)
    fun incrementalDexingShardsAfterProguard(): Int

    @StarlarkMethod(name = "apk_signing_method_v1", structField = true, doc = "", documented = false)
    fun apkSigningMethodV1(): Boolean

    @StarlarkMethod(name = "apk_signing_method_v2", structField = true, doc = "", documented = false)
    fun apkSigningMethodV2(): Boolean

    @StarlarkMethod(
        name = "apk_signing_method_v4",
        structField = true,
        doc = "",
        documented = false,
        allowReturnNones = true
    )
    fun apkSigningMethodV4(): Boolean?

    @get:StarlarkMethod(
        name = "get_dexopts_supported_in_incremental_dexing",
        structField = true,
        doc = "",
        documented = false
    )
    val dexoptsSupportedInIncrementalDexing: ImmutableList<String?>?

    @get:StarlarkMethod(
        name = "get_dexopts_supported_in_dex_merger",
        structField = true,
        doc = "",
        documented = false
    )
    val dexoptsSupportedInDexMerger: ImmutableList<String?>?

    @get:StarlarkMethod(
        name = "get_dexopts_supported_in_dex_sharder",
        structField = true,
        doc = "",
        documented = false
    )
    val dexoptsSupportedInDexSharder: ImmutableList<String?>?

    @StarlarkMethod(name = "desugar_java8", structField = true, doc = "", documented = false)
    fun desugarJava8(): Boolean

    @StarlarkMethod(name = "desugar_java8_libs", structField = true, doc = "", documented = false)
    fun desugarJava8Libs(): Boolean

    @StarlarkMethod(name = "use_android_resource_shrinking", structField = true, doc = "", documented = false)
    fun useAndroidResourceShrinking(): Boolean

    @StarlarkMethod(name = "use_android_resource_cycle_shrinking", structField = true, doc = "", documented = false)
    fun useAndroidResourceCycleShrinking(): Boolean

    @StarlarkMethod(name = "use_android_resource_path_shortening", structField = true, doc = "", documented = false)
    fun useAndroidResourcePathShortening(): Boolean

    @StarlarkMethod(name = "use_android_resource_name_obfuscation", structField = true, doc = "", documented = false)
    fun useAndroidResourceNameObfuscation(): Boolean

    @StarlarkMethod(name = "compress_java_resources", structField = true, doc = "", documented = false)
    fun compressJavaResources(): Boolean

    @get:StarlarkMethod(
        name = "get_exports_manifest_default",
        structField = true,
        doc = "",
        documented = false
    )
    val exportsManifestDefault: Boolean

    @get:StarlarkMethod(
        name = "manifest_merger",
        structField = true,
        doc = "",
        documented = false
    )
    val manifestMergerValue: String?

    @StarlarkMethod(name = "fixed_resource_neverlinking", structField = true, doc = "", documented = false)
    fun fixedResourceNeverlinking(): Boolean

    @StarlarkMethod(name = "persistent_aar_extractor", structField = true, doc = "", documented = false)
    fun persistentAarExtractor(): Boolean

    @StarlarkMethod(name = "persistent_busybox_tools", structField = true, doc = "", documented = false)
    fun persistentBusyboxTools(): Boolean

    @StarlarkMethod(name = "persistent_multiplex_busybox_tools", structField = true, doc = "", documented = false)
    fun persistentMultiplexBusyboxTools(): Boolean

    @StarlarkMethod(name = "persistent_android_dex_desugar", structField = true, doc = "", documented = false)
    fun persistentDexDesugar(): Boolean

    @StarlarkMethod(name = "persistent_multiplex_android_dex_desugar", structField = true, doc = "", documented = false)
    fun persistentMultiplexDexDesugar(): Boolean

    @get:StarlarkMethod(
        name = "get_output_directory_name",
        structField = true,
        doc = "",
        documented = false
    )
    val outputDirectoryName: String?

    @get:StarlarkMethod(
        name = "get_java_resources_from_optimized_jar",
        structField = true,
        doc = "",
        documented = false
    )
    val javaResourcesFromOptimizedJar: Boolean
}
