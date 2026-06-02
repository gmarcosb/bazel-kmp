// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.cmdline

/**
 * Marker interface indicating either a [ ] or a [MarkerRuntimeException].
 * Used with a generic type to indicate that a method can optionally throw `QueryException` if
 * the caller passes `QueryException.class` as a parameter to the method.
 * 
 * 
 * The only outside implementation of this interface is [ ]. Do not implement or extend!
 * 
 * 
 * Used to narrow a generic type like `E extends Exception` to `E extends Exception & QueryExceptionMarkerInterface`, guaranteeing that the method will only throw [ ] if any exception of type E is thrown.
 * Because `E` will appear in the "throws" clause of a method, it must extend [ ].
 */
interface QueryExceptionMarkerInterface {
    /**
     * Marker class indicating that a given method does not throw QueryException. Pass `MarkerRuntimeException.class` as a parameter.
     */
    class MarkerRuntimeException : java.lang.RuntimeException(), QueryExceptionMarkerInterface
}
