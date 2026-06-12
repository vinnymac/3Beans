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
#include <ctime>
#include <set>
#include <dirent.h>
#include <sys/stat.h>

#include "virtual_fat.h"
#include "../defines.h"

// Use 64-bit file offsets so file data past 2 GB reads correctly everywhere
#ifdef WINDOWS
#define FSEEK64 _fseeki64
#else
#define FSEEK64 fseeko
#endif

namespace {

// Little-endian field writers into a byte buffer
void putLE16(uint8_t *p, uint16_t v) { p[0] = v; p[1] = v >> 8; }
void putLE32(uint8_t *p, uint32_t v) { p[0] = v; p[1] = v >> 8; p[2] = v >> 16; p[3] = v >> 24; }

// Little-endian appenders/readers for the overlay sidecar serialization
void wr8(std::vector<uint8_t> &b, uint8_t v) { b.push_back(v); }
void wr16(std::vector<uint8_t> &b, uint16_t v) { b.push_back(v); b.push_back(v >> 8); }
void wr32(std::vector<uint8_t> &b, uint32_t v) { for (int i = 0; i < 4; i++) b.push_back(v >> (8 * i)); }
void wr64(std::vector<uint8_t> &b, uint64_t v) { for (int i = 0; i < 8; i++) b.push_back(v >> (8 * i)); }
void wrStr(std::vector<uint8_t> &b, const std::string &s) {
    wr32(b, uint32_t(s.size()));
    b.insert(b.end(), s.begin(), s.end());
}

// Bounds-checked readers advancing p; set ok=false on overrun (corrupt sidecar)
uint8_t rd8(const uint8_t *&p, const uint8_t *end, bool &ok) {
    if (p + 1 > end) { ok = false; return 0; }
    return *p++;
}
uint16_t rd16(const uint8_t *&p, const uint8_t *end, bool &ok) {
    uint16_t v = rd8(p, end, ok); return v | (uint16_t(rd8(p, end, ok)) << 8);
}
uint32_t rd32(const uint8_t *&p, const uint8_t *end, bool &ok) {
    uint32_t v = 0; for (int i = 0; i < 4; i++) v |= uint32_t(rd8(p, end, ok)) << (8 * i); return v;
}
uint64_t rd64(const uint8_t *&p, const uint8_t *end, bool &ok) {
    uint64_t lo = rd32(p, end, ok), hi = rd32(p, end, ok); return lo | (hi << 32);
}
std::string rdStr(const uint8_t *&p, const uint8_t *end, bool &ok) {
    uint32_t n = rd32(p, end, ok);
    if (!ok || p + n > end) { ok = false; return std::string(); }
    std::string s((const char*)p, n); p += n; return s;
}

const char OVERLAY_MAGIC[8] = { '3', 'B', 'S', 'D', 'O', 'V', 'L', '1' };

// Decode a UTF-8 string to UTF-16 code units (with surrogate pairs), substituting
// U+FFFD for malformed sequences so a bad name can never desync the parser
std::vector<uint16_t> utf8ToUtf16(const std::string &s) {
    std::vector<uint16_t> out;
    size_t i = 0, n = s.size();
    while (i < n) {
        uint8_t c = s[i++];
        uint32_t cp;
        int extra;
        if (c < 0x80) { cp = c; extra = 0; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; extra = 1; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; extra = 2; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; extra = 3; }
        else { out.push_back(0xFFFD); continue; }
        bool ok = true;
        for (int k = 0; k < extra; k++) {
            if (i >= n || (uint8_t(s[i]) & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (uint8_t(s[i++]) & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); continue; }
        if (cp >= 0xD800 && cp <= 0xDFFF) cp = 0xFFFD;
        if (cp <= 0xFFFF) {
            out.push_back(uint16_t(cp));
        }
        else if (cp <= 0x10FFFF) {
            cp -= 0x10000;
            out.push_back(uint16_t(0xD800 + (cp >> 10)));
            out.push_back(uint16_t(0xDC00 + (cp & 0x3FF)));
        }
        else {
            out.push_back(0xFFFD);
        }
    }
    return out;
}

// Characters allowed unescaped in a FAT 8.3 short name (plus A-Z and 0-9)
bool isShortNameChar(char c) {
    if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) return true;
    return strchr("$%'-_@~`!(){}^#&", c) != nullptr;
}

// Whether a host name is already a canonical uppercase 8.3 name needing no LFN
bool isValid83Upper(const std::string &name) {
    if (name.empty() || name == "." || name == "..") return false;
    size_t dot = name.find_last_of('.');
    std::string base = (dot == std::string::npos) ? name : name.substr(0, dot);
    std::string ext = (dot == std::string::npos) ? std::string() : name.substr(dot + 1);
    if (base.empty() || base.size() > 8 || ext.size() > 3) return false;
    if (dot != std::string::npos && ext.find('.') != std::string::npos) return false;
    for (char c : base) if (!isShortNameChar(c)) return false;
    for (char c : ext) if (!isShortNameChar(c)) return false;
    return true;
}

// Number of 32-byte long-name entries a node needs (0 if a plain 8.3 name)
size_t lfnEntryCount(const std::string &name) {
    if (isValid83Upper(name)) return 0;
    size_t units = utf8ToUtf16(name).size();
    return (units + 12) / 13; // 13 UTF-16 units per LFN entry, at least one
}

// Build a unique 11-byte 8.3 short name. Clean names pass through uppercased;
// names that carry an LFN get a "~N" numeric tail to stay unique in the directory
std::string makeShortName(const std::string &name, bool mangle, std::set<std::string> &used) {
    size_t dot = name.find_last_of('.');
    std::string rawBase = (dot == std::string::npos) ? name : name.substr(0, dot);
    std::string rawExt = (dot == std::string::npos) ? std::string() : name.substr(dot + 1);

    std::string base, ext;
    for (char c : rawBase) {
        char u = (c >= 'a' && c <= 'z') ? c - 32 : c;
        if (isShortNameChar(u)) base += u;
        else if (c != ' ' && c != '.') base += '_';
    }
    for (char c : rawExt) {
        char u = (c >= 'a' && c <= 'z') ? c - 32 : c;
        if (isShortNameChar(u)) ext += u;
        else if (c != ' ' && c != '.') ext += '_';
    }
    if (base.empty()) base = "_";
    ext = ext.substr(0, 3);

    auto compose = [&](const std::string &b) -> std::string {
        std::string s(11, ' ');
        for (size_t i = 0; i < b.size() && i < 8; i++) s[i] = b[i];
        for (size_t i = 0; i < ext.size() && i < 3; i++) s[8 + i] = ext[i];
        return s;
    };

    if (!mangle) {
        std::string s = compose(base.substr(0, 8));
        used.insert(s);
        return s;
    }

    // Append ~1, ~2, ... shortening the base as needed until unique
    for (uint32_t n = 1;; n++) {
        std::string tail = "~" + std::to_string(n);
        size_t keep = (tail.size() >= 8) ? 1 : (8 - tail.size());
        std::string b = base.substr(0, std::min(base.size(), keep)) + tail;
        std::string s = compose(b);
        if (used.find(s) == used.end()) { used.insert(s); return s; }
    }
}

// FAT checksum of an 11-byte short name, stamped into every LFN entry
uint8_t lfnChecksum(const std::string &shortName) {
    uint8_t sum = 0;
    for (int i = 0; i < 11; i++)
        sum = ((sum & 1) ? 0x80 : 0) + (sum >> 1) + uint8_t(shortName[i]);
    return sum;
}

// Convert a host modification time to packed FAT date/time, clamped to 1980+
void fatTimestamp(time_t t, uint16_t &date, uint16_t &fatTime) {
    struct tm lt = *localtime(&t);
    int year = lt.tm_year + 1900;
    if (year < 1980) { date = (1 << 5) | 1; fatTime = 0; return; } // 1980-01-01
    date = uint16_t(((year - 1980) << 9) | ((lt.tm_mon + 1) << 5) | lt.tm_mday);
    fatTime = uint16_t((lt.tm_hour << 11) | (lt.tm_min << 5) | (lt.tm_sec >> 1));
}

} // namespace

VirtualFatBlock::VirtualFatBlock(const std::string &rootPath, const std::string &overlayPath):
    rootPath(rootPath), overlayPath(overlayPath) {
    // Fixed 64 GB geometry so the card is always treated as high-capacity
    totalSectors = uint32_t((64ULL << 30) / bytesPerSec);
    uint32_t tmp = (256 * secPerClus + numFATs) / numFATs;
    fatSz = (totalSectors - rsvdSecCnt + tmp - 1) / tmp;
    firstDataSector = rsvdSecCnt + numFATs * fatSz;
    countOfClusters = (totalSectors - firstDataSector) / secPerClus;

    // Resume a persisted overlay first (e.g. recovering writes after a crash).
    // The base is reproduced from the manifest pinned inside the overlay, so its
    // sectors always refer to the exact layout they were written against.
    struct stat ost;
    if (stat(overlayPath.c_str(), &ost) == 0) {
        if (!loadOverlay()) {
            // The overlay exists but is unreadable: refuse rather than silently
            // discarding the guest's writes by falling back to a fresh scan.
            LOG_CRIT("Virtual SD overlay is unreadable: %s\n", overlayPath.c_str());
            return;
        }
        opened = true;

        // Detect whether the host folder changed under the pinned layout
        Node fresh;
        struct stat st;
        if (stat(rootPath.c_str(), &st) == 0 && S_ISDIR(st.st_mode)) {
            fresh.isDir = true;
            fresh.hostPath = rootPath;
            fatTimestamp(st.st_mtime, fresh.fatDate, fresh.fatTime);
            scan(rootPath, fresh);
            drifted = (computeSignature(fresh) != manifestSignature);
        }
        else {
            drifted = true; // the folder vanished out from under the overlay
        }
        if (drifted)
            LOG_CRIT("Virtual SD folder changed since the overlay was pinned\n");
        return;
    }

    // Fresh mount: the root path must be an existing directory to back the card
    struct stat st;
    if (stat(rootPath.c_str(), &st) != 0 || !S_ISDIR(st.st_mode)) {
        LOG_CRIT("Virtual SD root is not a directory: %s\n", rootPath.c_str());
        return;
    }

    // Scan the folder into a tree, lay it out, and pin its layout signature
    root.isDir = true;
    root.hostPath = rootPath;
    fatTimestamp(st.st_mtime, root.fatDate, root.fatTime);
    scan(rootPath, root);
    allocate(root, true);
    manifestSignature = computeSignature(root);
    opened = true;
    LOG_INFO("Virtual SD card synthesized from %s (%u clusters used)\n",
        rootPath.c_str(), nextFreeCluster - 2);
}

VirtualFatBlock::~VirtualFatBlock() {
    for (auto &pair : openFiles)
        if (pair.second) fclose(pair.second);
}

void VirtualFatBlock::scan(const std::string &path, Node &node) {
    // Read directory entries, skipping "." and ".."
    DIR *dir = opendir(path.c_str());
    if (!dir) return;
    while (struct dirent *ent = readdir(dir)) {
        std::string name = ent->d_name;
        if (name == "." || name == "..") continue;
        std::string child = path + "/" + name;
        struct stat st;
        if (stat(child.c_str(), &st) != 0) continue;

        Node n;
        n.name = name;
        n.hostPath = child;
        n.isDir = S_ISDIR(st.st_mode);
        n.size = n.isDir ? 0 : uint64_t(st.st_size);
        fatTimestamp(st.st_mtime, n.fatDate, n.fatTime);
        node.children.push_back(std::move(n));
    }
    closedir(dir);

    // Deterministic order so the layout (and any overlay) stays stable
    std::sort(node.children.begin(), node.children.end(),
        [](const Node &a, const Node &b) { return a.name < b.name; });

    // Recurse into subdirectories now that the tree node is stable
    for (Node &c : node.children)
        if (c.isDir) scan(c.hostPath, c);
}

void VirtualFatBlock::allocate(Node &node, bool isRoot) {
    if (node.isDir) {
        // Size the directory from its entry count, then claim its clusters
        size_t entries = isRoot ? 0 : 2; // "." and ".." for subdirectories
        for (Node &c : node.children)
            entries += 1 + lfnEntryCount(c.name);
        uint64_t bytes = entries * 32;
        uint32_t clusters = std::max<uint32_t>(1, uint32_t((bytes + clusterBytes - 1) / clusterBytes));
        node.firstCluster = nextFreeCluster;
        node.clusterCount = clusters;
        nextFreeCluster += clusters;
        extents.push_back({ node.firstCluster, clusters, &node });

        // Recurse, recording each child's parent cluster for its ".." entry
        for (Node &c : node.children) {
            c.parentCluster = isRoot ? 0 : node.firstCluster;
            allocate(c, false);
        }
    }
    else if (node.size > 0) {
        uint32_t clusters = uint32_t((node.size + clusterBytes - 1) / clusterBytes);
        node.firstCluster = nextFreeCluster;
        node.clusterCount = clusters;
        nextFreeCluster += clusters;
        extents.push_back({ node.firstCluster, clusters, &node });
    }
}

const VirtualFatBlock::Extent *VirtualFatBlock::findExtent(uint32_t cluster) const {
    // Binary search the ascending, non-overlapping extent list
    int lo = 0, hi = int(extents.size()) - 1;
    while (lo <= hi) {
        int mid = (lo + hi) / 2;
        const Extent &e = extents[mid];
        if (cluster < e.startCluster) hi = mid - 1;
        else if (cluster >= e.startCluster + e.clusterCount) lo = mid + 1;
        else return &e;
    }
    return nullptr;
}

uint32_t VirtualFatBlock::fatEntry(uint32_t cluster) const {
    if (cluster == 0) return 0x0FFFFFF8; // media descriptor
    if (cluster == 1) return 0x0FFFFFFF; // reserved / EOC
    const Extent *e = findExtent(cluster);
    if (!e) return 0x00000000; // free
    return (cluster == e->startCluster + e->clusterCount - 1) ? 0x0FFFFFFF : cluster + 1;
}

const std::vector<uint8_t> &VirtualFatBlock::dirBytes(const Node *node) {
    auto it = dirCache.find(node);
    if (it != dirCache.end()) return it->second;

    std::vector<uint8_t> out;
    std::set<std::string> used;
    uint8_t e[32];

    auto emitShort = [&](const std::string &shortName, bool isDir, uint32_t firstCluster,
        uint32_t size, uint16_t date, uint16_t fatTime) {
        memset(e, 0, 32);
        memcpy(e, shortName.data(), 11);
        e[11] = isDir ? 0x10 : 0x20; // ATTR_DIRECTORY or ATTR_ARCHIVE
        putLE16(e + 14, fatTime); putLE16(e + 16, date); // creation
        putLE16(e + 18, date); // last access
        putLE16(e + 20, firstCluster >> 16);
        putLE16(e + 22, fatTime); putLE16(e + 24, date); // last write
        putLE16(e + 26, firstCluster & 0xFFFF);
        putLE32(e + 28, size);
        out.insert(out.end(), e, e + 32);
    };

    // Subdirectories begin with "." (self) and ".." (parent) entries
    if (node != &root) {
        emitShort(".          ", true, node->firstCluster, 0, node->fatDate, node->fatTime);
        emitShort("..         ", true, node->parentCluster, 0, node->fatDate, node->fatTime);
    }

    for (const Node &c : node->children) {
        size_t lfn = lfnEntryCount(c.name);
        std::string shortName = makeShortName(c.name, lfn > 0, used);

        // Long-name entries precede the short entry, highest order first
        if (lfn > 0) {
            std::vector<uint16_t> u16 = utf8ToUtf16(c.name);
            uint8_t cks = lfnChecksum(shortName);
            for (int ord = int(lfn); ord >= 1; ord--) {
                memset(e, 0, 32);
                e[0] = uint8_t(ord | (ord == int(lfn) ? 0x40 : 0));
                e[11] = 0x0F; // ATTR_LONG_NAME
                e[13] = cks;
                int base = (ord - 1) * 13;
                for (int i = 0; i < 13; i++) {
                    int idx = base + i;
                    uint16_t ch = (idx < int(u16.size())) ? u16[idx]
                        : (idx == int(u16.size()) ? 0x0000 : 0xFFFF);
                    int off = (i < 5) ? (1 + i * 2) : (i < 11) ? (14 + (i - 5) * 2) : (28 + (i - 11) * 2);
                    putLE16(e + off, ch);
                }
                out.insert(out.end(), e, e + 32);
            }
        }
        emitShort(shortName, c.isDir, c.firstCluster, c.isDir ? 0 : uint32_t(c.size), c.fatDate, c.fatTime);
    }

    auto res = dirCache.emplace(node, std::move(out));
    return res.first->second;
}

FILE *VirtualFatBlock::openHostFile(const std::string &path) {
    auto it = openFiles.find(path);
    if (it != openFiles.end()) return it->second;
    FILE *f = fopen(path.c_str(), "rb");
    openFiles[path] = f;
    return f;
}

void VirtualFatBlock::buildBootSector(uint8_t *s) {
    memset(s, 0, bytesPerSec);
    s[0] = 0xEB; s[1] = 0x58; s[2] = 0x90;
    memcpy(s + 3, "MSWIN4.1", 8);
    putLE16(s + 11, bytesPerSec);
    s[13] = secPerClus;
    putLE16(s + 14, rsvdSecCnt);
    s[16] = numFATs;
    putLE16(s + 17, 0); // RootEntCnt
    putLE16(s + 19, 0); // TotSec16
    s[21] = 0xF8; // media
    putLE16(s + 22, 0); // FATSz16
    putLE16(s + 24, 63); // SecPerTrk
    putLE16(s + 26, 255); // NumHeads
    putLE32(s + 28, 0); // HiddSec
    putLE32(s + 32, totalSectors);
    putLE32(s + 36, fatSz);
    putLE16(s + 40, 0); // ExtFlags
    putLE16(s + 42, 0); // FSVer
    putLE32(s + 44, 2); // RootClus
    putLE16(s + 48, 1); // FSInfo sector
    putLE16(s + 50, 6); // BkBootSec
    s[64] = 0x80; // DrvNum
    s[66] = 0x29; // BootSig
    memcpy(s + 71, "NO NAME    ", 11);
    memcpy(s + 82, "FAT32   ", 8);
    s[510] = 0x55; s[511] = 0xAA;
}

void VirtualFatBlock::buildFsInfo(uint8_t *s) {
    memset(s, 0, bytesPerSec);
    putLE32(s + 0, 0x41615252); // LeadSig
    putLE32(s + 484, 0x61417272); // StrucSig
    uint32_t allocated = nextFreeCluster - 2;
    putLE32(s + 488, (countOfClusters > allocated) ? countOfClusters - allocated : 0); // FreeCount
    putLE32(s + 492, nextFreeCluster); // NxtFree
    putLE32(s + 508, 0xAA550000); // TrailSig
}

void VirtualFatBlock::synthSector(uint32_t sector, uint8_t *out) {
    memset(out, 0, bytesPerSec);

    // Reserved region: boot sector, FSInfo, and the backup boot sector
    if (sector == 0 || sector == 6) return buildBootSector(out);
    if (sector == 1) return buildFsInfo(out);
    if (sector < rsvdSecCnt) return;

    // FAT region: both copies map to the same computed entries
    if (sector < rsvdSecCnt + 2 * fatSz) {
        uint32_t fatSec = (sector - rsvdSecCnt) % fatSz;
        uint32_t first = fatSec * (bytesPerSec / 4);
        for (uint32_t i = 0; i < bytesPerSec / 4; i++)
            putLE32(out + i * 4, fatEntry(first + i));
        return;
    }

    // Data region: resolve the cluster to a directory or file extent
    uint32_t cluster = 2 + (sector - firstDataSector) / secPerClus;
    uint32_t secInClus = (sector - firstDataSector) % secPerClus;
    const Extent *e = findExtent(cluster);
    if (!e) return; // free cluster reads back as zeros
    uint64_t byteOff = uint64_t(cluster - e->startCluster) * clusterBytes + secInClus * bytesPerSec;

    if (e->node->isDir) {
        const std::vector<uint8_t> &bytes = dirBytes(e->node);
        if (byteOff < bytes.size())
            memcpy(out, bytes.data() + byteOff, std::min<uint64_t>(bytesPerSec, bytes.size() - byteOff));
    }
    else if (byteOff < e->node->size) {
        if (FILE *f = openHostFile(e->node->hostPath)) {
            FSEEK64(f, byteOff, SEEK_SET);
            uint64_t avail = std::min<uint64_t>(bytesPerSec, e->node->size - byteOff);
            fread(out, 1, avail, f);
        }
    }
}

void VirtualFatBlock::readSector(uint32_t sector, uint8_t *out) {
    auto it = overlay.find(sector);
    if (it != overlay.end())
        memcpy(out, it->second.data(), bytesPerSec);
    else
        synthSector(sector, out);
}

void VirtualFatBlock::writeSector(uint32_t sector, const uint8_t *in) {
    std::vector<uint8_t> &slot = overlay[sector];
    if (slot.empty()) slot.resize(bytesPerSec);
    memcpy(slot.data(), in, bytesPerSec);
}

bool VirtualFatBlock::read(uint64_t offset, size_t size, void *buffer) {
    if (!opened) return false;
    uint8_t *out = (uint8_t*)buffer;
    uint8_t sec[bytesPerSec];
    while (size > 0) {
        uint32_t sector = uint32_t(offset >> 9);
        uint32_t within = uint32_t(offset & 511);
        size_t chunk = std::min<size_t>(size, bytesPerSec - within);
        readSector(sector, sec);
        memcpy(out, sec + within, chunk);
        out += chunk; offset += chunk; size -= chunk;
    }
    return true;
}

bool VirtualFatBlock::write(uint64_t offset, size_t size, const void *buffer) {
    if (!opened) return false;
    dirty = true;
    const uint8_t *in = (const uint8_t*)buffer;
    uint8_t sec[bytesPerSec];
    while (size > 0) {
        uint32_t sector = uint32_t(offset >> 9);
        uint32_t within = uint32_t(offset & 511);
        size_t chunk = std::min<size_t>(size, bytesPerSec - within);
        if (within != 0 || chunk != bytesPerSec) readSector(sector, sec); // read-modify-write
        memcpy(sec + within, in, chunk);
        writeSector(sector, sec);
        in += chunk; offset += chunk; size -= chunk;
    }
    return true;
}

uint64_t VirtualFatBlock::computeSignature(const Node &node) const {
    // FNV-1a over the folder's layout-relevant identity, in deterministic order:
    // names, the tree structure, and (for files) size and modification time. A
    // directory's own mtime is excluded — it merely reflects child changes, which
    // are already captured, and it doesn't affect the cluster layout. So adding
    // then removing a file restores the signature rather than reporting drift.
    uint64_t h = 14695981039346656037ULL;
    auto byte = [&](uint8_t b) { h ^= b; h *= 1099511628211ULL; };
    auto mix = [&](uint64_t v) { for (int i = 0; i < 8; i++) byte((v >> (8 * i)) & 0xFF); };

    for (char c : node.name) byte(uint8_t(c));
    byte(node.isDir ? 1 : 0);
    if (!node.isDir) {
        mix(node.size);
        mix((uint64_t(node.fatDate) << 16) | node.fatTime);
    }
    for (const Node &c : node.children)
        mix(computeSignature(c));
    return h;
}

void VirtualFatBlock::rebuildExtents(Node &node) {
    // Recreate the cluster -> node map from a deserialized tree, in the same
    // ascending order the allocator produced so the list stays sorted
    if (node.isDir) {
        extents.push_back({ node.firstCluster, node.clusterCount, &node });
        for (Node &c : node.children)
            rebuildExtents(c);
    }
    else if (node.clusterCount > 0) {
        extents.push_back({ node.firstCluster, node.clusterCount, &node });
    }
}

void VirtualFatBlock::serializeNode(const Node &node, std::vector<uint8_t> &out) const {
    wrStr(out, node.name);
    wrStr(out, node.hostPath);
    wr8(out, node.isDir ? 1 : 0);
    wr64(out, node.size);
    wr16(out, node.fatDate);
    wr16(out, node.fatTime);
    wr32(out, node.firstCluster);
    wr32(out, node.clusterCount);
    wr32(out, node.parentCluster);
    wr32(out, uint32_t(node.children.size()));
    for (const Node &c : node.children)
        serializeNode(c, out);
}

void VirtualFatBlock::deserializeNode(Node &node, const uint8_t *&p, const uint8_t *end) {
    bool ok = true;
    node.name = rdStr(p, end, ok);
    node.hostPath = rdStr(p, end, ok);
    node.isDir = rd8(p, end, ok) != 0;
    node.size = rd64(p, end, ok);
    node.fatDate = rd16(p, end, ok);
    node.fatTime = rd16(p, end, ok);
    node.firstCluster = rd32(p, end, ok);
    node.clusterCount = rd32(p, end, ok);
    node.parentCluster = rd32(p, end, ok);
    uint32_t count = rd32(p, end, ok);
    if (!ok || count > (1u << 20)) { p = end + 1; return; } // bail on corruption
    node.children.resize(count);
    for (uint32_t i = 0; i < count; i++)
        deserializeNode(node.children[i], p, end);
}

bool VirtualFatBlock::loadOverlay() {
    FILE *f = fopen(overlayPath.c_str(), "rb");
    if (!f) return false;

    // Slurp the whole sidecar (it only holds written sectors, so it stays small)
    fseeko(f, 0, SEEK_END);
    long len = ftello(f);
    fseeko(f, 0, SEEK_SET);
    std::vector<uint8_t> buf(len > 0 ? len : 0);
    if (len <= 0 || fread(buf.data(), 1, len, f) != size_t(len)) { fclose(f); return false; }
    fclose(f);

    const uint8_t *p = buf.data(), *end = p + buf.size();
    bool ok = true;
    if (buf.size() < 8 || memcmp(p, OVERLAY_MAGIC, 8) != 0) return false;
    p += 8;
    uint32_t version = rd32(p, end, ok);
    uint32_t flags = rd32(p, end, ok);
    uint32_t storedTotal = rd32(p, end, ok);
    uint32_t storedFatSz = rd32(p, end, ok);
    uint32_t storedFirstData = rd32(p, end, ok);
    nextFreeCluster = rd32(p, end, ok);
    manifestSignature = rd64(p, end, ok);
    if (!ok || version != 1 || storedTotal != totalSectors ||
        storedFatSz != fatSz || storedFirstData != firstDataSector)
        return false;

    // Manifest (the pinned folder layout) then the overlaid sectors
    deserializeNode(root, p, end);
    uint32_t sectorCount = rd32(p, end, ok);
    if (!ok) return false;
    overlay.clear();
    for (uint32_t i = 0; i < sectorCount; i++) {
        uint32_t sector = rd32(p, end, ok);
        if (!ok || p + bytesPerSec > end) return false;
        std::vector<uint8_t> &slot = overlay[sector];
        slot.assign(p, p + bytesPerSec);
        p += bytesPerSec;
    }

    rebuildExtents(root);
    dirty = (flags & 1) != 0;
    return true;
}

void VirtualFatBlock::saveOverlay() {
    std::vector<uint8_t> buf;
    buf.insert(buf.end(), OVERLAY_MAGIC, OVERLAY_MAGIC + 8);
    wr32(buf, 1); // version
    wr32(buf, dirty ? 1 : 0); // flags
    wr32(buf, totalSectors);
    wr32(buf, fatSz);
    wr32(buf, firstDataSector);
    wr32(buf, nextFreeCluster);
    wr64(buf, manifestSignature);
    serializeNode(root, buf);
    wr32(buf, uint32_t(overlay.size()));
    for (const auto &pair : overlay) {
        wr32(buf, pair.first);
        buf.insert(buf.end(), pair.second.begin(), pair.second.end());
    }

    // Write to a temp file and rename so a crash mid-write can't corrupt a good
    // overlay (the rename is atomic on the same filesystem)
    std::string tmp = overlayPath + ".tmp";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) { LOG_CRIT("Failed to write virtual SD overlay: %s\n", tmp.c_str()); return; }
    fwrite(buf.data(), 1, buf.size(), f);
    fclose(f);
    if (rename(tmp.c_str(), overlayPath.c_str()) != 0)
        LOG_CRIT("Failed to commit virtual SD overlay: %s\n", overlayPath.c_str());
}

void VirtualFatBlock::flush() {
    if (opened && dirty) saveOverlay();
}

void VirtualFatBlock::discardOverlay() {
    overlay.clear();
    dirty = false;
    remove(overlayPath.c_str());
}
