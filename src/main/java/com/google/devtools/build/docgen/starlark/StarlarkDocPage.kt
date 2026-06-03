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
package com.google.devtools.build.docgen.starlark

import com.google.devtools.build.buildjar.javac.plugins.dependency.DependencyModule.Builder.build
import com.google.devtools.build.buildjar.javac.plugins.processing.AnnotationProcessingModule.Builder.build
import com.google.devtools.build.buildjar.javac.statistics.BlazeJavacStatistics.Builder.build
import com.google.devtools.build.docgen.starlark.AnnotStarlarkOrdinaryMethodDoc
import com.google.devtools.build.docgen.starlark.MemberDoc
import com.google.devtools.build.docgen.starlark.StarlarkDoc
import com.google.devtools.build.docgen.starlark.StarlarkDocExpander
import com.google.testing.junit.runner.junit4.JUnit4Bazel.Builder.build
import com.google.testing.junit.runner.junit4.JUnit4TestModelBuilder.get
import net.starlark.java.syntax.Identifier.getName
import java.util.HashMap

/**
 * A typical Starlark documentation page, containing a bunch of field/method documentation entries.
 */
abstract class StarlarkDocPage protected constructor(expander: StarlarkDocExpander?) : StarlarkDoc(expander) {
    // Contains all members; must be sorted for output - we cannot sort before output because
    // overloading can change a member doc's sort key.
    protected val membersByShortName: com.google.common.collect.HashMultimap<String?, MemberDoc?> =
        com.google.common.collect.HashMultimap.create<String?, MemberDoc?>()

    // Contains overloaded members; used only for uniqueness checks in overloadMember().
    private val overloadsBySignature: HashMap<String?, MemberDoc?> = HashMap<String?, MemberDoc?>()
    private var constructor: MemberDoc? = null

    abstract val title: String?

    fun setConstructor(method: MemberDoc) {
        com.google.common.base.Preconditions.checkArgument(
            method.isConstructor(),
            "Expected a constructor, got %s",
            method
        )
        com.google.common.base.Preconditions.checkState(
            constructor == null,
            "Constructor method doc already set for %s:\n  existing: %s\n  attempted: %s",
            getName(),
            constructor,
            method
        )
        constructor = method
    }

    fun addMember(member: MemberDoc) {
        if (!member.documented()) {
            return
        }

        val shortName: String? = member.getShortName()
        val overloads: MutableSet<MemberDoc?> = membersByShortName.get(shortName)
        if (!overloads.isEmpty()) {
            // Overload information only needs to be updated if we're discovering the first overload
            // (= the second method of the same name).
            if (overloads.size == 1) {
                overloadMember(com.google.common.collect.Iterables.getOnlyElement<MemberDoc?>(overloads))
            }
            overloadMember(member)
        }
        membersByShortName.put(shortName, member)
    }

    private fun overloadMember(member: MemberDoc?) {
        if (member is AnnotStarlarkOrdinaryMethodDoc) {
            member.setOverloaded(true)
            val prevOverloadWithSameSignature: MemberDoc? = overloadsBySignature.put(member.getName(), member)
            check(prevOverloadWithSameSignature == null) {
                String.format(
                    "Starlark type '%s' has multiple overloads with signature %s: %s, %s",
                    getName(), member.getName(), member, prevOverloadWithSameSignature
                )
            }
        } else {
            throw java.lang.IllegalArgumentException(
                "Only non-constructor Java-defined methods can be overloaded; got " + member
            )
        }
    }

    val members: com.google.common.collect.ImmutableList<MemberDoc?>
        /**
         * Returns the list of members of this doc page,; first the constructor method (if one is
         * defined), and then the remaining methods in case-insensitive name order.
         */
        get() {
            val members: com.google.common.collect.ImmutableList.Builder<MemberDoc?> =
                com.google.common.collect.ImmutableList.builder<MemberDoc?>()
            if (constructor != null) {
                members.add(constructor)
            }
            // membersByShortName is a hash map,
            return members
                .addAll(
                    com.google.common.collect.ImmutableList.sortedCopyOf<MemberDoc?>(
                        java.util.Comparator.comparing<MemberDoc?, String?>(java.util.function.Function { m: MemberDoc? ->
                            m.getName().lowercase()
                        }),
                        membersByShortName.values()
                    )
                )
                .build()
        }

    fun getConstructor(): MemberDoc? {
        return constructor
    }

    /** Returns the path to the source file backing this doc page.  */ // This method may seem unused, but it's actually used in the template file (starlark-library.vm).
    abstract val sourceFile: String?
        /** Returns the path to the source file backing this doc page.  */
        get
}
