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
package com.google.devtools.build.docgen.testutil

import com.google.devtools.build.lib.packages.Attribute.attr

/**
 * Rule definitions that can be used for testing.
 */
class TestData {
    class TestRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder
                .add(attr("foo", LABEL_LIST))
                .build()
        }

        public override fun getMetadata(): Metadata {
            return RuleDefinition.Metadata.builder().name("testrule")
                .factoryClass(DummyRuleFactory::class.java).ancestors(IntermediateRule::class.java).build()
        }
    }

    class IntermediateRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder.build()
        }

        public override fun getMetadata(): Metadata {
            return RuleDefinition.Metadata.builder().name("testrule")
                .factoryClass(DummyRuleFactory::class.java).ancestors(BaseRule::class.java).build()
        }
    }

    class BaseRule : RuleDefinition {
        public override fun build(builder: RuleClass.Builder, environment: RuleDefinitionEnvironment?): RuleClass {
            return builder.build()
        }

        public override fun getMetadata(): Metadata {
            return RuleDefinition.Metadata.builder().name("base_rule")
                .factoryClass(DummyRuleFactory::class.java).build()
        }
    }

    class DummyRuleFactory : RuleConfiguredTargetFactory {
        @Throws(InterruptedException::class, RuleErrorException::class, ActionConflictException::class)
        public override fun create(ruleContext: RuleContext?): ConfiguredTarget? {
            throw IllegalStateException()
        }
    }
}

