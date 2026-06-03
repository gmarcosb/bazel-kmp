// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/**
 * An [AttributeMap] that supports attribute type queries on both a rule and its aspects and
 * attribute value queries on the rule.
 * 
 * 
 * An attribute type query is anything accessible from [Attribute] (i.e. anything about how
 * the attribute is integrated into the [com.google.devtools.build.lib.packages.RuleClass]).
 * An attribute value query is anything related to the actual value an attribute takes.
 * 
 * 
 * For example, given `deps = [":adep"]`, checking that `deps` exists or that it's
 * type is [com.google.devtools.build.lib.packages.BuildType.LABEL_LIST] are type queries.
 * Checking that its value is explicitly set in the BUILD File or that its value `[":adep"]`
 * are value queries.
 * 
 * 
 * Value queries on aspect attributes trigger [UnsupportedOperationException].
 */
internal class AspectAwareAttributeMapper(
    ruleAttributes: AttributeMap,
    aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?>
) : AttributeMap {
    private val ruleAttributes: AttributeMap
    private val aspectAttributes: com.google.common.collect.ImmutableMap<String?, Attribute?>

    init {
        this.ruleAttributes = ruleAttributes
        this.aspectAttributes = aspectAttributes
    }

    public override fun getLabel(): Label {
        return ruleAttributes.getLabel()
    }

    public override fun <T> get(attributeName: String?, type: Type<T?>): T? {
        if (ruleAttributes.has(attributeName, type)) {
            return ruleAttributes.get(attributeName, type)
        } else {
            val attribute: Attribute? = aspectAttributes.get(attributeName)
            requireNotNull(attribute != null) {
                java.lang.String.format(
                    "no attribute '%s' in either %s or its aspects",
                    attributeName, ruleAttributes.describeRule()
                )
            }
            require(attribute.getType() === type) {
                java.lang.String.format(
                    "attribute %s has type %s, not expected type %s",
                    attributeName, attribute.getType(), type
                )
            }
            if (attribute.isImplicit()) {
                return type.cast(attribute.getDefaultValue( /* rule= */null))
            } else {
                throw java.lang.UnsupportedOperationException(
                    java.lang.String.format(
                        "Attribute '%s' comes from an aspect. "
                                + "Value retrieval for aspect attributes is not supported.",
                        attributeName
                    )
                )
            }
        }
    }

    public override fun isConfigurable(attributeName: String?): Boolean {
        return ruleAttributes.isConfigurable(attributeName)
    }

    public override fun getAttributeNames(): Iterable<String?> {
        return com.google.common.collect.Iterables.concat(ruleAttributes.getAttributeNames(), aspectAttributes.keySet())
    }

    public override fun getAttributeType(attrName: String?): Type<*>? {
        val type: Type<*>? = ruleAttributes.getAttributeType(attrName)
        if (type != null) {
            return type
        } else {
            val attribute: Attribute? = aspectAttributes.get(attrName)
            return if (attribute != null) attribute.getType() else null
        }
    }

    public override fun getAttributeDefinition(attrName: String?): Attribute? {
        val attribute: Attribute? = ruleAttributes.getAttributeDefinition(attrName)
        if (attribute != null) {
            return attribute
        } else {
            return aspectAttributes.get(attrName)
        }
    }

    public override fun isAttributeValueExplicitlySpecified(attributeName: String?): Boolean {
        return ruleAttributes.isAttributeValueExplicitlySpecified(attributeName)
    }

    public override fun visitAllLabels(consumer: java.util.function.BiConsumer<Attribute?, Label?>?) {
        throw java.lang.UnsupportedOperationException("rule + aspects label visition is not supported")
    }

    public override fun visitLabels(attributeName: String?, consumer: java.util.function.Consumer<Label?>?) {
        throw java.lang.UnsupportedOperationException("rule + aspects label visition is not supported")
    }

    public override fun visitLabels(
        filter: DependencyFilter?,
        consumer: java.util.function.BiConsumer<Attribute?, Label?>?
    ) {
        throw java.lang.UnsupportedOperationException("rule + aspects label visition is not supported")
    }

    public override fun getPackageArgs(): PackageArgs {
        return ruleAttributes.getPackageArgs()
    }

    public override fun has(attrName: String?): Boolean {
        if (ruleAttributes.has(attrName)) {
            return true
        } else {
            return aspectAttributes.containsKey(attrName)
        }
    }

    public override fun <T> has(attrName: String?, type: Type<T?>?): Boolean {
        if (ruleAttributes.has(attrName, type)) {
            return true
        } else {
            return aspectAttributes.containsKey(attrName)
                    && aspectAttributes.get(attrName).getType() === type
        }
    }
}
