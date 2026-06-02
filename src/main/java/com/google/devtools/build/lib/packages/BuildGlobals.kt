// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** A set of miscellaneous APIs that are available to any BUILD file.  */
@com.google.devtools.build.docgen.annot.GlobalMethods(environment = com.google.devtools.build.docgen.annot.GlobalMethods.Environment.BUILD)
class BuildGlobals private constructor() {
    @net.starlark.java.annot.StarlarkMethod(
        name = "environment_group",
        doc = ("Defines a set of related environments that can be tagged onto rules to prevent"
                + "incompatible rules from depending on each other."),
        parameters = [net.starlark.java.annot.Param(
            name = "name",
            positional = false,
            named = true,
            doc = "The name of the rule."
        ), net.starlark.java.annot.Param(
            name = "environments",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = Label::class
            )],
            positional = false,
            named = true,
            doc = "A list of Labels for the environments to be grouped, from the same package."
        ), net.starlark.java.annot.Param(
            name = "defaults",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = Label::class
            )],
            positional = false,
            named = true,
            doc = "A list of Labels."
        )],
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun environmentGroup(
        name: String?,
        environmentsList: net.starlark.java.eval.Sequence<*>?,  // <Label>
        defaultsList: net.starlark.java.eval.Sequence<*>?,  // <Label>
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.NoneType? {
        val pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder =
            com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                thread,
                "environment_group()"
            )
        val environments: MutableList<Label?> =
            BuildType.LABEL_LIST.convert(
                environmentsList, "'environment_group argument'", pkgBuilder.getLabelConverter()
            )
        val defaults: MutableList<Label?> =
            BuildType.LABEL_LIST.convert(
                defaultsList, "'environment_group argument'", pkgBuilder.getLabelConverter()
            )

        if (environments.isEmpty()) {
            throw net.starlark.java.eval.Starlark.errorf(
                "environment group %s must contain at least one environment",
                name
            )
        }
        try {
            val loc: net.starlark.java.syntax.Location? = thread.getCallerLocation()
            pkgBuilder.addEnvironmentGroup(
                name, environments, defaults, pkgBuilder.getLocalEventHandler(), loc
            )
            return net.starlark.java.eval.Starlark.NONE
        } catch (e: LabelSyntaxException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "environment group has invalid name: %s: %s",
                name,
                e.getMessage()
            )
        } catch (e: NameConflictException) {
            throw net.starlark.java.eval.Starlark.errorf("%s", e.getMessage())
        }
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "licenses",
        doc = "Declare the license(s) for the code in the current package.",
        parameters = [net.starlark.java.annot.Param(
            name = "license_strings",
            allowedTypes = [net.starlark.java.annot.ParamType(
                type = net.starlark.java.eval.Sequence::class,
                generic1 = String::class
            )],
            doc = "A list of strings, the names of the licenses used."
        )],
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun licenses(
        licensesList: net.starlark.java.eval.Sequence<*>?,  // list of license strings
        thread: net.starlark.java.eval.StarlarkThread
    ): net.starlark.java.eval.NoneType? {
        val pkgBuilder: com.google.devtools.build.lib.packages.Package.AbstractBuilder =
            com.google.devtools.build.lib.packages.Package.AbstractBuilder.Companion.fromOrFailAllowBuildOnly(
                thread,
                "licenses()"
            )
        try {
            val license: License? = BuildType.LICENSE.convert(licensesList, "'licenses' operand")
            pkgBuilder.mergePackageArgsFrom(PackageArgs.Companion.builder().setLicense(license))
        } catch (e: ConversionException) {
            pkgBuilder
                .getLocalEventHandler()
                .handle(
                    com.google.devtools.build.lib.packages.Package.Companion.error(
                        thread.getCallerLocation(), e.getMessage(), Code.LICENSE_PARSE_FAILURE
                    )
                )
            pkgBuilder.setContainsErrors()
        }
        return net.starlark.java.eval.Starlark.NONE
    }

    companion object {
        val INSTANCE: BuildGlobals = BuildGlobals()
    }
}
