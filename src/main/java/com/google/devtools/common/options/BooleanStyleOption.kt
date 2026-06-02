// Copyright 2026 The Bazel Authors. All rights reserved.
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
package com.google.devtools.common.options

/**
 * Marker for a [Converter] whose option uses the same command-line forms as a boolean flag:
 * `--name` (true), `--noname` (false), and `--name=value` with a boolean string.
 * 
 * 
 * The converter implementation must interpret the strings `"1"` and `"0"` as the
 * options parser uses them for the valueless and `--no*` forms.
 */
interface BooleanStyleOption 
