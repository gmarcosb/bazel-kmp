// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.objc

import com.google.common.base.Joiner
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableMap
import com.google.devtools.build.lib.testutil.Scratch
import java.io.IOException

/**
 * Provides utilities to help test a certain rule type without requiring the calling code to know
 * exactly what kind of rule is being tested. Only one instance is needed per rule type (e.g. one
 * instance for `objc_library`).
 */
abstract class RuleType internal constructor(
    /**
     * The name of this type as it appears in `BUILD` files, such as `objc_library`.
     */
    val ruleTypeName: String
) {
    /**
     * Returns names and values, and otherwise prepares, extra attributes required for this rule type
     * to be without error. For instance, if this rule type requires 'srcs' and 'infoplist'
     * attributes, this method may be implemented as follows:
     * <pre>
     * `List<String> attributes = new ArrayList<>(); if (!alreadyAdded.contains("srcs")) {   scratch.file("/workspace_root/" + packageDir + "/a.m");   attributes.add("srcs = ['a.m']"); } if (!alreadyAdded.contains(INFOPLIST_ATTR)) {   scratch.file("/workspace_root/" + packageDir + "Info.plist");   attributes.add("infoplist = ['Info.plist']"); } return attributes; </pre> `
     * 
     * @throws IOException for whatever reason the implementer feels like, but mostly just when
     * a scratch file couldn't be created
    </pre> */
    @Throws(IOException::class)
    abstract fun requiredAttributes(
        scratch: Scratch?, packageDir: String?, alreadyAdded: MutableSet<String?>?
    ): Iterable<String?>?

    private fun map(vararg attrs: String?): ImmutableMap<String?, String?> {
        val map = ImmutableMap.Builder<String?, String?>()
        Preconditions.checkArgument(
            (attrs.size and 1) == 0,
            "attrs must have an even number of elements"
        )
        var i = 0
        while (i < attrs.size) {
            map.put(attrs[i], attrs[i + 1])
            i += 2
        }
        return map.build()
    }

    /**
     * Generates the String necessary to define a target of this rule type.
     * 
     * @param packageDir the package in which to create the target
     * @param name the name of the target
     * @param checkSpecificAttrs alternating name/values of attributes to add to the rule that are
     * required for the check being performed to be defined a certain way. Pass
     * [.OMIT_REQUIRED_ATTR] for a value to prevent an attribute from being automatically
     * defined.
     */
    @Throws(IOException::class)
    fun target(
        scratch: Scratch?, packageDir: String?, name: String?, vararg checkSpecificAttrs: String?
    ): String {
        val checkSpecific = map(*checkSpecificAttrs)
        val target = StringBuilder(ruleTypeName)
            .append("(name = '")
            .append(name)
            .append("',")
        for (entry in checkSpecific.entries) {
            if (entry.value == OMIT_REQUIRED_ATTR) {
                continue
            }
            target.append(entry.key)
                .append("=")
                .append(entry.value)
                .append(",")
        }
        Joiner.on(",").appendTo(
            target,
            requiredAttributes(scratch, packageDir, checkSpecific.keys)
        )
        target.append(')')
        return target.toString()
    }

    /**
     * Creates a target at //x:x which is the only target in the BUILD file. Returns the string that
     * is written to the scratch file as it is often useful for debugging purposes.
     */
    @Throws(IOException::class)
    fun scratchTarget(scratch: Scratch, vararg checkSpecificAttrs: String?): String {
        return scratchTarget("x", "x", scratch, *checkSpecificAttrs)
    }

    /**
     * Creates a target at a given package which is the only target in the BUILD file. Returns the
     * string that is written to the scratch file as it is often useful for debugging purposes.
     * 
     * @param packageDir the package of the target, for example "foo" in //foo:bar
     * @param targetName the name of the target, for example "bar" in //foo:bar
     * @param scratch the scratch object to use to create the build file
     * @param checkSpecificAttrs alternating name/values of attributes to add to the rule that are
     * required for the check being performed to be defined a certain way. Pass
     * [.OMIT_REQUIRED_ATTR] for a value to prevent an attribute from being automatically
     * defined.
     */
    @Throws(IOException::class)
    fun scratchTarget(
        packageDir: String?, targetName: String?,
        scratch: Scratch, vararg checkSpecificAttrs: String?
    ): String {
        val target = target(scratch, packageDir, targetName, *checkSpecificAttrs)
        scratch.file(packageDir + "/BUILD", starlarkLoadPrerequisites() + "\n" + target)
        return target
    }

    /**
     * Returns a string (of one or more lines) required by BUILD files which reference targets of this
     * rule type.
     * 
     * 
     * Subclasses of [RuleType] should override this method if using the rule requires
     * Starlark files to be loaded.
     */
    fun starlarkLoadPrerequisites(): String {
        return """
load("@rules_cc//cc:objc_import.bzl", "objc_import")
load("@rules_cc//cc:objc_library.bzl", "objc_library")

""".trimIndent()
    }

    companion object {
        /**
         * What to pass as the value of some attribute to indicate an attribute should not be added to the
         * rule. This can either be to test an error condition, or to use an alternative attribute to
         * supply the value.
         */
        const val OMIT_REQUIRED_ATTR: String = "<OMIT_REQUIRED_ATTR>"
    }
}
