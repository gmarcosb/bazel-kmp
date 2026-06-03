// Copyright 2018 The Bazel Authors. All rights reserved.
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
package net.starlark.java.annot.processor

import com.google.common.truth.Truth
import com.google.testing.compile.JavaFileObjects
import com.google.testing.compile.JavaSourceSubjectFactory
import com.google.testing.compile.JavaSourcesSubject.SingleSourceAdapter
import net.starlark.java.annot.processor.StarlarkMethodProcessor
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import javax.tools.JavaFileObject

/** Unit tests for StarlarkMethodProcessor.  */
@RunWith(JUnit4::class)
class StarlarkMethodProcessorTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGoldenCase() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("GoldenCase.java"))
            .processedWith(StarlarkMethodProcessor())
            .compilesWithoutError()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrivateMethod() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("PrivateMethod.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("StarlarkMethod-annotated methods must be public.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticMethod() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StaticMethod.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("StarlarkMethod-annotated methods cannot be static.")
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldWithArguments() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StructFieldWithArguments.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "method structFieldMethod is annotated structField=true but also has 1 Param"
                        + " annotations"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldWithInvalidInfo() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StructFieldWithInvalidInfo.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "a StarlarkMethod-annotated method with structField=true may not also specify"
                        + " useStarlarkThread"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldWithExtraArgs() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StructFieldWithExtraArgs.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "a StarlarkMethod-annotated method with structField=true may not also specify"
                        + " extraPositionals"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStructFieldWithExtraKeywords() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StructFieldWithExtraKeywords.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "a StarlarkMethod-annotated method with structField=true may not also specify"
                        + " extraKeywords"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDocumentationMissing() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("DocumentationMissing.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("The 'doc' string must be non-empty if 'documented' is true.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArgumentMissing() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ArgumentMissing.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "method methodWithParams has 1 Param annotations but only 0 parameters"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkThreadMissing() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StarlarkThreadMissing.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "for useStarlarkThread special parameter 'shouldBeThread', got type java.lang.String,"
                        + " want StarlarkThread"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkInfoBeforeParams() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("StarlarkInfoBeforeParams.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "for useStarlarkThread special parameter 'three', got type java.lang.String, want"
                        + " StarlarkThread"
            )
        // Also reports:
        // - annotated type java.lang.String of parameter 'one' is not assignable
        //   to variable of type net.starlark.java.eval.StarlarkThread
        // - annotated type java.lang.Integer of parameter 'two' is not assignable
        //   to variable of type java.lang.String
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTooManyArguments() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("TooManyArguments.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "method methodWithTooManyArguments is annotated with 1 Params plus 0 special"
                        + " parameters, yet has 2 parameter variables"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamNeitherNamedNorPositional() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ParamNeitherNamedNorPositional.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("Parameter 'a_parameter' must be either positional or named")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNonDefaultParamAfterDefault() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("NonDefaultParamAfterDefault.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Positional parameter 'two' has no default value but is specified "
                        + "after one or more positional parameters with default values"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPositionalParamAfterNonPositional() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("PositionalParamAfterNonPositional.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Positional parameter 'two' is specified after one or more non-positional parameters"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPositionalOnlyParamAfterNamed() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("PositionalOnlyParamAfterNamed.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Positional-only parameter 'two' is specified after one or more named or undocumented"
                        + " parameters"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraKeywordsOutOfOrder() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ExtraKeywordsOutOfOrder.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "extraKeywords special parameter 'one' has type java.lang.String, to which"
                        + " Dict<String, Object> cannot be assigned"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtraPositionalsMissing() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ExtraPositionalsMissing.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "method threeArgMethod is annotated with 1 Params plus 2 special parameters, but has"
                        + " only 2 parameter variables"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfCallWithNoName() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("SelfCallWithNoName.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("StarlarkMethod.name must be non-empty.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSelfCallWithStructField() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("SelfCallWithStructField.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "a StarlarkMethod-annotated method with structField=true may not also specify"
                        + " selfCall=true"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMultipleSelfCallMethods() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("MultipleSelfCallMethods.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("Containing class has more than one selfCall method defined.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnablingAndDisablingFlag() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("EnablingAndDisablingFlag.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Only one of StarlarkMethod.enableOnlyWithFlag and StarlarkMethod.disableWithFlag may"
                        + " be specified."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnablingAndDisablingFlag_param() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("EnablingAndDisablingFlagParam.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Parameter 'two' has enableOnlyWithFlag and disableWithFlag set. "
                        + "At most one may be set"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConflictingMethodNames() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ConflictingMethodNames.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Containing class defines more than one method named 'conflicting_method'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToggledKwargsParam() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ToggledKwargsParam.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("The extraKeywords parameter may not be toggled by semantic flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToggledParamNoDefaultValue() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("ToggledParamNoDefaultValue.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Parameter 'two' may be disabled by semantic flag, thus defaultValue must be set"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSpecifiedGenericType() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("SpecifiedGenericType.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "parameter 'one' has generic type "
                        + "net.starlark.java.eval.Sequence<java.lang.String>"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testKwargsWithUndocumentedParam() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("KwargsWithUndocumentedParams.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining(
                "Method 'undocumented_with_kwargs' has undocumented parameters but also allows extra"
                        + " keyword parameters"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUndocumentedPositionalParam() {
        Truth.assertAbout<SingleSourceAdapter?, JavaFileObject?>(JavaSourceSubjectFactory.javaSource())
            .that(getFile("UndocumentedPositionalParam.java"))
            .processedWith(StarlarkMethodProcessor())
            .failsToCompile()
            .withErrorContaining("Parameter 'one' must be documented because it is positional")
    }

    companion object {
        private fun getFile(pathToFile: String?): JavaFileObject {
            return JavaFileObjects.forResource(
                com.google.common.io.Resources.getResource(
                    StarlarkMethodProcessorTest::class.java, "testsources/" + pathToFile
                )
            )
        }
    }
}
