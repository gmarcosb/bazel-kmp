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
package net.starlark.java.eval

import net.starlark.java.eval.CompactImmutableDictTest
import net.starlark.java.eval.DynamicTypeCheckTest
import net.starlark.java.eval.EvalUtilsTest
import net.starlark.java.eval.EvaluationTest
import net.starlark.java.eval.FunctionTest
import net.starlark.java.eval.ImmutableKeyTrackingDictTest
import net.starlark.java.eval.MethodLibraryTest
import net.starlark.java.eval.MutabilityTest
import net.starlark.java.eval.PrinterTest
import net.starlark.java.eval.StarlarkAnnotationsTest
import net.starlark.java.eval.StarlarkClassTest
import net.starlark.java.eval.StarlarkEvaluationTest
import net.starlark.java.eval.StarlarkFlagGuardingTest
import net.starlark.java.eval.StarlarkListTest
import net.starlark.java.eval.StarlarkMutableTest
import net.starlark.java.eval.StarlarkThreadDebuggingTest
import net.starlark.java.eval.StarlarkThreadTest
import net.starlark.java.eval.StaticTypeCheckTest
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

/** EvalTests tests the Starlark evaluator.  */
@RunWith(Suite::class)
@SuiteClasses(
    DynamicTypeCheckTest::class,
    CompactImmutableDictTest::class,
    EvaluationTest::class,
    EvalUtilsTest::class,
    FunctionTest::class,
    ImmutableKeyTrackingDictTest::class,
    MethodLibraryTest::class,
    MutabilityTest::class,
    PrinterTest::class,
    StarlarkClassTest::class,
    StarlarkEvaluationTest::class,
    StarlarkFlagGuardingTest::class,
    StarlarkAnnotationsTest::class,
    StarlarkListTest::class,
    StarlarkMutableTest::class,
    StarlarkThreadDebuggingTest::class,
    StarlarkThreadTest::class,
    StaticTypeCheckTest::class
)
class EvalTests 
