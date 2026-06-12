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

#include <algorithm>
#include <cstring>
#include "file_block.h"

// Use 64-bit file offsets everywhere so a 64 GB sd.img is seekable even where
// long is 32-bit (e.g. Windows). MinGW provides the MSVC-style helpers.
#ifdef WINDOWS
#define FSEEK64 _fseeki64
#define FTELL64 _ftelli64
#else
#define FSEEK64 fseeko
#define FTELL64 ftello
#endif

FileBlock::FileBlock(const std::string &path) {
    // Open the image read-write and record its size for capacity reporting
    if (!(file = fopen(path.c_str(), "rb+"))) return;
    FSEEK64(file, 0, SEEK_END);
    fileSize = FTELL64(file);
}

FileBlock::~FileBlock() {
    if (file) fclose(file);
}

bool FileBlock::read(uint64_t offset, size_t size, void *buffer) {
    if (!file) return false;

    // Zero-fill any portion at or beyond the end of the file (sparse semantics)
    if (offset >= fileSize) {
        memset(buffer, 0, size);
        return true;
    }
    size_t avail = std::min<uint64_t>(size, fileSize - offset);
    if (avail < size)
        memset((uint8_t*)buffer + avail, 0, size - avail);

    // Read the backed portion from the file
    FSEEK64(file, offset, SEEK_SET);
    fread(buffer, 1, avail, file);
    return true;
}

bool FileBlock::write(uint64_t offset, size_t size, const void *buffer) {
    if (!file) return false;
    FSEEK64(file, offset, SEEK_SET);
    fwrite(buffer, 1, size, file);
    if (offset + size > fileSize) fileSize = offset + size;
    return true;
}
