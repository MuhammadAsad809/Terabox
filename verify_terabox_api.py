#!/usr/bin/env python3
"""
TeraBox Real API & HTTP Range Verification Suite
Used for Technical Verification Gates:
- Gate 1: TeraBox Authentication & Session Validation
- Gate 2: Real API & File Hierarchy Access Verification
- Gate 3: HTTP Range Seeking & Byte-level Partial Content Test (for GameHub / VLC VFS compatibility)
"""

import sys
import os
import json
import time
import ssl
import urllib.request
import urllib.parse
import urllib.error

class TeraBoxVerifier:
    def __init__(self, ndus: str):
        self.ndus = ndus.strip()
        self.base_url = "https://www.terabox.com"
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Cookie": f"ndus={self.ndus};",
            "Accept": "application/json, text/plain, */*",
            "Referer": "https://www.terabox.com/main"
        }
        self.ssl_context = ssl.create_default_context()

    def check_auth_and_quota(self):
        print("\n=======================================================")
        print(" [GATE 1] TeraBox Authentication & Quota Verification")
        print("=======================================================")
        url = f"{self.base_url}/api/quota?checkexpire=1&checkfree=1"
        req = urllib.request.Request(url, headers=self.headers)
        
        try:
            with urllib.request.urlopen(req, timeout=15, context=self.ssl_context) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                
                errno = data.get("errno", -1)
                if errno != 0:
                    print(f"❌ Authentication Failed: errno={errno}, response={data}")
                    return False, data
                
                total_bytes = data.get("total", 0)
                used_bytes = data.get("used", 0)
                free_bytes = data.get("free", total_bytes - used_bytes)
                
                total_gb = total_bytes / (1024 ** 3)
                used_gb = used_bytes / (1024 ** 3)
                free_gb = free_bytes / (1024 ** 3)
                
                print(f"✅ Authentication SUCCESSFUL!")
                print(f"   • Total Cloud Space: {total_gb:.2f} GB ({total_bytes} bytes)")
                print(f"   • Used Space:        {used_gb:.2f} GB ({used_bytes} bytes)")
                print(f"   • Free Space:        {free_gb:.2f} GB ({free_bytes} bytes)")
                return True, data
        except Exception as e:
            print(f"❌ Request Error connecting to TeraBox API: {e}")
            return False, str(e)

    def list_files(self, dir_path="/"):
        print("\n=======================================================")
        print(f" [GATE 2] Real File Access & Directory Traversal: '{dir_path}'")
        print("=======================================================")
        params = urllib.parse.urlencode({
            "dir": dir_path,
            "order": "name",
            "desc": 0,
            "num": 100,
            "page": 1,
            "showempty": 0
        })
        url = f"{self.base_url}/api/list?{params}"
        req = urllib.request.Request(url, headers=self.headers)

        try:
            with urllib.request.urlopen(req, timeout=15, context=self.ssl_context) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                errno = data.get("errno", -1)
                if errno != 0:
                    print(f"❌ Directory Listing Failed: errno={errno}, response={data}")
                    return []
                
                file_list = data.get("list", [])
                print(f"✅ Directory listing retrieved successfully! Total items: {len(file_list)}")
                
                for idx, item in enumerate(file_list):
                    is_dir = "DIR " if item.get("isdir") == 1 else "FILE"
                    size_mb = item.get("size", 0) / (1024 ** 2)
                    name = item.get("server_filename", "unnamed")
                    fs_id = item.get("fs_id")
                    print(f"   [{idx + 1:02d}] [{is_dir}] {name} ({size_mb:.2f} MB, fs_id: {fs_id})")
                
                return file_list
        except Exception as e:
            print(f"❌ Error fetching directory listing: {e}")
            return []

    def get_download_link(self, fs_id: int):
        print("\n=======================================================")
        print(f" Resolving Direct Download Link for fs_id: {fs_id}")
        print("=======================================================")
        params = urllib.parse.urlencode({
            "fidlist": f"[{fs_id}]",
            "type": "dlink"
        })
        url = f"{self.base_url}/api/download?{params}"
        req = urllib.request.Request(url, headers=self.headers)
        
        try:
            with urllib.request.urlopen(req, timeout=15, context=self.ssl_context) as resp:
                data = json.loads(resp.read().decode('utf-8'))
                dlink_list = data.get("dlink", [])
                if not dlink_list:
                    print(f"❌ No dlink returned: {data}")
                    return None
                
                dlink = dlink_list[0].get("dlink")
                print(f"✅ Direct CDN Download Link Acquired!")
                print(f"   • CDN URL: {dlink[:80]}...")
                return dlink
        except Exception as e:
            print(f"❌ Error fetching download link: {e}")
            return None

    def test_http_range_seeking(self, dlink: str, file_size: int, filename: str):
        print("\n=======================================================")
        print(" [GATE 3] Real HTTP Range & Random-Access Seeking Test")
        print(f" Target File: '{filename}' | Total Size: {file_size / (1024**2):.2f} MB")
        print("=======================================================")

        if file_size < 1024:
            print("⚠️ File is too small for multi-chunk range test.")
            return False

        chunk_size = min(1024 * 1024, file_size // 4) # 1MB or fraction
        
        # Test 3 distinct seeking locations:
        # Location 1: Start of file (Header) -> offset 0
        # Location 2: Middle of file (e.g., video frame / game asset) -> offset file_size // 2
        # Location 3: End of file (ZIP/ISO Central Directory seeking!) -> offset file_size - chunk_size
        ranges_to_test = [
            ("Start (Header)", 0, min(chunk_size - 1, file_size - 1)),
            ("Middle (Random Seek)", file_size // 2, min((file_size // 2) + chunk_size - 1, file_size - 1)),
            ("End (ZIP Central Directory / Footer)", max(0, file_size - chunk_size), file_size - 1)
        ]

        all_passed = True

        for label, start_byte, end_byte in ranges_to_test:
            expected_bytes = end_byte - start_byte + 1
            print(f"\n⚡ Testing Range: {label}")
            print(f"   Requesting bytes={start_byte}-{end_byte} (Expected: {expected_bytes} bytes)...")
            
            headers = self.headers.copy()
            headers["Range"] = f"bytes={start_byte}-{end_byte}"
            
            req = urllib.request.Request(dlink, headers=headers)
            t0 = time.time()
            try:
                with urllib.request.urlopen(req, timeout=20, context=self.ssl_context) as resp:
                    t_latency = (time.time() - t0) * 1000
                    status_code = resp.status
                    content_range = resp.headers.get("Content-Range", "None")
                    chunk_data = resp.read()
                    actual_bytes = len(chunk_data)
                    t_total = (time.time() - t0)
                    speed_mbps = (actual_bytes / (1024 * 1024)) / t_total if t_total > 0 else 0
                    
                    if status_code == 206:
                        print(f"   ✅ HTTP 206 Partial Content confirmed!")
                        print(f"   • Content-Range: {content_range}")
                        print(f"   • Received: {actual_bytes} bytes")
                        print(f"   • Initial Latency: {t_latency:.1f} ms")
                        print(f"   • Throughput: {speed_mbps:.2f} MB/s")
                    else:
                        print(f"   ⚠️ Unexpected Status Code: {status_code} (Expected 206)")
                        all_passed = False
                    
                    if actual_bytes != expected_bytes:
                        print(f"   ❌ Byte length mismatch: got {actual_bytes}, expected {expected_bytes}")
                        all_passed = False
            except urllib.error.HTTPError as e:
                print(f"   ❌ HTTP Error: {e.code} {e.reason}")
                all_passed = False
            except Exception as e:
                print(f"   ❌ Network Error: {e}")
                all_passed = False

        if all_passed:
            print("\n=======================================================")
            print(" 🎉 [GATE 3 PASSED] TeraBox CDN supports real HTTP Range seeking!")
            print(" GameHub and VLC can seek anywhere in the file with zero download overhead!")
            print("=======================================================")
        else:
            print("\n❌ [GATE 3 FAILED] HTTP Range seeking encountered issues.")
            
        return all_passed

def main():
    ndus = None
    if len(sys.argv) > 1:
        ndus = sys.argv[1]
    elif "TERABOX_NDUS" in os.environ:
        ndus = os.environ["TERABOX_NDUS"]
        
    if not ndus:
        print("Usage: python verify_terabox_api.py <ndus_cookie>")
        print("Or set TERABOX_NDUS environment variable.")
        sys.exit(1)

    verifier = TeraBoxVerifier(ndus)
    
    # 1. Gate 1: Check Auth & Quota
    auth_ok, quota_data = verifier.check_auth_and_quota()
    if not auth_ok:
        sys.exit(1)
        
    # 2. Gate 2: List files
    files = verifier.list_files("/")
    if not files:
        print("No files found or directory listing empty.")
        sys.exit(1)
        
    # Find a non-directory file for Range testing
    test_file = None
    for item in files:
        if item.get("isdir") == 0 and item.get("size", 0) > 1024:
            test_file = item
            break
            
    if not test_file:
        print("No suitable file found in root directory for range testing. Please upload at least one file.")
        sys.exit(1)
        
    fs_id = test_file.get("fs_id")
    file_size = test_file.get("size", 0)
    filename = test_file.get("server_filename", "test")
    
    # 3. Resolve direct download link
    dlink = verifier.get_download_link(fs_id)
    if not dlink:
        # Fallback: check if item has direct dlink inside list response
        dlink = test_file.get("dlink")
        
    if not dlink:
        print("Could not resolve direct dlink.")
        sys.exit(1)
        
    # 4. Gate 3: Test HTTP Range seeking
    range_ok = verifier.test_http_range_seeking(dlink, file_size, filename)
    if not range_ok:
        sys.exit(1)
        
    print("\n✅ All technical verification gates for Phase 1-3 successfully verified!")

if __name__ == "__main__":
    main()
