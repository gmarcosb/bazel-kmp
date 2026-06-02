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
package com.google.devtools.build.lib.runtime.commands.info

import com.google.common.base.Preconditions
import com.google.common.base.Predicate
import com.google.common.base.Predicates
import com.google.common.base.Supplier
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue
import net.starlark.java.eval.StarlarkInt
import kotlin.Any
import kotlin.Boolean
import kotlin.ByteArray
import kotlin.Comparator
import kotlin.Deprecated
import kotlin.IllegalStateException
import kotlin.String
import kotlin.toString

/**
 * Info item for the build language. It is deprecated, it still works, when explicitly requested,
 * but are not shown by default. It prints multi-line messages and thus don't play well with grep.
 * We don't print them unless explicitly requested.
 */
@Deprecated("")
class BuildLanguageInfoItem : InfoItem("build-language", "A protobuffer with the build language structure", true) {
    public override fun needsSyncPackageLoading(): Boolean {
        // Requires CommandEnvironment.syncPackageLoading to be called in order to initialize the
        // skyframe executor.
        return true
    }

    @Throws(AbruptExitException::class)
    public override fun get(
        configurationSupplier: Supplier<BuildConfigurationValue?>?,
        env: CommandEnvironment?
    ): ByteArray {
        Preconditions.checkNotNull<Any?>(env)
        val builtins: StarlarkBuiltinsValue? = loadStarlarkBuiltins(env)
        return print(
            getBuildLanguageDefinition(
                RuleClassUtils.getBuiltinRuleClasses(
                    builtins,
                    env.getRuntime().getRuleClassProvider(),  /* includeMacroWrappedRules= */
                    true
                )
            )
        )
    }

    @Throws(AbruptExitException::class)
    private fun loadStarlarkBuiltins(env: CommandEnvironment): StarlarkBuiltinsValue? {
        val result: EvaluationResult<SkyValue?> =
            env.getSkyframeExecutor()
                .evaluateSkyKeys(
                    env.getReporter(),
                    ImmutableList.of<E?>(StarlarkBuiltinsValue.key()),  /* keepGoing= */
                    false
                )
        if (result.hasError()) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetails.FailureDetail.newBuilder()
                        .setMessage("Failed to load Starlark builtins")
                        .setInfoCommand(FailureDetails.InfoCommand.getDefaultInstance())
                        .build()
                )
            )
        }
        return result.get(StarlarkBuiltinsValue.key()) as StarlarkBuiltinsValue?
    }

    companion object {
        /**
         * Returns a byte array containing a proto-buffer describing the build language.
         * 
         * @param ruleClasses a sorted list of rule classes
         */
        private fun getBuildLanguageDefinition(ruleClasses: ImmutableList<RuleClass>): ByteArray {
            val resultPb: BuildLanguage.Builder = BuildLanguage.newBuilder()
            for (ruleClass in ruleClasses) {
                if (isAbstractRule(ruleClass)) {
                    continue
                }

                val rulePb: RuleDefinition.Builder = RuleDefinition.newBuilder()
                rulePb.setName(ruleClass.getName())

                val sortedAttributeDefinitions: ImmutableList<Attribute> =
                    ImmutableList.sortedCopyOf<E>(
                        Comparator.comparing<Any?, Any?>(Attribute::getName),
                        ruleClass.getAttributeProvider().getAttributes()
                    )
                for (attr in sortedAttributeDefinitions) {
                    val t: Type<*> = attr.getType()
                    val attrPb: AttributeDefinition.Builder = AttributeDefinition.newBuilder()
                    attrPb.setName(attr.name)
                    attrPb.setType(ProtoUtils.getDiscriminatorFromType(t))
                    attrPb.setMandatory(attr.isMandatory())
                    attrPb.setAllowEmpty(!attr.isNonEmpty())
                    attrPb.setAllowSingleFile(attr.isSingleArtifact())
                    attrPb.setConfigurable(attr.isConfigurable())

                    // Encode default value, if simple.
                    val v: Any? = attr.defaultValueUnchecked
                    if (!(v == null || v is Attribute.ComputedDefault
                                || v is StarlarkComputedDefaultTemplate
                                || v is Attribute.LateBoundDefault
                                || v === t.getDefaultValue())
                    ) {
                        attrPb.setDefault(convertAttrValue(t, v))
                    }
                    attrPb.setExecutable(attr.isExecutable())
                    if (BuildType.isLabelType(t)) {
                        attrPb.setAllowedRuleClasses(getAllowedRuleClasses(ruleClasses, attr))
                        attrPb.setNodep(t.getLabelClass() === Type.LabelClass.NONDEP_REFERENCE)
                    }
                    rulePb.addAttribute(attrPb)
                }

                resultPb.addRule(rulePb)
            }

            return resultPb.build().toByteArray()
        }

        // convertAttrValue converts attribute value v of type to t an AttributeValue message.
        private fun convertAttrValue(t: Type<*>, v: Any): AttributeValue {
            val b: AttributeValue.Builder = AttributeValue.newBuilder()
            if (v is MutableMap<*, *>) {
                val dictType: Type.DictType<*, *> = t as Type.DictType<*, *>
                for (entry in v.entrySet()) {
                    b.addDictBuilder()
                        .setKey(entry.getKey().toString())
                        .setValue(convertAttrValue(dictType.valueType, entry.getValue()))
                }
            } else if (v is MutableList<*>) {
                for (elem in v) {
                    b.addList(Companion.convertAttrValue(t.getListElementType(), elem!!))
                }
            } else if (t === BuildType.LICENSE) {
                // TODO(adonovan): need dual function of parseLicense.
                // Treat as empty list for now.
            } else if (t === Type.STRING) {
                b.setString(StringEncoding.internalToUnicode(v as String?))
            } else if (t === Type.INTEGER) {
                b.setInt((v as StarlarkInt).toIntUnchecked())
            } else if (t === Type.BOOLEAN) {
                b.setBool(v as Boolean?)
            } else if (t === BuildType.TRISTATE) {
                b.setInt((v as TriState).toInt())
            } else if (BuildType.isLabelType(t)) { // case order matters!
                b.setString(StringEncoding.internalToUnicode(v.toString()))
            } else {
                // No native rule attribute of this type (FilesetEntry?) has a default value.
                throw IllegalStateException("unexpected type of attribute default value: " + t)
            }
            return b.build()
        }

        private fun getAllowedRuleClasses(
            ruleClasses: MutableCollection<RuleClass>, attr: Attribute
        ): AllowedRuleClassInfo {
            val info: AllowedRuleClassInfo.Builder = AllowedRuleClassInfo.newBuilder()
            info.setPolicy(AllowedRuleClassInfo.AllowedRuleClasses.ANY)

            val filter: Predicate<RuleClass?>
            if (attr.isStrictLabelCheckingEnabled()
                && ((attr.getAllowedRuleClassObjectPredicate().also { filter = it })
                        !== Predicates.alwaysTrue<RuleClass?>())
            ) {
                info.setPolicy(AllowedRuleClassInfo.AllowedRuleClasses.SPECIFIED)
                for (otherClass in Iterables.filter<RuleClass>(ruleClasses, filter)) {
                    if (!isAbstractRule(otherClass)) {
                        info.addAllowedRuleClass(otherClass.getName())
                    }
                }
            }

            return info.build()
        }

        private fun isAbstractRule(c: RuleClass): Boolean {
            return c.getName().startsWith("$")
        }
    }
}
