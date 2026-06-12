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

#include <cstdint>
#include <cstdio>
#include <string>
#include <unordered_map>
#include <vector>

#include "block_device.h"

// Presents a host directory to the emulator as a read-only FAT32 SD card,
// synthesizing the boot sector, FATs, and directory entries on the fly and
// reading file data straight from the host files. Guest writes are captured in a
// copy-on-write overlay (kept in memory here; persistence is added separately),
// so neither the host files nor the synthesized base are ever mutated by the
// guest. The folder is the source of truth; emulator writes are reconciled back
// into it out of band (see commit, added in a later phase).
class VirtualFatBlock: public BlockDevice {
public:
    VirtualFatBlock(const std::string &rootPath, const std::string &overlayPath);
    ~VirtualFatBlock();

    bool read(uint64_t offset, size_t size, void *buffer) override;
    bool write(uint64_t offset, size_t size, const void *buffer) override;
    uint64_t capacity() override { return uint64_t(totalSectors) << 9; }
    bool isOpen() override { return opened; }
    void flush() override; // persist the overlay sidecar if there are writes

    // Whether the guest has written to the card this session, and whether the
    // host folder changed since a persisted overlay was pinned to it.
    bool isDirty() const { return dirty; }
    bool hasDrift() const { return drifted; }

    // Drop the overlay and its sidecar (e.g. after committing, or on a reset)
    void discardOverlay();

    // Reconcile the overlay's final filesystem state back into the host folder
    // (creating, updating, and deleting files to match), clearing the overlay on
    // success. A no-op with no writes; returns false and keeps the overlay if the
    // image can't be read cleanly, so nothing is lost on a bad commit.
    bool commit();

    // Write the current base+overlay out as a standalone, sparse FAT32 image.
    bool exportImage(const std::string &destPath);

    // Mirror the FAT32 image on dev into folder (create/update/delete). Static and
    // device-agnostic so it can be tested against any BlockDevice, e.g. a real
    // image built and modified by mtools.
    static bool commitImageToFolder(BlockDevice &dev, const std::string &folder);

    // Run a commit out of band: after a stop, on next-launch crash recovery, or a
    // "sync now". Builds a device from the sidecar and reconciles it. Returns
    // 0 = no overlay to commit, 1 = committed, 2 = folder drifted (not committed,
    // caller must resolve), 3 = error. allowDrift forces a commit despite drift.
    static int commitOverlay(const std::string &rootPath, const std::string &overlayPath,
        bool allowDrift = false);

    // Discard a pending overlay sidecar (drop the emulator's unsynced writes)
    static void resetOverlay(const std::string &overlayPath);

private:
    // A scanned host file or directory and its assigned FAT32 cluster range
    struct Node {
        std::string name; // entry name as stored on the host (UTF-8)
        std::string hostPath; // absolute host path, for reading file data
        bool isDir = false;
        uint64_t size = 0; // file size in bytes (0 for directories)
        uint16_t fatDate = 0, fatTime = 0; // FAT-encoded modification time
        uint32_t firstCluster = 0; // 0 for empty files
        uint32_t clusterCount = 0;
        uint32_t parentCluster = 0; // for the ".." entry (0 if parent is root)
        std::vector<Node> children; // populated for directories, sorted by name
    };

    // A contiguous run of clusters owned by one node, for cluster -> node lookup
    struct Extent {
        uint32_t startCluster;
        uint32_t clusterCount;
        const Node *node;
    };

    std::string rootPath;
    std::string overlayPath;
    bool opened = false;
    bool dirty = false; // the guest has written to the overlay this session
    bool drifted = false; // the folder changed since the overlay was pinned
    uint64_t manifestSignature = 0; // hash of the pinned folder layout

    // FAT32 geometry, matching the desktop/Android "create empty sd.img" helper
    static const uint32_t bytesPerSec = 512;
    static const uint32_t secPerClus = 64; // 32 KB clusters
    static const uint32_t rsvdSecCnt = 32;
    static const uint32_t numFATs = 2;
    static const uint32_t clusterBytes = bytesPerSec * secPerClus;
    uint32_t totalSectors = 0;
    uint32_t fatSz = 0; // sectors per FAT
    uint32_t firstDataSector = 0;
    uint32_t countOfClusters = 0;
    uint32_t nextFreeCluster = 2; // first free cluster after allocation

    Node root;
    std::vector<Extent> extents; // sorted ascending by startCluster
    std::unordered_map<const Node*, std::vector<uint8_t>> dirCache;
    std::unordered_map<std::string, FILE*> openFiles;

    // Copy-on-write overlay: sector index -> 512 bytes the guest has written
    std::unordered_map<uint32_t, std::vector<uint8_t>> overlay;

    void scan(const std::string &path, Node &node);
    void allocate(Node &node, bool isRoot);
    void rebuildExtents(Node &node);
    uint64_t computeSignature(const Node &node) const;
    bool loadOverlay();
    void saveOverlay();
    void serializeNode(const Node &node, std::vector<uint8_t> &out) const;
    void deserializeNode(Node &node, const uint8_t *&p, const uint8_t *end);
    const Extent *findExtent(uint32_t cluster) const;
    uint32_t fatEntry(uint32_t cluster) const;
    const std::vector<uint8_t> &dirBytes(const Node *node);
    FILE *openHostFile(const std::string &path);

    void buildBootSector(uint8_t *sector);
    void buildFsInfo(uint8_t *sector);
    void synthSector(uint32_t sector, uint8_t *out);
    void readSector(uint32_t sector, uint8_t *out);
    void writeSector(uint32_t sector, const uint8_t *in);
};
