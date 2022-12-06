// Copyright 2021-present StarRocks, Inc. All rights reserved.
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

#pragma once

#include "exprs/table_function/table_function.h"
#include "runtime/primitive_type.h"

namespace starrocks::vectorized {

class TableFunctionFactory {
public:
};

extern const TableFunction* get_table_function(const std::string& name, const std::vector<LogicalType>& arg_type,
                                               const std::vector<LogicalType>& return_type,
                                               TFunctionBinaryType::type binary_type = TFunctionBinaryType::BUILTIN);

} // namespace starrocks::vectorized
