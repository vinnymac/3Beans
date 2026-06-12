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

#include <cstddef>
#include <cstdint>

// Backing storage for the emulated SD card, addressed by byte offset. The SD/MMC
// controller is the only user: it issues 512-byte-aligned transfers for high-
// capacity cards and arbitrary byte transfers for legacy standard-capacity ones,
// so the interface is byte-granular rather than sector-based. Reads zero-fill any
// region past the backing store's extent, matching sparse-image semantics.
class BlockDevice {
public:
    virtual ~BlockDevice() {}

    // Read/write size bytes at the given byte offset. read() always fills buffer
    // (zero-padding past the end of the store); both return false only if the
    // device is unavailable.
    virtual bool read(uint64_t offset, size_t size, void *buffer) = 0;
    virtual bool write(uint64_t offset, size_t size, const void *buffer) = 0;

    // Total capacity in bytes, or 0 if the backing store couldn't be opened.
    virtual uint64_t capacity() = 0;

    // Whether the backing store is present and usable.
    virtual bool isOpen() = 0;

    // Persist any pending state (e.g. an in-memory overlay) before teardown.
    virtual void flush() {}
};
