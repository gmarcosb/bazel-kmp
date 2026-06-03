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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.docgen.DocCheckerUtils

/** Utility functions for validating correctness of Bazel documentation.  */
internal object DocumentationTestUtil {
    private val CODE_FLAG_PATTERN: java.util.regex.Pattern = java.util.regex.Pattern.compile(
        "<code class\\s*=\\s*[\"']flag[\"']\\s*>--([a-z_\\[\\]]*)<\\/code>",
        java.util.regex.Pattern.CASE_INSENSITIVE
    )

    /**
     * Validates that a user manual `documentationSource` contains only the flags actually
     * provided by a given set of modules.
     */
    @Throws(java.lang.Exception::class)
    fun validateUserManual(
        blazeModuleClasses: Iterable<java.lang.Class<out BlazeModule?>?>?,
        blazeServices: Iterable<BlazeService?>?,
        ruleClassProvider: ConfiguredRuleClassProvider?,
        documentationSource: String?,
        extraValidOptions: MutableSet<String?>?
    ) {
        // if there is a class missing, one can find it using
        //   find . -name "*.java" -exec grep -Hn "@Option(name = " {} \; | grep "xxx"
        // where 'xxx' is a flag name.
        val optionsSuppliers: Iterable<OptionsSupplier?> =
            com.google.common.collect.Iterables.concat(
                BlazeRuntime.createBlazeModules(blazeModuleClasses),
                blazeServices
            )

        val validOptions: MutableSet<String?> = HashSet<String?>()

        val startupOptionsClasses: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            BlazeCommandUtils.getStartupOptions(optionsSuppliers)
        // collect all startup options
        for (optionsClass in startupOptionsClasses) {
            for (def in OptionDefinition.getOptionDefinitions(optionsClass)) {
                validOptions.add(def.getOptionName())
            }
        }
        validOptions.addAll(extraValidOptions!!)

        val startupOptions: OptionsParser = OptionsParser.builder().optionsClasses(startupOptionsClasses).build()
        startupOptions.parse()

        // collect all command options
        val serverBuilder: ServerBuilder = ServerBuilder()
        DummyBuiltinCommandModule().serverInit(startupOptions, serverBuilder)
        for (optionsSupplier in optionsSuppliers) {
            if (optionsSupplier is BlazeModule) {
                optionsSupplier.serverInit(startupOptions, serverBuilder)
            }
        }
        val blazeCommands: MutableList<BlazeCommand> = serverBuilder.getCommands()

        for (command in blazeCommands) {
            for (optionClass in BlazeCommandUtils.getOptions(command.getClass(), optionsSuppliers, ruleClassProvider)) {
                for (def in OptionDefinition.getOptionDefinitions(optionClass)) {
                    validOptions.add(def.getOptionName())
                }
            }
        }

        // check validity of option flags in manual
        val anchorMatcher: java.util.regex.Matcher = CODE_FLAG_PATTERN.matcher(documentationSource)
        var flag: String
        var found: Boolean

        while (anchorMatcher.find()) {
            flag = anchorMatcher.group(1)
            found = validOptions.contains(flag)
            if (!found && flag.startsWith("no")) {
                found = validOptions.contains(flag.substring(2))
            }
            if (!found && flag.startsWith("[no]")) {
                found = validOptions.contains(flag.substring(4))
            }

            Truth.assertWithMessage("flag '%s' is not a bazel option (anymore)", flag).that(found).isTrue()
        }

        val unclosedTag: String = DocCheckerUtils.getFirstUnclosedTagAndPrintHelp(documentationSource)
        Truth.assertWithMessage("Unclosed tag found: %s", unclosedTag).that(unclosedTag).isNull()
    }

    private class DummyBuiltinCommandModule : BuiltinCommandModule(RunCommand(TestPolicy.EMPTY_POLICY))
}
