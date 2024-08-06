// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/be/src/olap/rowset/bitmap_range_iterator.h

// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#pragma once

#include "roaring/roaring.hh"

namespace starrocks {

using Roaring = roaring::Roaring;

// A fast range iterator for roaring bitmap. Output ranges use closed-open form, like [from, to).
// Example:
//   input bitmap:  [0 1 4 5 6 7 10 15 16 17 18 19]
//   output ranges: [0,2), [4,8), [10,11), [15,20)
class BitmapRangeIterator {
public:
    explicit BitmapRangeIterator(const Roaring& bitmap) {
        roaring_init_iterator(&bitmap.roaring, &_iter);
        _read_next_batch();
    }

    ~BitmapRangeIterator() = default;

    // read next range into [*from, *to)
    // return false when there is no more range.
    bool next_range(uint32_t* from, uint32_t* to) {
        if (_eof) {
            return false;
        }
        *from = _buf[_buf_pos];
        auto last_val = *from;

        do {
            _buf_pos++;
            last_val++;
            if (_buf_pos == _buf_size) {
                _read_next_batch();
            }
        } while (!_eof && _buf[_buf_pos] == last_val);
        *to = last_val;
        return true;
    }

private:
    void _read_next_batch() {
        uint32_t n = roaring::api::roaring_read_uint32_iterator(&_iter, _buf, kBatchSize);
        _buf_pos = 0;
        _buf_size = n;
        _eof = n == 0;
    }

    static const uint32_t kBatchSize = 256;

    roaring::api::roaring_uint32_iterator_t _iter{};
    uint32_t _buf_pos{0};
    uint32_t _buf_size{0};
    bool _eof{false};
    uint32_t _buf[kBatchSize];
};

} // namespace starrocks
