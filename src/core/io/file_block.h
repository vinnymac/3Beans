/*
    Copyright 2023-2026 Hydr8gon

    This file is part of 3Beans.

    3Beans is free software: you can redistribute it and/or modify it
    under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    3Beans is distributed in the hope that it will be useful, but
    WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
    General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with 3Beans. If not, see <https://www.gnu.org/licenses/>.
*/

#pragma once

#include <cstdio>
#include <string>
#include "block_device.h"

// A BlockDevice backed by a plain file opened with fopen — the historical sd.img
// path. Reads past the end of the file are zero-filled, and 64-bit offsets are
// used so images larger than 2 GB work regardless of the platform's long width.
class FileBlock: public BlockDevice {
public:
    FileBlock(const std::string &path);
    ~FileBlock();

    bool read(uint64_t offset, size_t size, void *buffer) override;
    bool write(uint64_t offset, size_t size, const void *buffer) override;
    uint64_t capacity() override { return fileSize; }
    bool isOpen() override { return file != nullptr; }

private:
    FILE *file = nullptr;
    uint64_t fileSize = 0;
};
