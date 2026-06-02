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

import com.google.devtools.build.lib.cmdline.Label

/**
 * An [AttributeMap] that delegates all behavior to another [AttributeMap]. Useful
 * for custom mappers that just want to override specific scenarios.
 */
open class DelegatingAttributeMapper(delegate: com.google.devtools.build.lib.packages.AttributeMap?) :
    com.google.devtools.build.lib.packages.AttributeMap {
    private val delegate: com.google.devtools.build.lib.packages.AttributeMap

    init {
        this.delegate =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.AttributeMap>(
                delegate
            )
    }

    override fun getLabel(): Label? {
        return delegate.getLabel()
    }

    override fun <T> get(attributeName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): T? {
        return delegate.get<T?>(attributeName, type)
    }

    override fun isConfigurable(attributeName: String?): Boolean {
        return delegate.isConfigurable(attributeName)
    }

    override fun getAttributeNames(): Iterable<String?>? {
        return delegate.getAttributeNames()
    }

    override fun getAttributeType(attrName: String?): com.google.devtools.build.lib.packages.Type<*>? {
        return delegate.getAttributeType(attrName)
    }

    override fun getAttributeDefinition(attrName: String?): com.google.devtools.build.lib.packages.Attribute? {
        return delegate.getAttributeDefinition(attrName)
    }

    override fun isAttributeValueExplicitlySpecified(attributeName: String?): Boolean {
        return delegate.isAttributeValueExplicitlySpecified(attributeName)
    }

    override fun visitAllLabels(consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>?) {
        delegate.visitAllLabels(consumer)
    }

    override fun visitLabels(attributeName: String?, consumer: java.util.function.Consumer<Label?>?) {
        delegate.visitLabels(attributeName, consumer)
    }

    override fun visitLabels(
        filter: DependencyFilter?,
        consumer: java.util.function.BiConsumer<com.google.devtools.build.lib.packages.Attribute?, Label?>?
    ) {
        delegate.visitLabels(filter, consumer)
    }

    override fun getPackageArgs(): PackageArgs? {
        return delegate.getPackageArgs()
    }

    override fun has(attrName: String?): Boolean {
        return delegate.has(attrName)
    }

    override fun <T> has(attrName: String?, type: com.google.devtools.build.lib.packages.Type<T?>?): Boolean {
        return delegate.has<T?>(attrName, type)
    }
}
