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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.runtime.BlazeCommand
import kotlin.reflect.KClass

/**
 * An annotation that lets blaze commands specify their options and their help.
 * The annotations are processed by [BlazeCommand].
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Command(
    /**
     * The name of the command, as the user would type it.
     */
    val name: String,
    /**
     * Options processed by the command, indicated by options interfaces. These interfaces must
     * contain methods annotated with [com.google.devtools.common.options.Option].
     */
    val options: Array<KClass<out com.google.devtools.common.options.OptionsBase?>> = [],
    /**
     * The set of other Blaze commands that this annotation's command "inherits" options from. These
     * classes must be annotated with [Command].
     */
    val inheritsOptionsFrom: Array<KClass<out BlazeCommand?>> = [],
    /**
     * A short description, which appears in 'blaze help'.
     */
    val shortDescription: String,
    /**
     * True if the configuration-specific options should be available for this command.
     */
    val usesConfigurationOptions: Boolean = false,
    /**
     * The build phase associated with this command.
     * 
     * 
     * Use the enum helper methods to check the hierarchical effects of each command, like [ ][BuildPhase.executes], [BuildPhase.loads], instead of checking the enum value
     * directly.
     */
    val buildPhase: BuildPhase = BuildPhase.NONE,
    /**
     * True if the command should not be shown in the output of 'blaze help'.
     */
    val hidden: Boolean = false,
    /**
     * Specifies whether this command allows a residue after the parsed options.
     * For example, a command might expect a list of targets to build in the
     * residue.
     */
    val allowResidue: Boolean = false,
    /**
     * Specifies whether the command line residue might have sensitive data, or arbitrary command
     * line values.
     */
    val hasSensitiveResidue: Boolean = false,
    /**
     * Returns true if this command wants to write binary data to stdout.
     * Enabling this flag will disable ANSI escape stripping for this command.
     * This should be used in conjunction with `Reporter#switchToAnsiAllowingHandler`.
     * See [RunCommand] for example usage.
     */
    val binaryStdOut: Boolean = false,
    /**
     * Returns true if this command wants to write binary data to stderr.
     * Enabling this flag will disable ANSI escape stripping for this command.
     * This should be used in conjunction with `Reporter#switchToAnsiAllowingHandler`.
     * See [RunCommand] for example usage.
     */
    val binaryStdErr: Boolean = false,
    /**
     * Returns true if this command may want to write to the command.log.
     * 
     * 
     * The clean command would typically set this to false so it can delete the command.log.
     */
    val writeCommandLog: Boolean = true,
    /**
     * The help message for this command.  If the value starts with "resource:",
     * the remainder is interpreted as the name of a text file resource (in the
     * .jar file that provides the Command implementation class).
     */
    val help: String,
    /**
     * Returns true iff this command may only be run from within a Blaze workspace. Broadly, this
     * should be true for any command that interprets the package-path, since it's potentially
     * confusing otherwise.
     */
    val mustRunInWorkspace: Boolean = true,
    /**
     * Returns the type completion help for this command, that is the type arguments that this command
     * expects. It can be a whitespace separated list if the command take several arguments. The type
     * of each arguments can be `label`, `path`, `string`, ... It can
     * also be a comma separated list of values, e.g. `{value1,value2}`. If a command
     * accept several argument types, they can be combined with |, e.g `label|path`.
     */
    val completion: String = ""
) {
    /**
     * Build phases that can be associated with a command.
     * 
     * 
     * The effects are hierarchical: `EXECUTES` implies `ANALYZES`, but `LOADS`
     * does not imply `ANALYZES`. Use the helper methods to check this hierarchy.
     */
    enum class BuildPhase {
        /**
         * Use when this command does not have a build phase. Can also be used for commands that resets
         * state. Commands may produce effects to the terminal or output files, e.g. writing logs or
         * printing the help message.
         */
        NONE,

        /**
         * Use when this command loads BUILD and bzl files to produce the target graph, or MODULE.bazel
         * and WORKSPACE files for external dependencies.
         */
        LOADS,

        /**
         * Use when this command produces the configured target/aspect/action graphs.
         * 
         * 
         * Implies LOADS.
         */
        ANALYZES,

        /**
         * Use when this command executes actions.
         * 
         * 
         * Implies LOADS, ANALYZES.
         */
        EXECUTES;

        /* True if this command executes actions. */
        fun executes(): Boolean {
            return this == BuildPhase.EXECUTES
        }

        /* True if this command analyzes and creates the configured target and action graphs. */
        fun analyzes(): Boolean {
            return this == BuildPhase.ANALYZES || this == BuildPhase.EXECUTES
        }

        /**
         * Use when this command loads BUILD and bzl files to produce the target graph, or MODULE.bazel
         * and WORKSPACE files for external dependencies.
         */
        fun loads(): Boolean {
            return this == BuildPhase.LOADS || this == BuildPhase.ANALYZES || this == BuildPhase.EXECUTES
        }
    }
}
