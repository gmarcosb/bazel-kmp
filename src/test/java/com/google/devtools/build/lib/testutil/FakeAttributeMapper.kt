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
package com.google.devtools.build.lib.testutil

import com.google.devtools.build.lib.cmdline.Label

/** Faked implementation of [AttributeMap] for use in testing.  */
class FakeAttributeMapper : AttributeMap {
    val label: Label
        get() = Label.parseCanonicalUnchecked("//fake:rule")

    public override fun has(attrName: String?): Boolean {
        return false
    }

    public override fun <T> has(attrName: String?, type: Type<T?>?): Boolean {
        return false
    }

    public override fun <T> get(attributeName: String, type: Type<T?>?): T? {
        // Not specified in attributes or defaults
        Truth.assertWithMessage("Attribute %s not in attributes!", attributeName).fail()
        return null
    }

    public override fun isConfigurable(attributeName: String?): Boolean {
        return false
    }

    val attributeNames: Iterable<String?>
        get() = com.google.common.collect.ImmutableSet.of<String?>()

    public override fun getAttributeType(attrName: String?): Type<*>? {
        return null
    }

    public override fun getAttributeDefinition(attrName: String?): Attribute? {
        return null
    }

    public override fun isAttributeValueExplicitlySpecified(attributeName: String?): Boolean {
        return false
    }

    public override fun visitAllLabels(consumer: java.util.function.BiConsumer<Attribute?, Label?>?) {}

    public override fun visitLabels(attributeName: String?, consumer: java.util.function.Consumer<Label?>?) {}

    public override fun visitLabels(
        filter: DependencyFilter?,
        consumer: java.util.function.BiConsumer<Attribute?, Label?>?
    ) {
    }

    val packageArgs: PackageArgs
        get() = PackageArgs.DEFAULT

    companion object {
        fun empty(): FakeAttributeMapper {
            return FakeAttributeMapper()
        }
    }
}
