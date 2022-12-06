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
#include "formats/parquet/column_converter.h"

namespace starrocks {
class RandomAccessFile;
class SharedBufferedInputStream;
namespace vectorized {
struct HdfsScanStats;
}
} // namespace starrocks

namespace starrocks::parquet {
struct ColumnReaderContext {
    vectorized::Buffer<uint8_t>* filter = nullptr;
    size_t next_row = 0;
    size_t rows_to_skip = 0;

    void advance(size_t num_rows) { next_row += num_rows; }
};

struct ColumnReaderOptions {
    std::string timezone;
    bool case_sensitive = false;
    int chunk_size = 0;
    vectorized::HdfsScanStats* stats = nullptr;
    RandomAccessFile* file = nullptr;
    SharedBufferedInputStream* sb_stream = nullptr;
    tparquet::RowGroup* row_group_meta = nullptr;
    ColumnReaderContext* context = nullptr;
};

class ColumnReader {
public:
    // TODO(zc): review this,
    // create a column reader
    static Status create(const ColumnReaderOptions& opts, const ParquetField* field, const TypeDescriptor& col_type,
                         std::unique_ptr<ColumnReader>* reader);

    virtual ~ColumnReader() = default;

    virtual Status prepare_batch(size_t* num_records, ColumnContentType content_type, vectorized::Column* column) = 0;
    virtual Status finish_batch() = 0;

    Status next_batch(size_t* num_records, ColumnContentType content_type, vectorized::Column* column) {
        RETURN_IF_ERROR(prepare_batch(num_records, content_type, column));
        return finish_batch();
    }

    virtual void get_levels(level_t** def_levels, level_t** rep_levels, size_t* num_levels) = 0;

    virtual Status get_dict_values(vectorized::Column* column) {
        return Status::NotSupported("get_dict_values is not supported");
    }

    virtual Status get_dict_values(const std::vector<int32_t>& dict_codes, vectorized::Column* column) {
        return Status::NotSupported("get_dict_values is not supported");
    }

    virtual Status get_dict_codes(const std::vector<Slice>& dict_values, std::vector<int32_t>* dict_codes) {
        return Status::NotSupported("get_dict_codes is not supported");
    }

public:
    std::unique_ptr<ColumnConverter> converter;
};

} // namespace starrocks::parquet
