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

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.EmptyToNullLabelConverter
import com.google.devtools.common.options.*

/** Command-line options for building Java targets  */
@OptionsClass
abstract class JavaOptions : FragmentOptions() {
    /** Converter for the --java_classpath option.  */
    class JavaClasspathModeConverter :
        EnumConverter<JavaClasspathMode?>(JavaClasspathMode::class.java, "Java classpath reduction strategy")

    /** Converter for the --experimental_one_version_enforcement option  */
    class OneVersionEnforcementLevelConverter

        : EnumConverter<OneVersionEnforcementLevel?>(
        OneVersionEnforcementLevel::class.java,
        "Enforcement level for Java One Version violations"
    )

    @get:Option(
        name = "javacopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Additional options to pass to javac."
    )
    abstract var javacOpts: MutableList<String?>?

    @get:Option(
        name = "host_javacopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Additional options to pass to javac when building tools that are executed during a"
                + " build.")
    )
    abstract var hostJavacOpts: MutableList<String?>?

    @get:Option(
        name = "jvmopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Additional options to pass to the Java VM. These options will get added to the "
                + "VM startup options of each java_binary target.")
    )
    abstract val jvmOpts: MutableList<String?>?

    @get:Option(
        name = "host_jvmopt",
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Additional options to pass to the Java VM when building tools that are executed during "
                + " the build. These options will get added to the VM startup options of each "
                + " java_binary target.")
    )
    abstract val hostJvmOpts: MutableList<String?>?

    @get:Option(
        name = "use_ijars",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("If enabled, this option causes Java compilation to use interface jars. "
                + "This will result in faster incremental compilation, "
                + "but error messages can be different.")
    )
    abstract val useIjars: Boolean

    @get:Option(
        name = "java_header_compilation",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Compile ijars directly from source.",
        oldName = "experimental_java_header_compilation"
    )
    abstract val headerCompilation: Boolean

    @get:Option(
        name = "java_deps",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Generate dependency information (for now, compile-time classpath) per Java target."
    )
    abstract val javaDeps: Boolean

    @get:Option(
        name = "experimental_java_classpath",
        allowMultiple = false,
        defaultValue = "bazel",
        converter = JavaClasspathModeConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Enables reduced classpaths for Java compilations.",
        oldName = "java_classpath"
    )
    abstract val javaClasspath: JavaClasspathMode?

    @get:Option(
        name = "experimental_inmemory_jdeps_files",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.BUILD_TIME_OPTIMIZATION,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION, OptionEffectTag.AFFECTS_OUTPUTS
        ],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("If enabled, the dependency (.jdeps) files generated from Java compilations will be "
                + "passed through in memory directly from the remote build nodes instead of being "
                + "written to disk.")
    )
    abstract val inmemoryJdepsFiles: Boolean

    @get:Option(
        name = "java_debug",
        defaultValue = "null",
        expansion = ["--test_arg=--wrapper_script_flag=--debug", "--test_output=streamed", "--test_strategy=exclusive", "--test_timeout=9999" // Do not increase this without consulting b/459811767#comment3.
            , "--nocache_test_results"
        ],
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Causes the Java virtual machine of a java test to wait for a connection from a "
                + "JDWP-compliant debugger (such as jdb) before starting the test. Implies "
                + "-test_output=streamed.")
    )
    abstract val javaTestDebug: Void?

    @get:Option(
        name = "experimental_strict_java_deps",
        allowMultiple = false,
        defaultValue = "default",
        converter = StrictDepsConverter::class,
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS, OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("If true, checks that a Java target explicitly declares all directly used "
                + "targets as dependencies."),
        oldName = "strict_java_deps"
    )
    abstract val strictJavaDeps: StrictDepsMode?

    @get:Option(
        name = "experimental_fix_deps_tool",
        defaultValue = "add_dep",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.BUILD_FILE_SEMANTICS],
        help = "Specifies which tool should be used to resolve missing dependencies."
    )
    abstract val fixDepsTool: String?

    @get:Option(
        name = "explicit_java_test_deps",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Explicitly specify a dependency to JUnit or Hamcrest in a java_test instead of "
                + " accidentally obtaining from the TestRunner's deps. Only works for bazel right "
                + "now.")
    )
    abstract val explicitJavaTestDeps: Boolean

    @get:Option(
        name = "host_java_launcher",
        defaultValue = "null",
        converter = EmptyToNullLabelConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The Java launcher used by tools that are executed during a build."
    )
    abstract val hostJavaLauncher: Label?

    @get:Option(
        name = "java_launcher",
        defaultValue = "null",
        converter = EmptyToNullLabelConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("The Java launcher to use when building Java binaries. "
                + " If this flag is set to the empty string, the JDK launcher is used. "
                + "The \"launcher\" attribute overrides this flag. ")
    )
    abstract val javaLauncher: Label?

    @get:Option(
        name = "proguard_top",
        defaultValue = "null",
        converter = LabelConverter::class,
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = ("Specifies which version of ProGuard to use for code removal when building a Java "
                + "binary.")
    )
    abstract val proguard: Label?

    @get:Option(
        name = "bytecode_optimizers",
        defaultValue = "Proguard",
        converter = LabelMapConverter::class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Do not use.",
        oldName = "experimental_bytecode_optimizers"
    )
    abstract val bytecodeOptimizers: MutableMap<String?, Label>?

    @get:Option(
        name = "experimental_local_java_optimizations",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "Do not use."
    )
    abstract val runLocalJavaOptimizations: Boolean

    @get:Option(
        name = "experimental_local_java_optimization_configuration",
        defaultValue = "null",
        converter = LabelConverter::class,
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Do not use."
    )
    abstract val localJavaOptimizationConfiguration: Label?

    // TODO(b/237004872) Remove this after rollout of bytecode_optimization_pass_actions.
    @get:Option(
        name = "split_bytecode_optimization_pass",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Do not use."
    )
    abstract val splitBytecodeOptimizationPass: Boolean

    @get:Option(
        name = "bytecode_optimization_pass_actions",
        defaultValue = "1",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Do not use."
    )
    abstract val bytecodeOptimizationPassActions: Int

    @get:Option(
        name = "enforce_proguard_file_extension",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.EAGERNESS_TO_EXIT],
        help = ("If enabled, requires that ProGuard configuration files outside of third_party/ use the"
                + " *.pgcfg file extension.")
    )
    abstract val enforceProguardFileExtension: Boolean

    @get:Option(
        name = "experimental_one_version_enforcement",
        defaultValue = "OFF",
        converter = OneVersionEnforcementLevelConverter::class,
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("When enabled, enforce that a java_binary rule can't contain more than one version "
                + "of the same class file on the classpath. This enforcement can break the build, or "
                + "can just result in warnings.")
    )
    abstract val enforceOneVersion: OneVersionEnforcementLevel?

    @get:Option(
        name = "one_version_enforcement_on_java_tests",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.INPUT_STRICTNESS,
        effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
        help = ("When enabled, and with experimental_one_version_enforcement set to a non-NONE value,"
                + " enforce one version on java_test targets. This flag can be disabled to improve"
                + " incremental test performance at the expense of missing potential one version"
                + " violations.")
    )
    abstract val enforceOneVersionOnJavaTests: Boolean

    @get:Option(
        name = "experimental_add_test_support_to_compile_time_deps",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = ("Flag to help transition away from adding test support libraries to the compile-time"
                + " deps of Java test rules.")
    )
    abstract val addTestSupportToCompileTimeDeps: Boolean

    @get:Option(
        name = "experimental_run_android_lint_on_java_rules",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.AFFECTS_OUTPUTS],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "Whether to validate java_* sources."
    )
    abstract val runAndroidLint: Boolean

    @get:Option(
        name = "plugin",
        converter = LabelListConverter::class,
        allowMultiple = true,
        defaultValue = "null",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "Plugins to use in the build. Currently works with java_plugin."
    )
    abstract val pluginList: MutableList<Label>?

    @get:Option(
        name = "experimental_turbine_annotation_processing",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "If enabled, turbine is used for all annotation processing"
    )
    abstract val experimentalTurbineAnnotationProcessing: Boolean

    @get:Option(
        name = "experimental_turbine_cpu_reservation",
        defaultValue = "1",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The number of CPUs to reserve for Turbine."
    )
    abstract val turbineCpuReservation: Int

    @get:Option(
        name = "java_runtime_version",
        defaultValue = "local_jdk",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The Java runtime version"
    )
    abstract val javaRuntimeVersion: String?

    @get:Option(
        name = "tool_java_runtime_version",
        defaultValue = "remotejdk_11",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The Java runtime version used to execute tools during the build"
    )
    abstract val hostJavaRuntimeVersion: String?

    @get:Option(
        name = "java_language_version",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The Java language version"
    )
    abstract val javaLanguageVersion: String?

    @get:Option(
        name = "tool_java_language_version",
        defaultValue = "",
        documentationCategory = OptionDocumentationCategory.UNCATEGORIZED,
        effectTags = [OptionEffectTag.UNKNOWN],
        help = "The Java language version used to execute the tools that are needed during a build"
    )
    abstract val hostJavaLanguageVersion: String?

    @get:Option(
        name = "incompatible_multi_release_deploy_jars",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "When enabled, java_binary creates Multi-Release deploy jars."
    )
    abstract val multiReleaseDeployJars: Boolean

    @get:Option(
        name = "incompatible_disallow_java_import_exports",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "When enabled, java_import.exports is not supported."
    )
    abstract val disallowJavaImportExports: Boolean

    @get:Option(
        name = "experimental_enable_jspecify",
        defaultValue = "true",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "Enable experimental jspecify integration."
    )
    abstract val experimentalEnableJspecify: Boolean

    @get:Option(
        name = "experimental_java_test_auto_create_deploy_jar",
        defaultValue = "false",
        documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [OptionEffectTag.UNKNOWN],
        metadataTags = [OptionMetadataTag.EXPERIMENTAL],
        help = "DO NOT USE"
    )
    abstract val autoCreateDeployJarForJavaTests: Boolean
}
