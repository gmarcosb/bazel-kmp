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
import net.starlark.java.eval.EvalException
import net.starlark.java.eval.StarlarkThread
import net.starlark.java.eval.StarlarkValue

/** A java compiler configuration.  */
@StarlarkBuiltin(name = "java", doc = "A java compiler configuration.", category = DocCategory.CONFIGURATION_FRAGMENT)
interface JavaConfigurationApi : StarlarkValue {
    @get:StarlarkMethod(
        name = "default_javac_flags",
        structField = true,
        doc = "The default flags for the Java compiler."
    )
    val defaultJavacFlagsForStarlarkAsList: ImmutableList<String?>?

    @get:StarlarkMethod(
        name = "default_javac_flags_depset",
        structField = true,
        doc = "The default flags for the Java compiler."
    )
    val defaultJavacFlagsStarlark: Depset?

    @get:StarlarkMethod(
        name = "strict_java_deps",
        structField = true,
        doc = "The value of the strict_java_deps flag."
    )
    val strictJavaDepsName: String?

    @StarlarkMethod(name = "use_header_compilation", useStarlarkThread = true, documented = false)
    @Throws(
        EvalException::class
    )
    fun useHeaderCompilationStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "generate_java_deps", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun getGenerateJavaDepsStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(name = "reduce_java_classpath", useStarlarkThread = true, documented = false)
    @Throws(EvalException::class)
    fun getReduceJavaClasspathStarlark(thread: StarlarkThread?): String?

    @get:StarlarkMethod(
        name = "default_jvm_opts",
        structField = true,
        doc = "Additional options to pass to the Java VM for each java_binary target"
    )
    val defaultJvmFlags: ImmutableList<String?>?

    @StarlarkMethod(
        name = "one_version_enforcement_level",
        structField = true,
        doc = "The value of the --experimental_one_version_enforcement flag."
    )
    fun starlarkOneVersionEnforcementLevel(): String?

    @StarlarkMethod(name = "one_version_enforcement_on_java_tests", structField = true, documented = false)
    fun enforceOneVersionOnJavaTests(): Boolean

    @StarlarkMethod(name = "add_test_support_to_compile_deps", structField = true, documented = false)
    fun addTestSupportToCompileTimeDeps(): Boolean

    @StarlarkMethod(
        name = "run_android_lint",
        structField = true,
        doc = "The value of the --experimental_run_android_lint_on_java_rules flag."
    )
    fun runAndroidLint(): Boolean

    @StarlarkMethod(name = "enforce_explicit_java_test_deps", useStarlarkThread = true, documented = false)
    @Throws(
        EvalException::class
    )
    fun explicitJavaTestDepsStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(
        name = "multi_release_deploy_jars",
        structField = true,
        doc = "The value of the --incompatible_multi_release_deploy_jars flag."
    )
    fun multiReleaseDeployJars(): Boolean

    @get:StarlarkMethod(
        name = "plugins",
        structField = true,
        doc = "A list containing the labels provided with --plugins, if any."
    )
    val plugins: ImmutableList<Label>?

    @StarlarkMethod(
        name = "use_ijars",
        doc = "Returns true iff Java compilation should use ijars.",
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class
    )
    fun getUseIjarsInStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(
        name = "use_header_compilation_direct_deps",
        doc = "Returns true if Java header compilation should use separate outputs for direct deps.",
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class
    )
    fun getUseHeaderCompilationDirectDepsInStarlark(thread: StarlarkThread?): Boolean

    @StarlarkMethod(
        name = "disallow_java_import_exports",
        doc = "Returns true if java_import exports are not allowed.",
        useStarlarkThread = true
    )
    @Throws(
        EvalException::class
    )
    fun getDisallowJavaImportExportsInStarlark(thread: StarlarkThread?): Boolean

    @get:StarlarkMethod(
        name = "bytecode_optimizer_mnemonic",
        structField = true,
        doc = "The mnemonic for the bytecode optimizer."
    )
    val bytecodeOptimizerMnemonic: String?

    @StarlarkMethod(
        name = "split_bytecode_optimization_pass",
        structField = true,
        doc = ("Returns whether the OPTIMIZATION stage of the bytecode optimizer will be split across"
                + " two actions.")
    )
    fun splitBytecodeOptimizationPass(): Boolean

    @StarlarkMethod(
        name = "bytecode_optimization_pass_actions",
        structField = true,
        doc = ("This specifies the number of actions to divide the OPTIMIZATION stage of the bytecode"
                + " optimizer into. Note that if split_bytecode_optimization_pass is set, this will"
                + " only change behavior if it is > 2.")
    )
    fun bytecodeOptimizationPassActions(): Int

    @StarlarkMethod(
        name = "enforce_proguard_file_extension",
        structField = true,
        doc = ("Returns whether ProGuard configuration files outside of third_party/ are required to use"
                + " a *.pgcfg extension."),
        documented = false
    )
    fun enforceProguardFileExtension(): Boolean

    @StarlarkMethod(name = "auto_create_java_test_deploy_jars", useStarlarkThread = true, documented = false)
    @Throws(
        EvalException::class
    )
    fun autoCreateJavaTestDeployJars(thread: StarlarkThread?): Boolean
}
