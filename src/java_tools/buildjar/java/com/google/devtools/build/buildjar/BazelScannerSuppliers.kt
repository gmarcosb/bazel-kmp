// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.buildjar

import com.google.errorprone.BugCheckerInfo
import com.google.errorprone.bugpatterns.AlwaysThrows
import com.google.errorprone.bugpatterns.ArrayFillIncompatibleType
import com.google.errorprone.bugpatterns.ArrayHashCode
import com.google.errorprone.bugpatterns.ArrayToString
import com.google.errorprone.bugpatterns.ArraysAsListPrimitiveArray
import com.google.errorprone.bugpatterns.AutoValueBuilderDefaultsInConstructor
import com.google.errorprone.bugpatterns.BadAnnotationImplementation
import com.google.errorprone.bugpatterns.BadShiftAmount
import com.google.errorprone.bugpatterns.BanJNDI
import com.google.errorprone.bugpatterns.BoxedPrimitiveEquality
import com.google.errorprone.bugpatterns.BugChecker
import com.google.errorprone.bugpatterns.ChainingConstructorIgnoresParameter
import com.google.errorprone.bugpatterns.CheckNotNullMultipleTimes
import com.google.errorprone.bugpatterns.CollectionToArraySafeParameter
import com.google.errorprone.bugpatterns.ComparableType
import com.google.errorprone.bugpatterns.ComparingThisWithNull
import com.google.errorprone.bugpatterns.ComparisonOutOfRange
import com.google.errorprone.bugpatterns.CompileTimeConstantChecker
import com.google.errorprone.bugpatterns.ComputeIfAbsentAmbiguousReference
import com.google.errorprone.bugpatterns.ConditionalExpressionNumericPromotion
import com.google.errorprone.bugpatterns.ConstantOverflow
import com.google.errorprone.bugpatterns.DangerousLiteralNullChecker
import com.google.errorprone.bugpatterns.DeadException
import com.google.errorprone.bugpatterns.DeadThread
import com.google.errorprone.bugpatterns.DiscardedPostfixExpression
import com.google.errorprone.bugpatterns.DoNotCallChecker
import com.google.errorprone.bugpatterns.DoNotMockChecker
import com.google.errorprone.bugpatterns.DoubleBraceInitialization
import com.google.errorprone.bugpatterns.DuplicateMapKeys
import com.google.errorprone.bugpatterns.EqualsHashCode
import com.google.errorprone.bugpatterns.EqualsNaN
import com.google.errorprone.bugpatterns.EqualsNull
import com.google.errorprone.bugpatterns.EqualsReference
import com.google.errorprone.bugpatterns.EqualsWrongThing
import com.google.errorprone.bugpatterns.ForOverrideChecker
import com.google.errorprone.bugpatterns.FunctionalInterfaceMethodChanged
import com.google.errorprone.bugpatterns.FuturesGetCheckedIllegalExceptionType
import com.google.errorprone.bugpatterns.FuzzyEqualsShouldNotBeUsedInEqualsMethod
import com.google.errorprone.bugpatterns.GetClassOnAnnotation
import com.google.errorprone.bugpatterns.GetClassOnClass
import com.google.errorprone.bugpatterns.HashtableContains
import com.google.errorprone.bugpatterns.IdentityBinaryExpression
import com.google.errorprone.bugpatterns.IdentityHashMapBoxing
import com.google.errorprone.bugpatterns.ImpossibleNullComparison
import com.google.errorprone.bugpatterns.Incomparable
import com.google.errorprone.bugpatterns.IncompatibleModifiersChecker
import com.google.errorprone.bugpatterns.IndexOfChar
import com.google.errorprone.bugpatterns.InexactVarargsConditional
import com.google.errorprone.bugpatterns.InfiniteRecursion
import com.google.errorprone.bugpatterns.InvalidPatternSyntax
import com.google.errorprone.bugpatterns.InvalidTimeZoneID
import com.google.errorprone.bugpatterns.InvalidZoneId
import com.google.errorprone.bugpatterns.IsInstanceIncompatibleType
import com.google.errorprone.bugpatterns.IsInstanceOfClass
import com.google.errorprone.bugpatterns.JUnit3TestNotRun
import com.google.errorprone.bugpatterns.JUnit4ClassAnnotationNonStatic
import com.google.errorprone.bugpatterns.JUnit4SetUpNotRun
import com.google.errorprone.bugpatterns.JUnit4TearDownNotRun
import com.google.errorprone.bugpatterns.JUnit4TestNotRun
import com.google.errorprone.bugpatterns.JUnit4TestsNotRunWithinEnclosed
import com.google.errorprone.bugpatterns.JUnitAssertSameCheck
import com.google.errorprone.bugpatterns.JUnitParameterMethodNotFound
import com.google.errorprone.bugpatterns.LiteByteStringUtf8
import com.google.errorprone.bugpatterns.LockOnBoxedPrimitive
import com.google.errorprone.bugpatterns.LoopConditionChecker
import com.google.errorprone.bugpatterns.LossyPrimitiveCompare
import com.google.errorprone.bugpatterns.MathRoundIntLong
import com.google.errorprone.bugpatterns.MissingSuperCall
import com.google.errorprone.bugpatterns.MissingTestCall
import com.google.errorprone.bugpatterns.MisusedDayOfYear
import com.google.errorprone.bugpatterns.MisusedWeekYear
import com.google.errorprone.bugpatterns.MixedDescriptors
import com.google.errorprone.bugpatterns.MockitoUsage
import com.google.errorprone.bugpatterns.ModifyingCollectionWithItself
import com.google.errorprone.bugpatterns.MustBeClosedChecker
import com.google.errorprone.bugpatterns.NCopiesOfChar
import com.google.errorprone.bugpatterns.NonCanonicalStaticImport
import com.google.errorprone.bugpatterns.NonFinalCompileTimeConstant
import com.google.errorprone.bugpatterns.NonRuntimeAnnotation
import com.google.errorprone.bugpatterns.NullTernary
import com.google.errorprone.bugpatterns.NullableOnContainingClass
import com.google.errorprone.bugpatterns.OptionalEquality
import com.google.errorprone.bugpatterns.OptionalMapUnusedValue
import com.google.errorprone.bugpatterns.OptionalOfRedundantMethod
import com.google.errorprone.bugpatterns.ParametersButNotParameterized
import com.google.errorprone.bugpatterns.PreconditionsInvalidPlaceholder
import com.google.errorprone.bugpatterns.PrivateSecurityContractProtoAccess
import com.google.errorprone.bugpatterns.ProtoBuilderReturnValueIgnored
import com.google.errorprone.bugpatterns.ProtoStringFieldReferenceEquality
import com.google.errorprone.bugpatterns.ProtoTruthMixedDescriptors
import com.google.errorprone.bugpatterns.ProtocolBufferOrdinal
import com.google.errorprone.bugpatterns.RandomCast
import com.google.errorprone.bugpatterns.RandomModInteger
import com.google.errorprone.bugpatterns.RequiredModifiersChecker
import com.google.errorprone.bugpatterns.RestrictedApiChecker
import com.google.errorprone.bugpatterns.ReturnValueIgnored
import com.google.errorprone.bugpatterns.SelfAssertion
import com.google.errorprone.bugpatterns.SelfAssignment
import com.google.errorprone.bugpatterns.SelfComparison
import com.google.errorprone.bugpatterns.SelfEquals
import com.google.errorprone.bugpatterns.ShouldHaveEvenArgs
import com.google.errorprone.bugpatterns.SizeGreaterThanOrEqualsZero
import com.google.errorprone.bugpatterns.StreamToString
import com.google.errorprone.bugpatterns.StringBuilderInitWithChar
import com.google.errorprone.bugpatterns.SubstringOfZero
import com.google.errorprone.bugpatterns.SuppressWarningsDeprecated
import com.google.errorprone.bugpatterns.TestParametersNotInitialized
import com.google.errorprone.bugpatterns.TheoryButNoTheories
import com.google.errorprone.bugpatterns.ThrowIfUncheckedKnownChecked
import com.google.errorprone.bugpatterns.ThrowNull
import com.google.errorprone.bugpatterns.TreeToString
import com.google.errorprone.bugpatterns.TryFailThrowable
import com.google.errorprone.bugpatterns.TypeParameterQualifier
import com.google.errorprone.bugpatterns.UnicodeDirectionalityCharacters
import com.google.errorprone.bugpatterns.UnicodeInCode
import com.google.errorprone.bugpatterns.UnnecessaryTypeArgument
import com.google.errorprone.bugpatterns.UnusedAnonymousClass
import com.google.errorprone.bugpatterns.UnusedCollectionModifiedInPlace
import com.google.errorprone.bugpatterns.VarTypeName
import com.google.errorprone.bugpatterns.WrongOneof
import com.google.errorprone.bugpatterns.XorPower
import com.google.errorprone.bugpatterns.android.BundleDeserializationCast
import com.google.errorprone.bugpatterns.android.IsLoggableTagLength
import com.google.errorprone.bugpatterns.android.MislabeledAndroidString
import com.google.errorprone.bugpatterns.android.ParcelableCreator
import com.google.errorprone.bugpatterns.android.RectIntersectReturnValueIgnored
import com.google.errorprone.bugpatterns.argumentselectiondefects.AutoValueConstructorOrderChecker
import com.google.errorprone.bugpatterns.checkreturnvalue.NoCanIgnoreReturnValueOnClasses
import com.google.errorprone.bugpatterns.collectionincompatibletype.CollectionIncompatibleType
import com.google.errorprone.bugpatterns.collectionincompatibletype.CompatibleWithMisuse
import com.google.errorprone.bugpatterns.collectionincompatibletype.IncompatibleArgumentType
import com.google.errorprone.bugpatterns.flogger.FloggerFormatString
import com.google.errorprone.bugpatterns.flogger.FloggerLogString
import com.google.errorprone.bugpatterns.flogger.FloggerLogVarargs
import com.google.errorprone.bugpatterns.flogger.FloggerSplitLogStatement
import com.google.errorprone.bugpatterns.formatstring.FormatStringAnnotationChecker
import com.google.errorprone.bugpatterns.formatstring.LenientFormatStringValidation
import com.google.errorprone.bugpatterns.inject.InjectOnMemberAndConstructor
import com.google.errorprone.bugpatterns.inject.JavaxInjectOnAbstractMethod
import com.google.errorprone.bugpatterns.inject.MisplacedScopeAnnotations
import com.google.errorprone.bugpatterns.inject.MoreThanOneInjectableConstructor
import com.google.errorprone.bugpatterns.inject.MoreThanOneScopeAnnotationOnClass
import com.google.errorprone.bugpatterns.inject.OverlappingQualifierAndScopeAnnotation
import com.google.errorprone.bugpatterns.inject.dagger.AndroidInjectionBeforeSuper
import com.google.errorprone.bugpatterns.inject.dagger.ProvidesNull
import com.google.errorprone.bugpatterns.inject.guice.AssistedInjectScoping
import com.google.errorprone.bugpatterns.inject.guice.AssistedParameters
import com.google.errorprone.bugpatterns.inject.guice.InjectOnFinalField
import com.google.errorprone.bugpatterns.inject.guice.OverridesJavaxInjectableMethod
import com.google.errorprone.bugpatterns.inject.guice.ProvidesMethodOutsideOfModule
import com.google.errorprone.bugpatterns.nullness.DereferenceWithNullBranch
import com.google.errorprone.bugpatterns.nullness.NullArgumentForNonNullParameter
import com.google.errorprone.bugpatterns.nullness.UnnecessaryCheckNotNull
import com.google.errorprone.bugpatterns.nullness.UnsafeWildcard
import com.google.errorprone.bugpatterns.threadsafety.GuardedByChecker
import com.google.errorprone.bugpatterns.threadsafety.ImmutableChecker
import com.google.errorprone.bugpatterns.time.DurationFrom
import com.google.errorprone.bugpatterns.time.DurationGetTemporalUnit
import com.google.errorprone.bugpatterns.time.DurationTemporalUnit
import com.google.errorprone.bugpatterns.time.DurationToLongTimeUnit
import com.google.errorprone.bugpatterns.time.FromTemporalAccessor
import com.google.errorprone.bugpatterns.time.InstantTemporalUnit
import com.google.errorprone.bugpatterns.time.InvalidJavaTimeConstant
import com.google.errorprone.bugpatterns.time.JodaToSelf
import com.google.errorprone.bugpatterns.time.LocalDateTemporalAmount
import com.google.errorprone.bugpatterns.time.PeriodFrom
import com.google.errorprone.bugpatterns.time.PeriodGetTemporalUnit
import com.google.errorprone.bugpatterns.time.PeriodTimeMath
import com.google.errorprone.bugpatterns.time.TemporalAccessorGetChronoField
import com.google.errorprone.bugpatterns.time.ZoneIdOfZ
import com.google.errorprone.scanner.BuiltInCheckerSuppliers
import com.google.errorprone.scanner.ScannerSupplier

