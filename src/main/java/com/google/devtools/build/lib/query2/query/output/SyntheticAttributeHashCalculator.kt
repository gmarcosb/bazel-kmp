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
package com.google.devtools.build.lib.query2.query.output

import com.google.common.base.Preconditions
import com.google.common.hash.HashFunction
import com.google.common.hash.HashingOutputStream
import com.google.common.io.BaseEncoding
import com.google.common.io.ByteStreams
import com.google.devtools.build.lib.packages.Attribute

/**
 * Contains the logic for condensing the various properties of rules that contribute to their
 * "affectedness" into a simple hash value. The resulting hash may be compared across queries to
 * tell if a rule has changed in a potentially meaningful way.
 */
internal object SyntheticAttributeHashCalculator {
    private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

    /**
     * Returns a hash of various properties of a rule which might contribute to the rule's
     * "affectedness". This includes, but is not limited to, attribute values and error-state.
     * 
     * @param rule The rule instance to calculate the hash for.
     * @param serializedAttributes Any available attribute which have already been serialized. This is
     * an optimization to avoid re-serializing attributes internally.
     * @param extraDataForAttrHash Extra data to add to the hash.
     */
    fun compute(
        rule: Rule,
        serializedAttributes: MutableMap<Attribute?, Build.Attribute>,
        extraDataForAttrHash: Any?,
        hashFunction: HashFunction,
        includeAttributeSourceAspects: Boolean,
        includeStarlarkRuleEnv: Boolean
    ): String {
        val hashingOutputStream =
            HashingOutputStream(hashFunction, ByteStreams.nullOutputStream())
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(hashingOutputStream)

        val ruleClass: RuleClass = rule.getRuleClassObject()
        if (ruleClass.isStarlark && includeStarlarkRuleEnv) {
            try {
                codedOut.writeByteArrayNoTag(
                    Preconditions.< byte [] > checkNotNull<ByteArray?>(ruleClass.ruleDefinitionEnvironmentDigest, rule)
                )
            } catch (e: IOException) {
                throw IllegalStateException("Unexpected IO failure writing to digest stream", e)
            }
        }

        val rawAttributeMapper: RawAttributeMapper = RawAttributeMapper.of(rule)
        for (attr in rule.getAttributes()) {
            val attrName: String = attr.name

            if (attrName == "generator_location") {
                // generator_location can be ignored for the purpose of telling if a rule has changed.
                continue
            }

            var valueToHash: Any? = rawAttributeMapper.getRawAttributeValue(rule, attr)

            if (valueToHash is ComputedDefault) {
                // ConfiguredDefaults need special handling to detect changes in evaluated values.
                if (!valueToHash.dependencies().isEmpty()) {
                    // TODO(b/29038463): We're skipping computed defaults that depend on other configurable
                    // attributes because there currently isn't a way to evaluate such a computed default;
                    // there isn't *one* value it evaluates to.
                    continue
                }

                try {
                    valueToHash = valueToHash.getDefault(rawAttributeMapper)
                } catch (e: IllegalArgumentException) {
                    // TODO(mschaller): Catching IllegalArgumentException isn't ideal. It's thrown by
                    // AbstractAttributeMapper#get if the attribute's type doesn't match its value, which
                    // would happen if a ComputedDefault function accessed an attribute whose value was
                    // configurable. We check whether the ComputedDefault declared any configurable
                    // attribute dependencies above, but someone could make a mistake and fail to declare
                    // something. There's no mechanism that enforces correct declaration right now.
                    // This allows us to recover from such an error by skipping an attribute, as opposed to
                    // crashing.
                    logger.atWarning().log(
                        "Recovering from failed evaluation of ComputedDefault attribute value: %s", e
                    )
                    continue
                }
            }

            val attrPb: Build.Attribute
            if (valueToHash is SelectorList<*> || !serializedAttributes.containsKey(attr)) {
                // We didn't already serialize the attribute or it's a SelectorList. Latter may
                // have been flattened while we want the full representation, so we start from scratch.
                attrPb =
                    AttributeFormatter.getAttributeProto(
                        attr,
                        valueToHash,  /* explicitlySpecified= */
                        false,  // We care about value, not how it was set.
                        /* encodeBooleanAndTriStateAsIntegerAndString= */
                        false,  /* sourceAspect= */
                        null,
                        includeAttributeSourceAspects,
                        LabelPrinter.legacy()
                    )
            } else {
                attrPb = serializedAttributes.get(attr)
            }

            try {
                attrPb.writeTo(codedOut)
            } catch (e: IOException) {
                throw IllegalStateException("Unexpected IO failure writing to digest stream", e)
            }
        }

        try {
            // Rules can be considered changed when the containing package goes in/out of error.
            codedOut.writeBoolNoTag(rule.getPackageoid().containsErrors())
        } catch (e: IOException) {
            throw IllegalStateException("Unexpected IO failure writing to digest stream", e)
        }

        try {
            // Include a summary of any package-wide data that applies to this target (e.g. custom make
            // variables aka `vardef`).
            codedOut.writeStringNoTag(extraDataForAttrHash as String?)
        } catch (e: IOException) {
            throw IllegalStateException("Unexpected IO failure writing to digest stream", e)
        }

        try {
            // Flush coded out to make sure all bytes make it to the underlying digest stream.
            codedOut.flush()
        } catch (e: IOException) {
            throw IllegalStateException("Unexpected flush failure", e)
        }

        return BaseEncoding.base64().encode(hashingOutputStream.hash().asBytes())
    }
}
