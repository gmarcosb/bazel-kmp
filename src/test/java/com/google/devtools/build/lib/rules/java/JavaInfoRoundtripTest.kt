// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests if JavaInfo identical to one returned by Java rules can be constructed.  */
@RunWith(JUnit4::class)
class JavaInfoRoundtripTest : BuildViewTestCase() {
    /** A rule to convert JavaInfo to a structure having only string values.  */
    @Before
    @Throws(java.lang.Exception::class)
    fun javaInfoToDict() {
        mockToolsConfig.create("tools/build_defs/inspect/BUILD")
        mockToolsConfig.copyTool(
            TestConstants.BAZEL_REPO_SCRATCH + "tools/build_defs/inspect/struct_to_dict.bzl",
            "tools/build_defs/inspect/struct_to_dict.bzl"
        )

        scratch.file(
            "javainfo/javainfo_to_dict.bzl",
            """
        load("@rules_java//java/common:java_info.bzl", "JavaInfo")
        load("//tools/build_defs/inspect:struct_to_dict.bzl", "struct_to_dict")
        Info = provider()
        def _impl(ctx):
            return Info(result = struct_to_dict(ctx.attr.dep[JavaInfo], 10))

        javainfo_to_dict = rule(_impl, attrs = {"dep": attr.label()})
        
        """.trimIndent()
        )
    }

    /** A simple rule that calls JavaInfo constructor using identical attribute as java_library.  */
    @Before
    @Throws(java.lang.Exception::class)
    fun constructJavaInfo() {
        if (!getAnalysisMock().isThisBazel) {
            setBuildLanguageOptions("--experimental_google_legacy_api")
        }
        scratch.file(
            "foo/construct_javainfo.bzl",
            """
        load("@rules_java//java:defs.bzl", "JavaInfo")
        def _impl(ctx):
            OUTS = {
                "lib": "lib%s.jar",
                "hjar": "lib%s-hjar.jar",
                "src": "lib%s-src.jar",
                "compile_jdeps": "lib%s-hjar.jdeps",
                "genclass": "lib%s-gen.jar",
                "gensource": "lib%s-gensrc.jar",
                "jdeps": "lib%s.jdeps",
                "manifest": "lib%s.jar_manifest_proto",
                "headers": "lib%s-native-header.jar",
                "tjar": "lib%s-tjar.jar",
            }
            for file, name in OUTS.items():
                OUTS[file] = ctx.actions.declare_file(name % ctx.label.name)
                ctx.actions.write(OUTS[file], "")

            java_info = JavaInfo(
                output_jar = OUTS["lib"],
                compile_jar = OUTS["hjar"],
                source_jar = OUTS["src"],
                compile_jdeps = OUTS["compile_jdeps"],
                generated_class_jar = ctx.attr.plugins and OUTS["genclass"] or None,
                generated_source_jar = ctx.attr.plugins and OUTS["gensource"] or None,
                manifest_proto = OUTS["manifest"],
                native_headers_jar = OUTS["headers"],
                deps = [d[JavaInfo] for d in ctx.attr.deps],
                runtime_deps = [d[JavaInfo] for d in ctx.attr.runtime_deps],
                exports = [d[JavaInfo] for d in ctx.attr.exports],
                jdeps = OUTS["jdeps"],
                header_compilation_jar = OUTS["tjar"],
            )
            return [java_info]

        construct_javainfo = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = True),
                "deps": attr.label_list(),
                "runtime_deps": attr.label_list(),
                "exports": attr.label_list(),
                "plugins": attr.bool(default = False),
            },
            fragments = ["java"],
        )
        
        """.trimIndent()
        )
    }

    /** For a given target providing JavaInfo returns a Starlark Dict with String values  */
    @Throws(java.lang.Exception::class)
    private fun getDictFromJavaInfo(packageName: String?, javaInfoTarget: String?): Dict<Any?, Any?> {
        // Because we're overwriting files to have identical names, we need to invalidate them.
        skyframeExecutor.invalidateFilesUnderPathForTesting(
            reporter,
            Builder().modify(PathFragment.create(packageName + "/BUILD")).build(),
            Root.fromPath(rootDirectory)
        )

        scratch.deleteFile("javainfo/BUILD")
        val dictTarget: ConfiguredTarget =
            scratchConfiguredTarget(
                "javainfo",
                "javainfo",
                "load(':javainfo_to_dict.bzl', 'javainfo_to_dict')",
                "javainfo_to_dict(",
                "  name = 'javainfo',",
                "  dep = '//" + packageName + ':' + javaInfoTarget + "',",
                ")"
            )
        val dictInfo: StarlarkInfo = getStarlarkProvider(dictTarget, "Info")
        val javaInfo:  // deserialization
                Dict<Any?, Any?>? = dictInfo.getValue("result") as Dict<Any?, Any?>?
        return
        Companion.deepStripAttributes<Dict<Any?, Any?>?>(
            javaInfo,
            java.util.function.Predicate { attr: String? -> attr.startsWith("_") })
    }

    private fun removeCompilationInfo(javaInfo: Dict<Any?, Any?>?): Dict<Any?, Any?> {
        return Dict.builder<Any?, Any?>().putAll(javaInfo).put("compilation_info", Starlark.NONE).buildImmutable()
    }