/** A factory for the [ScannerSupplier] that supplies Error Prone checks for Bazel.  */
internal object BazelScannerSuppliers {
    fun bazelChecks(): ScannerSupplier? {
        return BuiltInCheckerSuppliers.allChecks().filter(
            com.google.common.base.Predicates.`in`<BugCheckerInfo?>(
                ENABLED_ERRORS
            )
        )
    }

    // The list of default Error Prone errors as of 2023-8-17, generated from:
    // https://github.com/google/error-prone/blob/1b1ef67c6dc59eb1060e37cf989f95312e84e76d/core/src/main/java/com/google/errorprone/scanner/BuiltInCheckerSuppliers.java#L635
    // New errors should not be enabled in this list to avoid breaking changes in java_rules release
    private val ENABLED_ERRORS: com.google.common.collect.ImmutableSet<BugCheckerInfo?> =
        BuiltInCheckerSuppliers.getSuppliers( // keep-sorted start
            AlwaysThrows::class.java,
            AndroidInjectionBeforeSuper::class.java,
            com.google.errorprone.bugpatterns.ArrayEquals::class.java,
            ArrayFillIncompatibleType::class.java,
            ArrayHashCode::class.java,
            ArrayToString::class.java,
            ArraysAsListPrimitiveArray::class.java,
            AssistedInjectScoping::class.java,
            AssistedParameters::class.java,
            AutoValueBuilderDefaultsInConstructor::class.java,
            AutoValueConstructorOrderChecker::class.java,
            BadAnnotationImplementation::class.java,
            BadShiftAmount::class.java,
            BanJNDI::class.java,
            BoxedPrimitiveEquality::class.java,
            BundleDeserializationCast::class.java,
            ChainingConstructorIgnoresParameter::class.java,
            CheckNotNullMultipleTimes::class.java,
            com.google.errorprone.bugpatterns.CheckReturnValue::class.java,
            CollectionIncompatibleType::class.java,
            CollectionToArraySafeParameter::class.java,
            ComparableType::class.java,
            ComparingThisWithNull::class.java,
            ComparisonOutOfRange::class.java,
            CompatibleWithMisuse::class.java,
            CompileTimeConstantChecker::class.java,
            ComputeIfAbsentAmbiguousReference::class.java,
            ConditionalExpressionNumericPromotion::class.java,
            ConstantOverflow::class.java,
            DangerousLiteralNullChecker::class.java,
            DeadException::class.java,
            DeadThread::class.java,
            DereferenceWithNullBranch::class.java,
            DiscardedPostfixExpression::class.java,
            DoNotCallChecker::class.java,
            DoNotMockChecker::class.java,
            DoubleBraceInitialization::class.java,
            DuplicateMapKeys::class.java,
            DurationFrom::class.java,
            DurationGetTemporalUnit::class.java,
            DurationTemporalUnit::class.java,
            DurationToLongTimeUnit::class.java,
            EqualsHashCode::class.java,
            EqualsNaN::class.java,
            EqualsNull::class.java,
            EqualsReference::class.java,
            EqualsWrongThing::class.java,
            FloggerFormatString::class.java,
            FloggerLogString::class.java,
            FloggerLogVarargs::class.java,
            FloggerSplitLogStatement::class.java,
            ForOverrideChecker::class.java,
            com.google.errorprone.bugpatterns.formatstring.FormatString::class.java,
            FormatStringAnnotationChecker::class.java,
            FromTemporalAccessor::class.java,
            FunctionalInterfaceMethodChanged::class.java,
            FuturesGetCheckedIllegalExceptionType::class.java,
            FuzzyEqualsShouldNotBeUsedInEqualsMethod::class.java,
            GetClassOnAnnotation::class.java,
            GetClassOnClass::class.java,
            GuardedByChecker::class.java,
            HashtableContains::class.java,
            IdentityBinaryExpression::class.java,
            IdentityHashMapBoxing::class.java,
            ImmutableChecker::class.java,
            ImpossibleNullComparison::class.java,
            Incomparable::class.java,
            IncompatibleArgumentType::class.java,
            IncompatibleModifiersChecker::class.java,
            IndexOfChar::class.java,
            InexactVarargsConditional::class.java,
            InfiniteRecursion::class.java,
            InjectOnFinalField::class.java,
            InjectOnMemberAndConstructor::class.java,
            InstantTemporalUnit::class.java,
            InvalidJavaTimeConstant::class.java,
            InvalidPatternSyntax::class.java,
            InvalidTimeZoneID::class.java,
            InvalidZoneId::class.java,
            IsInstanceIncompatibleType::class.java,
            IsInstanceOfClass::class.java,
            IsLoggableTagLength::class.java,
            JUnit3TestNotRun::class.java,
            JUnit4ClassAnnotationNonStatic::class.java,
            JUnit4SetUpNotRun::class.java,
            JUnit4TearDownNotRun::class.java,
            JUnit4TestNotRun::class.java,
            JUnit4TestsNotRunWithinEnclosed::class.java,
            JUnitAssertSameCheck::class.java,
            JUnitParameterMethodNotFound::class.java,
            JavaxInjectOnAbstractMethod::class.java,
            JodaToSelf::class.java,
            LenientFormatStringValidation::class.java,
            LiteByteStringUtf8::class.java,
            LocalDateTemporalAmount::class.java,
            LockOnBoxedPrimitive::class.java,
            LoopConditionChecker::class.java,
            LossyPrimitiveCompare::class.java,
            MathRoundIntLong::class.java,
            MislabeledAndroidString::class.java,
            MisplacedScopeAnnotations::class.java,
            MissingSuperCall::class.java,
            MissingTestCall::class.java,
            MisusedDayOfYear::class.java,
            MisusedWeekYear::class.java,
            MixedDescriptors::class.java,
            MockitoUsage::class.java,
            ModifyingCollectionWithItself::class.java,
            MoreThanOneInjectableConstructor::class.java,
            MoreThanOneScopeAnnotationOnClass::class.java,
            MustBeClosedChecker::class.java,
            NCopiesOfChar::class.java,
            NoCanIgnoreReturnValueOnClasses::class.java,
            NonCanonicalStaticImport::class.java,
            NonFinalCompileTimeConstant::class.java,
            NonRuntimeAnnotation::class.java,
            NullArgumentForNonNullParameter::class.java,
            NullTernary::class.java,
            NullableOnContainingClass::class.java,
            OptionalEquality::class.java,
            OptionalMapUnusedValue::class.java,
            OptionalOfRedundantMethod::class.java,
            OverlappingQualifierAndScopeAnnotation::class.java,
            OverridesJavaxInjectableMethod::class.java,
            com.google.errorprone.bugpatterns.PackageInfo::class.java,
            ParametersButNotParameterized::class.java,
            ParcelableCreator::class.java,
            PeriodFrom::class.java,
            PeriodGetTemporalUnit::class.java,
            PeriodTimeMath::class.java,
            PreconditionsInvalidPlaceholder::class.java,
            PrivateSecurityContractProtoAccess::class.java,
            ProtoBuilderReturnValueIgnored::class.java,
            ProtoStringFieldReferenceEquality::class.java,
            ProtoTruthMixedDescriptors::class.java,
            ProtocolBufferOrdinal::class.java,
            ProvidesMethodOutsideOfModule::class.java,
            ProvidesNull::class.java,
            RandomCast::class.java,
            RandomModInteger::class.java,
            RectIntersectReturnValueIgnored::class.java,
            RequiredModifiersChecker::class.java,
            RestrictedApiChecker::class.java,
            ReturnValueIgnored::class.java,  // If you got a build error here, remove the rewrite in
            // devtools/blaze/bazel/admin/copybara/copy.bara.sky.
            SelfAssertion::class.java,
            SelfAssignment::class.java,
            SelfComparison::class.java,
            SelfEquals::class.java,
            ShouldHaveEvenArgs::class.java,
            SizeGreaterThanOrEqualsZero::class.java,
            StreamToString::class.java,
            StringBuilderInitWithChar::class.java,
            SubstringOfZero::class.java,
            SuppressWarningsDeprecated::class.java,
            TemporalAccessorGetChronoField::class.java,
            TestParametersNotInitialized::class.java,
            TheoryButNoTheories::class.java,
            ThrowIfUncheckedKnownChecked::class.java,
            ThrowNull::class.java,
            TreeToString::class.java,
            TryFailThrowable::class.java,
            TypeParameterQualifier::class.java,
            UnicodeDirectionalityCharacters::class.java,
            UnicodeInCode::class.java,
            UnnecessaryCheckNotNull::class.java,
            UnnecessaryTypeArgument::class.java,
            UnsafeWildcard::class.java,
            UnusedAnonymousClass::class.java,
            UnusedCollectionModifiedInPlace::class.java,
            com.google.errorprone.bugpatterns.inlineme.Validator::class.java,
            VarTypeName::class.java,
            WrongOneof::class.java,
            XorPower::class.java,
            ZoneIdOfZ::class.java,
            findMaybeInNullnessSubpackage("AsyncCallableReturnsNull"),
            findMaybeInNullnessSubpackage("AsyncFunctionReturnsNull") // keep-sorted end
        )

    private fun findMaybeInNullnessSubpackage(simpleName: String?): java.lang.Class<out BugChecker?> {
        for (packageName in com.google.common.collect.ImmutableList.of<String?>(
            "com.google.errorprone.bugpatterns", "com.google.errorprone.bugpatterns.nullness"
        )) {
            try {
                return java.lang.Class.forName(packageName + "." + simpleName)
                    .asSubclass<BugChecker?>(BugChecker::class.java)
            } catch (e: java.lang.ClassNotFoundException) {
                // continue
            }
        }
        throw java.lang.IllegalStateException("Could not find " + simpleName)
    }
}
