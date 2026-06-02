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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * The interface for accessing a [Rule]'s attributes.
 * 
 * 
 * Since what an attribute lookup should return can be ambiguous (e.g. for configurable
 * attributes, should we return a configuration-resolved value or the original, unresolved
 * selection expression?), different implementations can apply different policies for how to
 * fulfill these methods. Calling code can then use the appropriate implementation for whatever
 * its particular needs are.
 */
interface AttributeMap {
    /** Describe the underlying rule, for use in messages.  */
    fun describeRule(): String {
        return getLabel().toString()
    }

    /**
     * Returns the label of the rule.
     */
    fun getLabel(): Label?

    /**
     * Returns true if an attribute with the given name exists.
     */
    fun has(attrName: String?): Boolean

    /**
     * Returns true if an attribute with the given name exists with the given type.
     * 
     * 
     * Don't use this version unless you really care about the type.
     */
    fun <T> has(attrName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): Boolean

    /**
     * Returns the value of the named rule attribute, which must be of the given type. This may
     * be null (for example, for an attribute with no default value that isn't explicitly set in
     * the rule - see [Type.getDefaultValue]).
     * 
     * 
     * If the rule doesn't have this attribute with the specified type, throws an
     * [IllegalArgumentException].
     */
    fun <T> get(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): T?

    /**
     * Returns the value of the named rule attribute if it exists, otherwise the given default value.
     * This may be null (for example, for an attribute with no default value that isn't explicitly set
     * in the rule - see [Type.getDefaultValue]).
     */
    fun <T> getOrDefault(
        attributeName: String?,
        type: com.google.devtools.build.lib.packages.Type<T?>?,
        defaultValue: T?
    ): T? {
        if (has(attributeName)) {
            return get<T?>(attributeName, type)
        }
        return defaultValue
    }

    /**
     * Returns true if the given attribute is configurable for this rule instance or if any attributes
     * it requires (for computed defaults) are configurable. Returns false if the attribute doesn't
     * exist.
     */
    fun isConfigurable(attributeName: String?): Boolean

    /** Returns the names of all attributes covered by this map.  */
    fun getAttributeNames(): Iterable<String?>?

    /**
     * Returns the type of the given attribute, if it exists. Otherwise returns null.
     */
    fun getAttributeType(attrName: String?): com.google.devtools.build.lib.packages.Type<*>?

    /**
     * Returns the attribute definition whose name is `attrName`, or null
     * if not found.
     */
    fun getAttributeDefinition(attrName: String?): com.google.devtools.build.lib.packages.Attribute?

    /**
     * Returns true iff the specified attribute is explicitly set in the target's definition (as
     * opposed to being omitted and taking on its default value from the rule definition).
     * 
     * 
     * Note that this returns true in the case where the attribute is explicitly set to the same
     * value as its default. Therefore, this method breaks encapsulation in the sense that it
     * describes *how* a target is defined rather than just *what* its attribute values are.
     * 
     * 
     * CAUTION: It is a good idea to avoid relying on this method if possible. It's confusing to
     * users that setting an attribute to (for example) an empty list is different from not setting it
     * at all. It also breaks some use cases, such as programmatically copying a target definition via
     * `native.existing_rules`. Specifically, the Starlark code doing the copying will observe
     * the attribute on the existing target whether or not it was set explicitly, and then set that
     * value explicitly on the new target. This can cause the two targets to behave differently, and
     * can be a difficult bug to track down. (See #7071, b/122596733).
     */
    fun isAttributeValueExplicitlySpecified(attributeName: String?): Boolean

    /**
     * Invokes a consumer for labels of *every* attribute that contains labels in its value
     * (either by being a label or being a collection that includes labels).
     * 
     * 
     * If it is not necessary to visit labels of every attribute, prefer [ ][.visitLabels] or [.visitLabels] for
     * better performance.
     */
    fun visitAllLabels(consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>?)

    /** Same as [.visitAllLabels] but for a single attribute.  */
    fun visitLabels(attributeName: String?, consumer: java.util.function.Consumer<Label?>?)

    /** Same as [.visitAllLabels] but for attributes matching a [DependencyFilter].  */
    fun visitLabels(
        filter: DependencyFilter?,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>?
    )

    fun getPackageArgs(): PackageArgs?
}
