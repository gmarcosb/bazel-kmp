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
package net.starlark.java.syntax

import net.starlark.java.syntax.FileLocationsTest
import net.starlark.java.syntax.LValueBoundNamesTest
import net.starlark.java.syntax.LocationTest
import net.starlark.java.syntax.NodePrinterTest
import net.starlark.java.syntax.NodeVisitorTest
import net.starlark.java.syntax.ParserInputTest
import net.starlark.java.syntax.ParserTest
import net.starlark.java.syntax.ProgramTest
import net.starlark.java.syntax.ResolverTest
import net.starlark.java.syntax.StarlarkFileTest
import net.starlark.java.syntax.TypeCheckerTest
import net.starlark.java.syntax.TypeTaggerTest
import net.starlark.java.syntax.TypesTest
import org.junit.runner.RunWith
import org.junit.runners.Suite
import org.junit.runners.Suite.SuiteClasses

/** SyntaxTests tests the syntax package (Starlark frontend).  */
@RunWith(Suite::class)
@SuiteClasses(
    FileLocationsTest::class,
    net.starlark.java.syntax.LexerTest::class,
    LocationTest::class,
    LValueBoundNamesTest::class,
    NodePrinterTest::class,
    NodeVisitorTest::class,
    ParserInputTest::class,
    ParserTest::class,
    ProgramTest::class,
    ResolverTest::class,
    StarlarkFileTest::class,
    TypeCheckerTest::class,
    TypeTaggerTest::class,
    TypesTest::class
)
class SyntaxTests 