    private fun removeAnnotationClasses(javaInfo: Dict<Any?, Any?>): Dict<Any?, Any?> {
        var annotationProcessing:  // safe by specification
                Dict<Any?, Any?>? =
            javaInfo.get("annotation_processing") as Dict<Any?, Any?>?

        annotationProcessing =
            Dict.builder<Any?, Any?>()
                .putAll(annotationProcessing)
                .put("enabled", false)
                .put("processor_classnames", StarlarkList.immutableOf<Any?>())
                .put("processor_classpath", StarlarkList.immutableOf<Any?>())
                .buildImmutable()
        return Dict.builder<Any?, Any?>()
            .putAll(javaInfo)
            .put("annotation_processing", annotationProcessing)
            .buildImmutable()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dictFromJavaInfo_nonEmpty() {
        scratch.overwriteFile(
            "foo/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'java_lib', srcs = ['A.java'])"
        )

        val javaInfo: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        Truth.assertThat(javaInfo as MutableMap<*, *>?).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dictFromJavaInfo_detectsDifference() {
        scratch.overwriteFile(
            "foo/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'java_lib', srcs = ['A.java'])"
        )
        val javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        scratch.overwriteFile(
            "foo/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'java_lib2', srcs = ['A.java'])"
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib2")

        Truth.assertThat(javaInfoA as MutableMap<*, *>?).isNotEqualTo(javaInfoB)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundtripJavainfo_srcs() {
        scratch.overwriteFile(
            "foo/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'java_lib', srcs = ['A.java'])"
        )
        var javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
        )
        
        """.trimIndent()
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        javaInfoA = removeCompilationInfo(javaInfoA)
        Truth.assertThat(javaInfoB as MutableMap<*, *>?).isEqualTo(javaInfoA)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundtripJavaInfo_deps() {
        scratch.file(
            "bar/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'javalib', srcs = ['A.java'])"
        )

        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            deps = ["//bar:javalib"],
        )
        
        """.trimIndent()
        )
        var javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            deps = ["//bar:javalib"],
        )
        
        """.trimIndent()
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        javaInfoA = removeCompilationInfo(javaInfoA)
        Truth.assertThat(javaInfoB as MutableMap<*, *>?).isEqualTo(javaInfoA)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundtipJavaInfo_runtimeDeps() {
        scratch.file(
            "bar/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'deplib', srcs = ['A.java'])"
        )

        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            runtime_deps = ["//bar:deplib"],
        )
        
        """.trimIndent()
        )
        var javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            runtime_deps = ["//bar:deplib"],
        )
        
        """.trimIndent()
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        javaInfoA = removeCompilationInfo(javaInfoA)
        Truth.assertThat(javaInfoB as MutableMap<*, *>?).isEqualTo(javaInfoA)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundtipJavaInfo_exports() {
        scratch.file(
            "bar/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_library')",
            "java_library(name = 'exportlib', srcs = ['A.java'])"
        )

        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            exports = ["//bar:exportlib"],
        )
        
        """.trimIndent()
        )
        var javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            exports = ["//bar:exportlib"],
        )
        
        """.trimIndent()
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        javaInfoA = removeCompilationInfo(javaInfoA)
        Truth.assertThat(javaInfoB as MutableMap<*, *>?).isEqualTo(javaInfoA)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun roundtipJavaInfo_plugin() {
        scratch.file(
            "bar/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_plugin')",
            "java_plugin(name = 'plugin', srcs = ['A.java'], processor_class = 'bar.Main')"
        )

        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library")
        java_library(
            name = "java_lib",
            srcs = ["A.java"],
            plugins = ["//bar:plugin"],
        )
        
        """.trimIndent()
        )
        var javaInfoA: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")
        scratch.overwriteFile(
            "foo/BUILD",
            """
        load("//foo:construct_javainfo.bzl", "construct_javainfo")

        construct_javainfo(
            name = "java_lib",
            srcs = ["A.java"],
            plugins = True,
        )
        
        """.trimIndent()
        )
        val javaInfoB: Dict<Any?, Any?> = getDictFromJavaInfo("foo", "java_lib")

        javaInfoA = removeCompilationInfo(javaInfoA)
        javaInfoA = removeAnnotationClasses(javaInfoA)
        Truth.assertThat(javaInfoB as MutableMap<*, *>?).isEqualTo(javaInfoA)
    }

    companion object {
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun <T> deepStripAttributes(obj: T?, shouldRemove: java.util.function.Predicate<String?>): T? {
            if (obj == null) {
                return null
            } else if (obj is StarlarkList) {
                val builder: com.google.common.collect.ImmutableList.Builder<Any?> =
                    com.google.common.collect.ImmutableList.builder<Any?>()
                for (item in obj as StarlarkList<Any?>) {
                    builder.add(Companion.deepStripAttributes<Any?>(item, shouldRemove))
                }
                return StarlarkList.immutableCopyOf<Any?>(builder.build()) as T?
            } else if (obj is Structure) {
                for (fieldName in obj.getFieldNames()) {
                    val builder: net.starlark.java.eval.Dict.Builder<String?, Any?> = Dict.builder<String?, Any?>()
                    if (!shouldRemove.test(fieldName)) {
                        builder.put(
                            fieldName,
                            Companion.deepStripAttributes<Any?>((obj as Structure).getValue(fieldName), shouldRemove)
                        )
                    }
                    return StructProvider.STRUCT.create(builder.buildImmutable(), "") as T?
                }
            } else if (obj is Dict) {
                val builder: net.starlark.java.eval.Dict.Builder<Any?, Any?> = Dict.builder<Any?, Any?>()
                for (e in Dict.cast<Any?, Any?>(obj, Any::class.java, Any::class.java, "dict").entries) {
                    if (!(e.key is String && shouldRemove.test(e.key as String?))) {
                        builder.put(e.key, Companion.deepStripAttributes<Any?>(e.value, shouldRemove))
                    }
                }
                return builder.buildImmutable() as T?
            }
            return obj
        }
    }
}
