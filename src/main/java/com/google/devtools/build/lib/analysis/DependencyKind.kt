// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.auto.value.AutoValue
import com.google.devtools.build.lib.packages.AspectClass
import com.google.devtools.build.lib.packages.DeclaredExecGroup

/**
 * A kind of dependency, used extensively in [DependencyResolver].
 * 
 * 
 * Usually an attribute, but other special-cased kinds exist, for example, for visibility or
 * toolchains.
 */
interface DependencyKind {
    /**
     * The attribute through which a dependency arises.
     * 
     * 
     * Returns `null` for visibility, the dependency pointing from an output file to its
     * generating rule and toolchain dependencies.
     */
    fun getAttribute(): com.google.devtools.build.lib.packages.Attribute?

    /**
     * The aspect owning the attribute through which the dependency arises.
     * 
     * 
     * Should only be called for dependency kinds representing an attribute.
     */
    fun getOwningAspect(): AspectClass?

    /** A dependency caused by something that's not an attribute. Special cases enumerated below.  */
    class NonAttributeDependencyKind private constructor(private val name: String?) : DependencyKind {
        override fun getAttribute(): com.google.devtools.build.lib.packages.Attribute? {
            return null
        }

        override fun getOwningAspect(): AspectClass? {
            throw java.lang.IllegalStateException()
        }

        override fun toString(): String {
            return String.format("%s(%s)", javaClass.getSimpleName(), this.name)
        }
    }

    /**
     * Represents a dependency on toolchain context whether it's the entity (target or aspect) owned
     * toolchain or the base target toolchain in case of aspects.
     */
    interface ToolchainDependencyKind : DependencyKind {
        override fun getAttribute(): com.google.devtools.build.lib.packages.Attribute? {
            return null
        }

        override fun getOwningAspect(): AspectClass? {
            throw java.lang.IllegalStateException()
        }

        /** The name of the execution group represented by this dependency kind.  */
        fun getExecGroupName(): String?

        /** Returns true if this toolchain dependency is for the default exec group.  */
        fun isDefaultExecGroup(): Boolean
    }

    /**
     * A dependency of an entity (target or aspect) on a toolchain context, identified by the
     * execution group name.
     */
    @AutoValue
    class ToolchainDependencyKindImpl : ToolchainDependencyKind

    /**
     * A dependency for the aspect on its target's toolchain context, used for aspects propagating to
     * toolchains, identified by the execution group name and the toolchain type.
     */
    @AutoValue
    class BaseTargetToolchainDependencyKind : ToolchainDependencyKind {
        /** The toolchain type of the toolchain dependency.  */
        abstract fun getToolchainType(): com.google.devtools.build.lib.cmdline.Label?
    }

    /** A dependency through an attribute, either that of an aspect or the rule itself.  */
    @AutoValue
    class AttributeDependencyKind : DependencyKind {
        abstract override fun getAttribute(): com.google.devtools.build.lib.packages.Attribute?

        abstract override fun getOwningAspect(): AspectClass?

        companion object {
            fun forRule(attribute: com.google.devtools.build.lib.packages.Attribute?): AttributeDependencyKind {
                return AutoValue_DependencyKind_AttributeDependencyKind(attribute, null)
            }

            fun forAspect(
                attribute: com.google.devtools.build.lib.packages.Attribute?,
                owningAspect: AspectClass?
            ): AttributeDependencyKind {
                return AutoValue_DependencyKind_AttributeDependencyKind(
                    attribute, com.google.common.base.Preconditions.checkNotNull<T?>(owningAspect)
                )
            }
        }
    }

    companion object {
        /** Returns a [DependencyKind] for the given execution group.  */
        @kotlin.jvm.JvmStatic
        fun forExecGroup(execGroupName: String?): DependencyKind {
            if (DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME == execGroupName) {
                return com.google.devtools.build.lib.analysis.DependencyKind.Companion.defaultExecGroupToolchain()
            }
            return AutoValue_DependencyKind_ToolchainDependencyKindImpl(execGroupName, false)
        }

        /** Returns a [DependencyKind] for the default execution group.  */
        fun defaultExecGroupToolchain(): DependencyKind {
            return AutoValue_DependencyKind_ToolchainDependencyKindImpl(
                DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME, true
            )
        }

        /** Returns a [DependencyKind] for the given execution group.  */
        fun forBaseTargetExecGroup(
            execGroupName: String,
            toolchainType: com.google.devtools.build.lib.cmdline.Label?
        ): DependencyKind {
            return AutoValue_DependencyKind_BaseTargetToolchainDependencyKind(
                execGroupName,
                execGroupName == DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME,
                toolchainType
            )
        }

        /** Predicate to check if a dependency represents an aspect's base target toolchain.  */
        @kotlin.jvm.JvmStatic
        fun isBaseTargetToolchain(dependencyKind: DependencyKind?): Boolean {
            return dependencyKind is BaseTargetToolchainDependencyKind
        }

        /** Predicate to check if a dependency represents a toolchain.  */
        @kotlin.jvm.JvmStatic
        fun isToolchain(dependencyKind: DependencyKind?): Boolean {
            return dependencyKind is ToolchainDependencyKind
        }

        /** Predicate to check if a dependency represents an attribute dependency.  */
        @kotlin.jvm.JvmStatic
        fun isAttribute(dependencyKind: DependencyKind?): Boolean {
            return dependencyKind is AttributeDependencyKind
        }

        /** A dependency for visibility.  */
        @kotlin.jvm.JvmField
        val VISIBILITY_DEPENDENCY: DependencyKind = NonAttributeDependencyKind("VISIBILITY")

        /** A dependency for transitive visibility.  */
        @kotlin.jvm.JvmField
        val TRANSITIVE_VISIBILITY_DEPENDENCY: DependencyKind = NonAttributeDependencyKind("TRANSITIVE_VISIBILITY")

        /** The dependency on the rule that creates a given output file.  */
        @kotlin.jvm.JvmField
        val OUTPUT_FILE_RULE_DEPENDENCY: DependencyKind = NonAttributeDependencyKind("OUTPUT_FILE")
    }
}
