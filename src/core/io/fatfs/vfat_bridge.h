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

#include <stdint.h>

// Bridge between FatFs's C diskio layer (diskio.c) and the C++ BlockDevice that
// is currently bound for a commit. Implemented in virtual_fat.cpp.
#ifdef __cplusplus
extern "C" {
#endif

// Read `count` 512-byte sectors starting at `sector`; returns 1 on success.
int vfatBridgeRead(uint32_t sector, uint32_t count, void *buffer);

// Total capacity of the bound device in 512-byte sectors.
uint32_t vfatBridgeSectorCount(void);

#ifdef __cplusplus
}
#endif
